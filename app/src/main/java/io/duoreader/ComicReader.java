package io.duoreader;

import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.JSONArray;

/** Two consecutive comic pages, one decode worker, and a byte-budgeted decoded-page cache. */
public final class ComicReader {
  private final ReaderApp app;
  private final ThreadPoolExecutor decoder = TaskExecutors.create("reader-comics", 1);
  private final LruCache<String, Bitmap> cache =
      new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        protected int sizeOf(String key, Bitmap bitmap) {
          return bitmap.getAllocationByteCount();
        }
      };
  private ComicArchive archive;
  private Library.Entry entry;
  private Future<?> decodeTask;
  private int generation,
      index,
      upperWidth = 1080,
      upperHeight = 1920,
      lowerWidth = 960,
      lowerHeight = 1280;
  private Bitmap upper, lower;
  private String upperError = "", lowerError = "";
  private ComicView upperView, lowerView;
  private MainActivity upperOwner;
  private LinearLayout overlay;
  private TextView status;
  private boolean controlsFocused;
  private final Runnable hideControls =
      () -> {
        controlsFocused = false;
        if (overlay != null) overlay.setVisibility(View.GONE);
      };

  ComicReader(ReaderApp app) {
    this.app = app;
  }

  boolean active() {
    return archive != null
        && entry != null
        && app.current != null
        && entry.uri.equals(app.current.uri);
  }

  public int index() {
    return index;
  }

  int progress() {
    return archive == null ? 0 : Math.round(100f * index / Math.max(1, archive.names.size() - 1));
  }

  Future<?> open(Library.Entry opened, int openGeneration) {
    return app.worker.submit(
        () -> {
          ComicArchive candidate = null;
          try {
            File file =
                EpubCache.resolve(
                    app,
                    opened.uri,
                    opened.modified,
                    opened.size,
                    2L * 1024 * 1024 * 1024,
                    "comic");
            candidate = ComicArchive.open(file, DocumentFormat.of(opened.name), app.getCacheDir());
            ComicArchive result = candidate;
            candidate = null;
            app.main.post(
                () -> {
                  if (openGeneration != app.openGeneration || app.closing) {
                    decoder.execute(() -> close(result));
                    return;
                  }
                  app.save();
                  clear();
                  app.editor.clear();
                  if (app.book != null) {
                    try {
                      app.book.close();
                    } catch (IOException ignored) {
                    }
                    app.book = null;
                  }
                  archive = result;
                  entry = opened;
                  index =
                      Math.max(
                          0,
                          Math.min(
                              result.names.size() - 1,
                              app.prefs.getInt(app.key(opened.uri) + "comicPage", 0)));
                  app.current = opened;
                  app.selected = opened;
                  app.mode = "comic";
                  app.busy = false;
                  app.restoring = false;
                  app.resumePending = false;
                  app.topPage = app.bottomPage = null;
                  app.marks = new JSONArray();
                  app.save();
                  app.changed();
                  request();
                });
          } catch (Exception error) {
            if (candidate != null) close(candidate);
            app.main.post(() -> app.openFailed(openGeneration, error));
          }
        });
  }

  private static void close(ComicArchive archive) {
    try {
      archive.close();
    } catch (IOException ignored) {
    }
  }

  void clear() {
    ++generation;
    TaskExecutors.cancel(decodeTask, decoder);
    app.main.removeCallbacks(hideControls);
    ComicArchive previous = archive;
    archive = null;
    entry = null;
    upper = lower = null;
    cache.evictAll();
    upperView = lowerView = null;
    upperOwner = null;
    overlay = null;
    status = null;
    if (previous != null) decoder.execute(() -> close(previous));
  }

  Bitmap page(boolean lower) {
    return lower ? this.lower : upper;
  }

  String message(boolean lower) {
    if (archive == null || lower && index + 1 >= archive.names.size()) return "End of comic";
    String error = lower ? lowerError : upperError;
    return error.isEmpty() ? "Loading page…" : error;
  }

  String label(boolean lower) {
    return archive == null || lower && index + 1 >= archive.names.size()
        ? ""
        : (index + (lower ? 2 : 1)) + " / " + archive.names.size();
  }

  void dimensions(boolean lower, int width, int height) {
    if (width < 1 || height < 1) return;
    if (lower) {
      if (lowerWidth == width && lowerHeight == height) return;
      lowerWidth = width;
      lowerHeight = height;
    } else {
      if (upperWidth == width && upperHeight == height) return;
      upperWidth = width;
      upperHeight = height;
    }
    request();
  }

  private String key(int page, int width, int height) {
    return page + ":" + width + ":" + height;
  }

  private void request() {
    if (archive == null || !app.mode.equals("comic")) return;
    int token = ++generation,
        page = index,
        uw = upperWidth,
        uh = upperHeight,
        lw = lowerWidth,
        lh = lowerHeight;
    ComicArchive document = archive;
    TaskExecutors.cancel(decodeTask, decoder);
    upper = cache.get(key(page, uw, uh));
    lower = cache.get(key(page + 1, lw, lh));
    upperError = lowerError = "";
    refresh();
    decodeTask =
        decoder.submit(
            () -> {
              for (int side = 0; side < 2; side++) {
                if (Thread.currentThread().isInterrupted()) return;
                int number = page + side, width = side == 0 ? uw : lw, height = side == 0 ? uh : lh;
                if (number >= document.names.size()) continue;
                String key = key(number, width, height);
                Bitmap cached = cache.get(key);
                Bitmap decoded = cached;
                String failure = "";
                try {
                  if (decoded == null) decoded = document.decode(number, width, height);
                } catch (IOException error) {
                  failure = error.getMessage();
                }
                Bitmap result = decoded;
                String error = failure;
                boolean lower = side == 1;
                app.main.post(
                    () -> {
                      if (token != generation || document != archive || app.closing) return;
                      if (result != null) cache.put(key, result);
                      if (lower) {
                        this.lower = result;
                        lowerError = error;
                      } else {
                        upper = result;
                        upperError = error;
                      }
                      refresh();
                    });
              }
            });
  }

  private void refresh() {
    if (upperView != null) upperView.invalidate();
    if (lowerView != null) lowerView.invalidate();
    if (status != null) status.setText(entry == null ? "" : entry.name + " · " + label(false));
  }

  View primary(MainActivity owner) {
    if (upperView == null || upperOwner != owner) {
      upperOwner = owner;
      upperView = new ComicView(owner, app, false);
      upperView.post(this::request);
    }
    return upperView;
  }

  void buildControls(ReaderUi ui) {
    controlsFocused = false;
    lowerView = new ComicView(ui.activity, app, true);
    ui.root.addView(lowerView, new FrameLayout.LayoutParams(-1, -1));
    overlay = ui.column();
    overlay.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
    overlay.setBackground(ui.shape(ui.translucent(ui.shell(), 235), ui.line(), 1));
    LinearLayout top = ui.row();
    ui.addWeighted(top, ui.button("Library", app::library));
    ui.addWeighted(top, ui.button("Pages", this::pages));
    ui.addWeighted(top, ui.button("Menu", this::menu));
    overlay.addView(top);
    status = ui.text("", 12, ui.ink());
    overlay.addView(status);
    LinearLayout navigation = ui.row();
    ui.addWeighted(navigation, ui.button("Previous", () -> turn(-1)));
    ui.addWeighted(navigation, ui.button("Next", () -> turn(1)));
    overlay.addView(navigation);
    ui.root.addView(overlay, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    refresh();
    scheduleHide();
  }

  private void scheduleHide() {
    app.main.removeCallbacks(hideControls);
    app.main.postDelayed(hideControls, 4000);
  }

  void toggleControls() {
    if (overlay == null) return;
    overlay.setVisibility(overlay.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    scheduleHide();
  }

  void turn(int direction) {
    if (archive == null) return;
    if (direction > 0 && index + 2 >= archive.names.size()) return;
    int next = Math.max(0, index + direction * 2);
    if (next == index) return;
    index = next;
    app.save();
    request();
    scheduleHide();
  }

  private void pages() {
    ReaderUi ui = app.controls;
    ui.keyboard(
        "Go to comic page",
        Integer.toString(index + 1),
        "Go",
        value -> {
          try {
            int page = Integer.parseInt(value.trim()) - 1;
            if (page < 0 || page >= archive.names.size()) throw new NumberFormatException();
            index = page;
            app.save();
            request();
          } catch (NumberFormatException error) {
            ui.message("Page number", "Enter a page from 1 to " + archive.names.size());
          }
        });
  }

  private void menu() {
    ReaderUi ui = app.controls;
    LinearLayout body = ui.column();
    ui.rotationControls(body);
    ui.option(
        body,
        "Page color",
        () -> {
          app.paper = (app.paper + 1) % ReaderApp.PAPER_COLORS.length;
          app.appearance();
          refresh();
        });
    ui.option(body, "Preferences", ui::preferences);
    ui.option(body, "Close app", app::close);
    ui.showDialog("Comic", body);
  }

  boolean handle(KeyEvent event) {
    int key = event.getKeyCode();
    boolean arrow = key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT;
    if (controlsFocused && overlay != null && overlay.getVisibility() == View.VISIBLE && arrow) {
      scheduleHide();
      return false;
    }
    boolean action =
        key == KeyEvent.KEYCODE_DPAD_LEFT
            || key == KeyEvent.KEYCODE_DPAD_RIGHT
            || key == KeyEvent.KEYCODE_BUTTON_L1
            || key == KeyEvent.KEYCODE_BUTTON_R1
            || key == KeyEvent.KEYCODE_BUTTON_A
            || key == KeyEvent.KEYCODE_BUTTON_B
            || key == KeyEvent.KEYCODE_BACK
            || key == KeyEvent.KEYCODE_BUTTON_START
            || key == KeyEvent.KEYCODE_BUTTON_X;
    if (!action) return false;
    if (event.getAction() == KeyEvent.ACTION_DOWN) {
      if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_BUTTON_B) {
        if (controlsFocused) hideControls.run();
        else app.library();
      } else if (key == KeyEvent.KEYCODE_BUTTON_START || key == KeyEvent.KEYCODE_BUTTON_X) menu();
      else if (key == KeyEvent.KEYCODE_BUTTON_A) {
        if (controlsFocused && overlay != null && overlay.getVisibility() == View.VISIBLE) {
          View focused = app.bottom.getCurrentFocus();
          if (focused != null) focused.performClick();
        } else {
          if (overlay != null) {
            overlay.setVisibility(View.VISIBLE);
            overlay.requestFocus(View.FOCUS_FORWARD);
            controlsFocused = true;
            scheduleHide();
          }
        }
      } else turn(key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1);
    }
    return true;
  }

  void resume() {
    request();
  }

  void suspend() {
    ++generation;
    TaskExecutors.cancel(decodeTask, decoder);
    app.main.removeCallbacks(hideControls);
  }

  void trim() {
    cache.evictAll();
  }

  static Bitmap cover(ReaderApp app, Library.Entry entry, int size) throws IOException {
    File file =
        EpubCache.resolve(
            app, entry.uri, entry.modified, entry.size, 2L * 1024 * 1024 * 1024, "comic");
    try (ComicArchive archive =
        ComicArchive.open(file, DocumentFormat.of(entry.name), app.getCacheDir())) {
      Bitmap bitmap = archive.decode(0, size, size * 2);
      float scale = Math.min(1, size / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
      if (scale == 1) return bitmap;
      Bitmap resized =
          Bitmap.createScaledBitmap(
              bitmap,
              Math.max(1, Math.round(bitmap.getWidth() * scale)),
              Math.max(1, Math.round(bitmap.getHeight() * scale)),
              true);
      if (resized != bitmap) bitmap.recycle();
      return resized;
    }
  }
}
