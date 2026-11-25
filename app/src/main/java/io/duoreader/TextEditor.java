package io.duoreader;

import android.graphics.Typeface;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.*;

/** A document session owns editing and drafts; activities only own its views. */
public final class TextEditor {
  private final ReaderApp app;
  private final ThreadPoolExecutor io = TaskExecutors.create("reader-documents", 1);
  private TextDocument document;
  private NumberedText source;
  private TextView rendered, statusView;
  private ScrollView previewScroll;
  private FrameLayout primary;
  private MainActivity primaryOwner;
  private int primaryTheme = -1;
  private final EditHistory history = new EditHistory();
  private AppKeyboard keyboard;
  private Button inputMode;
  private View cursorFocus;
  private boolean keyboardNavigation;
  private String text = "";
  private long revision, savedRevision;
  private int cursor;
  private boolean preview, replaying, saving;
  private Future<?> previewTask;
  private Future<?> draftTask;
  private TextDocument lastDraftDocument;
  private long lastDraftRevision = -1;
  private int lastDraftCursor = -1;
  private boolean lastDraftDirty;
  private int previewGeneration;
  private final Runnable writeDraft = this::flush;

  TextEditor(ReaderApp app) {
    this.app = app;
  }

  boolean active() {
    return document != null && app.current != null && document.uri.equals(app.current.uri);
  }

  public int cursor() {
    return source == null ? cursor : Math.max(0, source.getSelectionStart());
  }

  String content() {
    return source == null ? text : source.getText().toString();
  }

  void awaitWrites() throws Exception {
    io.submit(() -> {}).get(15, TimeUnit.SECONDS);
  }

  private File draft(String uri) {
    return new File(app.getFilesDir(), "draft-" + Library.id(uri) + ".json");
  }

  Future<?> open(Library.Entry entry, int generation) {
    return app.worker.submit(
        () -> {
          try {
            TextDocument loaded = TextDocument.load(app, entry.uri);
            String content = loaded.text;
            int caret = app.prefs.getInt(app.key(entry.uri) + "offset", 0);
            boolean recovered = false;
            File draft = draft(entry.uri);
            if ((draft.isFile() || new File(draft.getPath() + ".bak").isFile())
                && draft.length() <= TextDocument.MAX_BYTES * 6L) {
              JSONObject json;
              try (InputStream input = new android.util.AtomicFile(draft).openRead();
                  ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                EpubCache.copy(input, output, TextDocument.MAX_BYTES * 6L);
                json = new JSONObject(output.toString("UTF-8"));
              }
              String saved = json.getString("text");
              if (saved.length() <= TextDocument.MAX_CHARS) {
                content = saved;
                caret = json.optInt("cursor", caret);
                recovered = !content.equals(loaded.text);
                if (recovered)
                  loaded.recoverAgainst(json.optString("baseline", loaded.fingerprint()));
              }
            }
            String restored = content;
            int restoredCursor = caret;
            boolean dirty = recovered;
            app.main.post(
                () -> {
                  if (generation != app.openGeneration || app.closing) return;
                  app.save();
                  clear();
                  app.comics.clear();
                  if (app.book != null) {
                    try {
                      app.book.close();
                    } catch (IOException ignored) {
                    }
                    app.book = null;
                  }
                  document = loaded;
                  text = restored;
                  cursor = Math.max(0, Math.min(restored.length(), restoredCursor));
                  revision = dirty ? 1 : 0;
                  savedRevision = 0;
                  preview = false;
                  app.current = entry;
                  app.selected = entry;
                  app.mode = "editing";
                  app.busy = false;
                  app.restoring = false;
                  app.resumePending = false;
                  app.status = "";
                  app.topPage = app.bottomPage = null;
                  app.marks = new JSONArray();
                  app.save();
                  app.changed();
                  if (dirty && app.controls != null) app.controls.toast("Recovered unsaved draft");
                });
          } catch (Exception error) {
            app.main.post(() -> app.openFailed(generation, error));
          }
        });
  }

  void clear() {
    flush();
    app.main.removeCallbacks(writeDraft);
    ++previewGeneration;
    TaskExecutors.cancel(previewTask, app.lookups);
    document = null;
    source = null;
    primary = null;
    primaryOwner = null;
    keyboard = null;
    inputMode = null;
    cursorFocus = null;
    keyboardNavigation = false;
    history.clear();
    saving = false;
    rendered = null;
    previewScroll = null;
    statusView = null;
  }

  void flush() {
    if (document == null) return;
    app.main.removeCallbacks(writeDraft);
    final String uri = document.uri;
    final TextDocument target = document;
    final int caret = cursor();
    final boolean dirty = revision != savedRevision;
    if (lastDraftDocument == document
        && lastDraftRevision == revision
        && lastDraftCursor == caret
        && lastDraftDirty == dirty) return;
    final String snapshot = content();
    text = snapshot;
    final long snapshotRevision = revision;
    if (lastDraftDocument == document
        && draftTask instanceof Runnable
        && io.remove((Runnable) draftTask)) draftTask.cancel(false);
    lastDraftDocument = document;
    lastDraftRevision = revision;
    lastDraftCursor = caret;
    lastDraftDirty = dirty;
    draftTask =
        io.submit(
            () -> {
              try {
                if (!dirty) {
                  new android.util.AtomicFile(draft(uri)).delete();
                  return;
                }
                JSONObject json =
                    new JSONObject()
                        .put("text", snapshot)
                        .put("cursor", caret)
                        .put("baseline", target.draftFingerprint());
                TextDocument.writeAtomic(draft(uri), json.toString().getBytes("UTF-8"));
              } catch (Exception error) {
                app.main.post(
                    () -> {
                      if (lastDraftDocument == target
                          && lastDraftRevision == snapshotRevision
                          && lastDraftCursor == caret) lastDraftRevision = -1;
                      if (app.controls != null)
                        app.controls.toast("Draft could not be saved: " + error.getMessage());
                    });
              }
            });
  }

  View primary(MainActivity owner) {
    if (primary != null && primaryOwner == owner && primaryTheme == app.theme) return primary;
    cursor = cursor();
    text = content();
    primaryTheme = app.theme;
    primaryOwner = owner;
    primary = new FrameLayout(owner);
    ReaderUi palette = new ReaderUi(owner, app);
    source = new NumberedText(palette, true);
    source.setFilters(new InputFilter[] {new InputFilter.LengthFilter(TextDocument.MAX_CHARS)});
    source.limitLines(
        TextDocument.MAX_LINES,
        () -> {
          if (app.controls != null) app.controls.toast("Text documents support up to 20,000 lines");
        });
    source.setText(text);
    source.setSelection(Math.min(text.length(), cursor));
    source.setCursorVisible(true);
    source.addTextChangedListener(
        new TextWatcher() {
          String removed = "";

          public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            if (!replaying) removed = s.subSequence(start, start + count).toString();
          }

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (!replaying)
              history.record(start, removed, s.subSequence(start, start + count).toString());
          }

          public void afterTextChanged(Editable value) {
            cursor = cursor();
            revision++;
            app.main.removeCallbacks(writeDraft);
            app.main.postDelayed(writeDraft, 250);
            updateStatus();
          }
        });
    primary.addView(source, new FrameLayout.LayoutParams(-1, -1));
    rendered = palette.text("", 18, palette.ink());
    rendered.setTypeface(Typeface.create("serif", 0));
    rendered.setPadding(palette.dp(16), palette.dp(16), palette.dp(16), palette.dp(16));
    previewScroll = new ScrollView(owner);
    previewScroll.addView(rendered);
    primary.addView(previewScroll, new FrameLayout.LayoutParams(-1, -1));
    previewScroll.setBackgroundColor(palette.surface());
    applyPreview();
    source.post(() -> source.bringPointIntoView(cursor));
    return primary;
  }

  void buildControls(ReaderUi ui) {
    LinearLayout body = ui.column();
    body.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
    LinearLayout navigation = ui.row();
    ui.addWeighted(navigation, ui.button("Library", app::library));
    ui.addWeighted(navigation, ui.button("Save", this::saveFile));
    if (DocumentFormat.of(app.current.name) == DocumentFormat.MARKDOWN)
      ui.addWeighted(
          navigation,
          ui.button(
              "Source / Preview",
              () -> {
                preview = !preview;
                applyPreview();
              }));
    ui.addWeighted(navigation, ui.button("Menu", this::menu));
    body.addView(navigation);
    LinearLayout status = ui.row();
    statusView = ui.text("", 12, ui.muted());
    status.addView(statusView, new LinearLayout.LayoutParams(0, -2, 1));
    inputMode = ui.button("", this::toggleInputMode);
    inputMode.setTextSize(12);
    status.addView(inputMode, new LinearLayout.LayoutParams(ui.dp(140), ui.dp(44)));
    body.addView(status);
    updateStatus();
    body.addView(new View(ui.activity), new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout arrows = ui.row();
    for (int direction : new int[] {-2, -1, 1, 2}) {
      String label = direction == -2 ? "↑" : direction == -1 ? "←" : direction == 1 ? "→" : "↓";
      ui.addWeighted(arrows, ui.button(label, () -> moveCursor(direction)));
    }
    body.addView(arrows);
    ui.addWeighted(arrows, ui.button("Undo", () -> history(true)));
    ui.addWeighted(arrows, ui.button("Redo", () -> history(false)));
    keyboard =
        new AppKeyboard(
            ui,
            new AppKeyboard.Target() {
              public void insert(String value) {
                edit(() -> source.insert(value));
              }

              public void erase() {
                edit(() -> source.erase());
              }
            },
            true);
    body.addView(keyboard, new LinearLayout.LayoutParams(-1, -2));
    ui.root.addView(body, new FrameLayout.LayoutParams(-1, -1));
    cursorFocus = body;
    body.setFocusableInTouchMode(true);
    body.post(
        () -> {
          if (cursorFocus == body) updateInputMode();
        });
  }

  private void moveCursor(int direction) {
    edit(() -> source.move(direction));
    cursor = cursor();
  }

  private void toggleInputMode() {
    keyboardNavigation = !keyboardNavigation;
    updateInputMode();
  }

  private void updateInputMode() {
    if (inputMode == null || keyboard == null || cursorFocus == null) return;
    inputMode.setText("D-pad: " + (keyboardNavigation ? "Keys" : "Cursor"));
    inputMode.setContentDescription(
        "D-pad controls "
            + (keyboardNavigation ? "keyboard navigation" : "the text cursor")
            + ". Press Select or tap to switch.");
    cursorFocus.setFocusableInTouchMode(!keyboardNavigation);
    cursorFocus.setFocusable(!keyboardNavigation);
    if (keyboardNavigation) keyboard.focusFirstKey();
    else cursorFocus.requestFocus();
  }

  private void moveControlFocus(int direction) {
    int focusDirection =
        direction == -1
            ? View.FOCUS_LEFT
            : direction == 1 ? View.FOCUS_RIGHT : direction == -2 ? View.FOCUS_UP : View.FOCUS_DOWN;
    // Search inside the unrotated content, rather than Android's landscape window.
    View next =
        FocusFinder.getInstance()
            .findNextFocus(app.controls.root, app.bottom.getCurrentFocus(), focusDirection);
    if (next != null) next.requestFocusFromTouch();
  }

  private void edit(Runnable action) {
    if (source == null) return;
    preview = false;
    applyPreview();
    action.run();
    source.bringPointIntoView(cursor());
  }

  private void applyPreview() {
    if (source == null || previewScroll == null) return;
    source.setVisibility(preview ? View.GONE : View.VISIBLE);
    previewScroll.setVisibility(preview ? View.VISIBLE : View.GONE);
    if (!preview) return;
    int generation = ++previewGeneration;
    String snapshot = content();
    TaskExecutors.cancel(previewTask, app.lookups);
    previewTask =
        app.lookups.submit(
            () -> {
              try {
                Spanned result = MarkdownPreview.render(snapshot);
                app.main.post(
                    () -> {
                      if (generation == previewGeneration && rendered != null)
                        rendered.setText(result);
                    });
              } catch (Exception error) {
                app.main.post(
                    () -> {
                      if (generation == previewGeneration && app.controls != null)
                        app.controls.message("Preview unavailable", error.getMessage());
                    });
              }
            });
  }

  private void updateStatus() {
    if (statusView != null && document != null)
      statusView.setText(
          (saving ? "Saving… · " : revision != savedRevision ? "Unsaved draft · " : "Saved · ")
              + app.current.name
              + " · "
              + (source == null ? text.length() : source.length())
              + " characters");
  }

  void saveFile() {
    if (document == null || saving) return;
    flush();
    saving = true;
    updateStatus();
    TextDocument target = document;
    String snapshot = content();
    long saved = revision;
    io.execute(
        () -> {
          try {
            target.save(app, snapshot);
            app.main.post(
                () -> {
                  if (document != target) return;
                  saving = false;
                  savedRevision = saved;
                  app.invalidatePreview(target.uri);
                  flush();
                  updateStatus();
                  if (app.controls != null) app.controls.toast("Document saved");
                });
          } catch (Exception error) {
            app.main.post(
                () -> {
                  if (document != target) return;
                  saving = false;
                  updateStatus();
                  if (app.controls != null)
                    app.controls.message("File not saved", error.getMessage());
                });
          }
        });
  }

  private void history(boolean reverse) {
    if (source == null) return;
    edit(
        () -> {
          replaying = true;
          try {
            int caret = history.apply(source.getText(), reverse);
            if (caret >= 0) source.setSelection(caret);
          } finally {
            replaying = false;
          }
        });
  }

  boolean handle(KeyEvent event) {
    if (keyboard != null && keyboard.handle(event)) return true;
    int key = event.getKeyCode();
    int direction =
        key == KeyEvent.KEYCODE_DPAD_LEFT
            ? -1
            : key == KeyEvent.KEYCODE_DPAD_RIGHT
                ? 1
                : key == KeyEvent.KEYCODE_DPAD_UP ? -2 : key == KeyEvent.KEYCODE_DPAD_DOWN ? 2 : 0;
    if (direction != 0) {
      if (event.getAction() == KeyEvent.ACTION_DOWN) {
        if (keyboardNavigation) moveControlFocus(direction);
        else moveCursor(direction);
      }
      return true;
    }
    if (key == KeyEvent.KEYCODE_BUTTON_SELECT) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
        toggleInputMode();
      return true;
    }
    if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_BUTTON_B) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) app.library();
      return true;
    }
    if (key == KeyEvent.KEYCODE_BUTTON_START || key == KeyEvent.KEYCODE_BUTTON_X) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) menu();
      return true;
    }
    if (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
        if (!keyboardNavigation) toggleInputMode();
        else {
          View focus = app.bottom.getCurrentFocus();
          if (focus != null) focus.performClick();
        }
      }
      return true;
    }
    if (event.isCtrlPressed() && key == KeyEvent.KEYCODE_Z) {
      if (event.getAction() == KeyEvent.ACTION_DOWN) history(!event.isShiftPressed());
      return true;
    }
    return false;
  }

  private void menu() {
    ReaderUi ui = app.controls;
    LinearLayout body = ui.column();
    ui.rotationControls(body);
    ui.option(body, "Save copy…", this::saveCopy);
    ui.option(
        body,
        "Reload file…",
        () ->
            ui.menu(
                "Discard draft and reload?",
                new String[] {"Keep editing", "Discard draft and reload"},
                choice -> {
                  if (choice != 1 || app.current == null) return;
                  Library.Entry original = app.current;
                  String uri = original.uri;
                  app.library();
                  clear();
                  io.execute(
                      () -> {
                        new android.util.AtomicFile(draft(uri)).delete();
                        app.main.post(
                            () -> {
                              if (!app.closing) app.open(original);
                            });
                      });
                }));
    ui.option(body, "Preferences", ui::preferences);
    ui.option(body, "Close app", app::close);
    ui.showDialog("Document", body);
  }

  private void saveCopy() {
    if (document == null) return;
    String content = content(), parent = app.folder, root = app.root;
    DocumentFormat format = DocumentFormat.of(app.current.name);
    app.controls.keyboard(
        "Save a copy",
        "",
        "Save copy",
        name -> {
          io.execute(
              () -> {
                try {
                  Library.Entry created =
                      new LibraryStorage(app, root).create(parent, name, format, false);
                  try {
                    TextDocument copy = TextDocument.load(app, created.uri);
                    copy.save(app, content);
                  } catch (Exception error) {
                    try {
                      new LibraryStorage(app, root).delete(parent, created);
                    } catch (Exception ignored) {
                    }
                    throw error;
                  }
                  app.main.post(
                      () -> {
                        if (!app.closing && app.controls != null)
                          app.controls.toast("Copy saved: " + created.name);
                      });
                } catch (Exception error) {
                  app.main.post(
                      () -> {
                        if (!app.closing && app.controls != null)
                          app.controls.message("Copy not saved", error.getMessage());
                      });
                }
              });
        });
  }
}
