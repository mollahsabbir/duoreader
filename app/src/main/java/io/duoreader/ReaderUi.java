package io.duoreader;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;

/** All interaction lives here, on the lower display, using focusable native controls. */
public final class ReaderUi {
  public final Activity activity;
  public final ReaderApp app;
  public final FrameLayout root;
  public PaperView paper;
  public Dialog dialog;
  public LinearLayout controlsOverlay;
  public LinearLayout appearanceOverlay;
  public boolean controlsVisible;
  public boolean controlsFocus;
  private Button fontQuick, sizeQuick, spacingQuick, marginQuick, matchQuick;
  final ReaderFeedback feedback = new ReaderFeedback(this);
  FrameLayout dialogSurface;
  private PortraitHost dialogHost;
  private java.util.concurrent.Future<?> lookupTask;
  private Epub displayedBook;
  private Library.Entry displayedDocument;

  private void cancelLookup() {
    if (lookupTask != null) {
      TaskExecutors.cancel(lookupTask, app.lookups);
      lookupTask = null;
    }
  }

  private final ArrayList<Button> pageColors = new ArrayList<>();
  private final ArrayList<Button> overlayButtons = new ArrayList<>();
  private final ArrayList<Button> alignmentButtons = new ArrayList<>();
  private final Runnable autoHide = this::hideControls;
  public ListView list;
  BookListAdapter adapter;
  TextView libraryStatus, pathLabel, readingStatus;
  Button resumeButton;
  Button collectionButton;
  private ArrayList<Library.Entry> renderedEntries;
  private boolean restoringList;
  private int iconSet;
  String builtMode = "";
  int builtTheme = -1;
  final float density;

  public ReaderUi(Activity a, ReaderApp state) {
    activity = a;
    app = state;
    density = a.getResources().getDisplayMetrics().density;
    root = new FrameLayout(a);
  }

  int dp(float n) {
    return Math.round(n * density);
  }

  public int accent() {
    return app.libraryTheme().accent;
  }

  int shell() {
    return app.libraryTheme().shell;
  }

  int surface() {
    return app.libraryTheme().surface;
  }

  int ink() {
    return app.libraryTheme().ink;
  }

  int muted() {
    return app.libraryTheme().muted;
  }

  int line() {
    return app.libraryTheme().line;
  }

  GradientDrawable shape(int fill, int stroke, int width) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(7));
    if (width > 0) d.setStroke(dp(width), stroke);
    return d;
  }

  int translucent(int color, int alpha) {
    return (color & 0x00ffffff) | (alpha << 24);
  }

  StateListDrawable controlBackground() {
    return controlBackground(surface(), app.libraryTheme().soft, line());
  }

  StateListDrawable controlBackground(int fill, int active, int border) {
    StateListDrawable d = new StateListDrawable();
    d.addState(new int[] {android.R.attr.state_pressed}, shape(active, accent(), 2));
    d.addState(new int[] {android.R.attr.state_focused}, shape(active, accent(), 2));
    d.addState(new int[] {android.R.attr.state_activated}, shape(active, accent(), 2));
    d.addState(new int[] {android.R.attr.state_selected}, shape(active, accent(), 2));
    d.addState(new int[] {}, shape(fill, border, 1));
    return d;
  }

  TextView text(String label, int size, int color) {
    TextView t = new TextView(activity);
    t.setText(label);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    return t;
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(activity);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(activity);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  Button button(String title, Runnable action) {
    Button b = new Button(activity);
    b.setText(title);
    b.setTextColor(ink());
    b.setTextSize(13);
    b.setAllCaps(false);
    b.setTypeface(Typeface.create("sans-serif-medium", 0));
    b.setPadding(dp(10), dp(5), dp(10), dp(5));
    b.setMinHeight(dp(42));
    b.setMinimumHeight(dp(42));
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setBackground(controlBackground());
    b.setOnClickListener(v -> action.run());
    return b;
  }

  void styleAppClose(Button button) {
    int red = Color.luminance(surface()) < .2 ? 0xffef9a9a : 0xffa13b45;
    button.setTextColor(red);
    StateListDrawable background = new StateListDrawable();
    int normal = blend(surface(), red, .07f), active = blend(surface(), red, .18f);
    background.addState(new int[] {android.R.attr.state_pressed}, shape(active, red, 2));
    background.addState(new int[] {android.R.attr.state_focused}, shape(active, red, 2));
    background.addState(new int[] {}, shape(normal, translucent(red, 155), 1));
    button.setBackground(background);
  }

  private static int blend(int background, int foreground, float amount) {
    return Color.rgb(
        Math.round(Color.red(background) * (1 - amount) + Color.red(foreground) * amount),
        Math.round(Color.green(background) * (1 - amount) + Color.green(foreground) * amount),
        Math.round(Color.blue(background) * (1 - amount) + Color.blue(foreground) * amount));
  }

  void addWeighted(LinearLayout row, View v) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1);
    p.setMargins(dp(3), dp(3), dp(3), dp(3));
    row.addView(v, p);
  }

  void separator(LinearLayout l) {
    View v = new View(activity);
    v.setBackgroundColor(line());
    l.addView(v, new LinearLayout.LayoutParams(-1, dp(1)));
  }

  public void rebuild() {
    builtMode = "";
    refresh();
  }

  public void refresh() {
    boolean documentChanged = !app.mode.equals("library") && displayedDocument != app.current;
    if (!builtMode.equals(app.mode) || displayedBook != app.book || documentChanged) {
      feedback.dismiss();
      cancelLookup();
      if (dialog != null) dialog.dismiss();
    }
    displayedBook = app.book;
    displayedDocument = app.current;
    if (!builtMode.equals(app.mode) || builtTheme != app.theme || documentChanged) {
      feedback.dismiss();
      app.main.removeCallbacks(autoHide);
      root.removeAllViews();
      paper = null;
      controlsOverlay = null;
      appearanceOverlay = null;
      controlsVisible = false;
      controlsFocus = false;
      pageColors.clear();
      overlayButtons.clear();
      list = null;
      adapter = null;
      renderedEntries = null;
      restoringList = false;
      builtMode = app.mode;
      builtTheme = app.theme;
      root.setBackgroundColor(shell());
      if (app.mode.equals("reading") && app.book != null) buildReader();
      else if (app.mode.equals("editing")) app.editor.buildControls(this);
      else buildLibrary();
    }
    if (paper != null) {
      paper.appearance();
      readingStatus.setText(app.progress());
      styleOverlay();
    }
    if (adapter != null) {
      boolean changed = renderedEntries != app.entries;
      renderedEntries = app.entries;
      if (changed) {
        restoringList = true;
        list.clearChoices();
        adapter.setEntries(app.entries);
        adapter.notifyDataSetChanged();
      } else {
        adapter.refreshVisibleRows(list);
      }
      libraryStatus.setText(app.status);
      pathLabel.setText(app.folderLabel());
      resumeButton.setVisibility(
          app.current != null || !app.prefs.getString("lastUri", "").isEmpty()
              ? View.VISIBLE
              : View.GONE);
      updateCollectionButton();
      if (changed) {
        final ArrayList<Library.Entry> items = app.entries;
        final int index = app.selectedIndex();
        if (index >= 0) list.setSelection(index);
        list.post(
            () -> {
              if (items == app.entries && list != null) {
                if (index >= 0) {
                  list.setSelection(index);
                  if (dialog == null) list.requestFocusFromTouch();
                }
                restoringList = false;
              }
            });
      }
    }
  }

  void buildLibrary() {
    LinearLayout body = column();
    body.setPadding(dp(15), dp(14), dp(15), dp(8));
    root.addView(body, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout brand = row();
    TextView title = text("Duo Reader", 23, ink());
    title.setTypeface(Typeface.create("serif", 0));
    brand.addView(title, new LinearLayout.LayoutParams(0, dp(45), 1));
    Button options = button("Preferences", this::preferences);
    brand.addView(options, new LinearLayout.LayoutParams(dp(98), dp(40)));
    Button close = button("Close", app::close);
    styleAppClose(close);
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(60), dp(40));
    cp.setMargins(dp(6), 0, 0, 0);
    brand.addView(close, cp);
    body.addView(brand);
    body.addView(hardwareStrip(app.libraryTheme()));
    LinearLayout path = row();
    Button parent = button("‹", () -> app.parent());
    parent.setContentDescription("Parent folder");
    path.addView(parent, new LinearLayout.LayoutParams(dp(43), dp(40)));
    pathLabel = text(app.folderLabel(), 14, ink());
    pathLabel.setSingleLine(true);
    pathLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    pathLabel.setPadding(dp(12), 0, dp(6), 0);
    path.addView(pathLabel, new LinearLayout.LayoutParams(0, dp(40), 1));
    pathLabel.setGravity(Gravity.CENTER_VERTICAL);
    Button refresh = button("↻", () -> app.loadFolder(app.folder));
    refresh.setContentDescription("Refresh folder");
    path.addView(refresh, new LinearLayout.LayoutParams(dp(43), dp(40)));
    body.addView(path);
    resumeButton =
        button(
            "Continue last book  ›",
            () -> {
              if (app.current != null) {
                app.resume();
                return;
              }
              String uri = app.prefs.getString("lastUri", "");
              if (!uri.isEmpty()) {
                Library.Entry e =
                    new Library.Entry(uri, app.prefs.getString("lastName", "Book"), false);
                e.modified = app.prefs.getLong("lastModified", 0);
                e.size = app.prefs.getLong("lastSize", 0);
                app.open(e);
              }
            });
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(43));
    rp.setMargins(0, dp(10), 0, dp(3));
    body.addView(resumeButton, rp);
    LinearLayout head = row();
    TextView heading = text("BOOKS & COLLECTIONS", 10, muted());
    heading.setLetterSpacing(.09f);
    heading.setPadding(dp(3), dp(10), 0, dp(8));
    head.addView(heading, new LinearLayout.LayoutParams(0, dp(44), 1));
    collectionButton = button("Icon", this::collectionIcon);
    collectionButton.setContentDescription("Change collection icon");
    head.addView(collectionButton, new LinearLayout.LayoutParams(dp(65), dp(36)));
    body.addView(head);
    list = new ListView(activity);
    list.setDivider(new ColorDrawable(Color.TRANSPARENT));
    list.setDividerHeight(dp(7));
    list.setSelector(controlBackground());
    list.setPadding(dp(2), dp(2), dp(2), dp(2));
    list.setClipToPadding(false);
    list.setChoiceMode(ListView.CHOICE_MODE_NONE);
    list.setCacheColorHint(Color.TRANSPARENT);
    adapter = new BookListAdapter(this);
    list.setAdapter(adapter);
    body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView empty =
        text(
            "No documents here yet.\n"
                + "Choose your books folder in Preferences,\n"
                + "or open a collection above.",
            14,
            muted());
    empty.setGravity(Gravity.CENTER);
    FrameLayout emptyHolder = new FrameLayout(activity); // ListView uses its own empty view below.
    empty.setPadding(dp(20), dp(25), dp(20), dp(25));
    body.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
    list.setEmptyView(empty);
    list.setOnItemClickListener(
        (p, v, index, id) -> {
          if (!app.mode.equals("library")
              || app.loadingFolder
              || app.busy
              || app.closing
              || adapter == null) return;
          Library.Entry entry = adapter.entry(index);
          Object bound = v == null ? null : v.getTag();
          if (entry == null
              || id != entry.stableId
              || !(bound instanceof Library.Entry)
              || !entry.uri.equals(((Library.Entry) bound).uri)) return;
          for (Library.Entry current : app.entries)
            if (current.uri.equals(entry.uri)) {
              preview(current);
              app.enter(current);
              return;
            }
        });
    list.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> p, View v, int index, long id) {
            if (!restoringList && adapter != null && !app.loadingFolder) {
              Library.Entry entry = adapter.entry(index);
              if (entry != null && id == entry.stableId) preview(entry);
            }
          }

          public void onNothingSelected(AdapterView<?> p) {}
        });
    libraryStatus = text(app.status, 11, muted());
    libraryStatus.setPadding(dp(3), dp(9), dp(3), dp(5));
    body.addView(libraryStatus);
    TextView help = text("↑↓ Browse    → Open    ← Parent", 11, ink());
    help.setGravity(Gravity.CENTER);
    help.setPadding(0, dp(5), 0, dp(3));
    body.addView(help);
    LinearLayout keys = row();
    for (String key : new String[] {"A", "B", "X", "Y"}) {
      LinearLayout hint = row();
      hint.setGravity(Gravity.CENTER);
      TextView badge = text(key, 10, 0xff17212b);
      badge.setGravity(Gravity.CENTER);
      badge.setBackground(shape(app.libraryTheme().keyColor(key), app.libraryTheme().deep, 1));
      hint.addView(badge, new LinearLayout.LayoutParams(dp(20), dp(20)));
      TextView label =
          text(
              " "
                  + (key.equals("A")
                      ? "Open"
                      : key.equals("B") ? "Back" : key.equals("X") ? "Menu" : "Icon"),
              11,
              ink());
      hint.addView(label);
      keys.addView(hint, new LinearLayout.LayoutParams(0, dp(28), 1));
    }
    body.addView(keys);
    if (!app.entries.isEmpty()) {
      list.setSelection(app.selectedIndex());
      list.requestFocusFromTouch();
    }
  }

  LinearLayout hardwareStrip(LibraryTheme palette) {
    LinearLayout strip = row();
    strip.setPadding(dp(2), 0, dp(2), dp(9));
    TextView label = text(palette.name + "  ·  LOCAL EPUB LIBRARY", 10, palette.muted);
    label.setSingleLine(true);
    label.setEllipsize(TextUtils.TruncateAt.END);
    strip.addView(label, new LinearLayout.LayoutParams(0, dp(27), 1));
    for (int color : new int[] {palette.a, palette.b, palette.x, palette.y, palette.stick}) {
      View dot = new View(activity);
      GradientDrawable bg = new GradientDrawable();
      bg.setShape(GradientDrawable.OVAL);
      bg.setColor(color);
      bg.setStroke(dp(1), palette.line);
      dot.setBackground(bg);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(9), dp(9));
      p.setMargins(dp(5), 0, 0, 0);
      strip.addView(dot, p);
    }
    return strip;
  }

  void updateCollectionButton() {
    if (collectionButton != null) {
      boolean enabled = app.selected != null && app.selected.folder;
      collectionButton.setEnabled(enabled);
      collectionButton.setAlpha(enabled ? 1 : .35f);
    }
  }

  void preview(Library.Entry entry) {
    boolean changed = app.selected == null || !app.selected.uri.equals(entry.uri);
    app.selectEntry(entry);
    updateCollectionButton();
    if (changed && adapter != null) adapter.refreshVisibleRows(list);
    if (app.top != null && app.top.paper != null) app.top.paper.invalidate();
  }

  void buildReader() {
    paper = new PaperView(activity, true);
    root.addView(paper, new FrameLayout.LayoutParams(-1, -1));
    controlsOverlay =
        new LinearLayout(activity) {
          @Override
          public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) scheduleHide();
            return super.dispatchTouchEvent(event);
          }
        };
    controlsOverlay.setOrientation(LinearLayout.VERTICAL);
    controlsOverlay.setPadding(0, dp(17), 0, dp(5));
    LinearLayout turns = row();
    turns.setPadding(dp(8), dp(2), dp(8), dp(2));
    Button prev = readerButton("‹ Previous", () -> app.turn(-1));
    turns.addView(prev, new LinearLayout.LayoutParams(dp(102), dp(42)));
    readingStatus = text(app.progress(), 10, muted());
    readingStatus.setGravity(Gravity.CENTER);
    readingStatus.setMaxLines(2);
    turns.addView(readingStatus, new LinearLayout.LayoutParams(0, dp(42), 1));
    Button next = readerButton("Next ›", () -> app.turn(1));
    turns.addView(next, new LinearLayout.LayoutParams(dp(91), dp(42)));
    controlsOverlay.addView(turns);
    LinearLayout actions = row();
    actions.setPadding(dp(5), 0, dp(5), dp(4));
    addWeighted(actions, readerButton("Contents", this::contents));
    addWeighted(actions, readerButton("Search", this::search));
    addWeighted(actions, readerButton("Menu", this::readerMenu));
    controlsOverlay.addView(actions);
    root.addView(controlsOverlay, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    buildQuickAppearance();
    View bookmark = new View(activity);
    bookmark.setContentDescription("Bookmark this page");
    bookmark.setOnClickListener(v -> app.bookmarkSmallPage());
    root.addView(
        bookmark, new FrameLayout.LayoutParams(dp(43), dp(42), Gravity.TOP | Gravity.RIGHT));
    styleOverlay();
    paper.requestFocus();
    showControls();
  }

  void buildQuickAppearance() {
    appearanceOverlay = column();
    appearanceOverlay.setPadding(dp(5), dp(5), dp(5), dp(14));
    LinearLayout navigation = row();
    navigation.setPadding(0, 0, dp(43), 0);
    addWeighted(navigation, readerButton("Library", app::library));
    alignmentButtons.clear();
    String[] alignments = {"Left", "Justify", "Right"};
    for (int i = 0; i < alignments.length; i++) {
      final int choice = i;
      Button align =
          readerButton(
              "",
              () -> {
                app.alignment = choice;
                app.appearance();
                scheduleHide();
              });
      align.setContentDescription(alignments[i] + " alignment");
      alignmentButtons.add(align);
      addWeighted(navigation, align);
    }
    appearanceOverlay.addView(navigation);
    LinearLayout fonts = row();
    fonts.setPadding(0, 0, dp(43), 0);
    fontQuick = readerButton("", this::fontPicker);
    addWeighted(fonts, fontQuick);
    addWeighted(
        fonts,
        readerButton(
            "A−",
            () -> {
              fontStep(-1);
              scheduleHide();
            }));
    sizeQuick = readerButton("", () -> {});
    sizeQuick.setFocusable(false);
    addWeighted(fonts, sizeQuick);
    addWeighted(
        fonts,
        readerButton(
            "A+",
            () -> {
              fontStep(1);
              scheduleHide();
            }));
    appearanceOverlay.addView(fonts);
    LinearLayout colors = row();
    for (int i = 0; i < ReaderApp.PAPER_NAMES.length; i++) {
      final int choice = i;
      Button b =
          button(
              ReaderApp.PAPER_NAMES[i],
              () -> {
                app.paper = choice;
                app.appearance();
                scheduleHide();
              });
      b.setContentDescription(ReaderApp.PAPER_NAMES[i] + " page colors");
      pageColors.add(b);
      addWeighted(colors, b);
    }
    appearanceOverlay.addView(colors);
    LinearLayout layout = row();
    spacingQuick =
        readerButton(
            "",
            () -> {
              app.spacing =
                  app.spacing >= 1.79f ? 1.2f : Math.round((app.spacing + .2f) * 10) / 10f;
              app.appearance();
              scheduleHide();
            });
    marginQuick =
        readerButton(
            "",
            () -> {
              int[] widths = {12, 18, 24, 32, 40};
              int next = 0;
              for (int i = 0; i < widths.length; i++)
                if (widths[i] == app.margin) next = (i + 1) % widths.length;
              app.margin = widths[next];
              app.appearance();
              scheduleHide();
            });
    matchQuick =
        readerButton(
            "",
            () -> {
              app.matchScreens = !app.matchScreens;
              app.appearance();
              scheduleHide();
            });
    addWeighted(layout, spacingQuick);
    addWeighted(layout, marginQuick);
    addWeighted(layout, matchQuick);
    appearanceOverlay.addView(layout);
    root.addView(appearanceOverlay, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
  }

  Button readerButton(String label, Runnable action) {
    Button b = button(label, action);
    overlayButtons.add(b);
    return b;
  }

  void styleOverlay() {
    if (controlsOverlay == null) return;
    int bg = app.pageColor();
    GradientDrawable fade =
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {translucent(bg, 0), translucent(bg, 75), translucent(bg, 135)});
    controlsOverlay.setBackground(fade);
    readingStatus.setTextColor(app.paper == 2 ? 0xff9a9e94 : 0xff74796f);
    for (Button b : overlayButtons) {
      b.setTextColor(app.ink());
      b.setBackground(
          controlBackground(
              translucent(bg, 155), translucent(bg, 210), translucent(accent(), 100)));
    }
    if (appearanceOverlay != null) {
      appearanceOverlay.setBackground(
          new GradientDrawable(
              GradientDrawable.Orientation.TOP_BOTTOM,
              new int[] {translucent(bg, 150), translucent(bg, 90), translucent(bg, 0)}));
      fontQuick.setText(new String[] {"Serif", "Sans", "Mono"}[app.font]);
      fontQuick.setTypeface(app.typeface());
      sizeQuick.setText(Integer.toString(app.fontSize));
      spacingQuick.setText("Spacing " + String.format(Locale.ROOT, "%.1f", app.spacing));
      marginQuick.setText("Margins " + app.margin);
      matchQuick.setText("Match " + (app.matchScreens ? "On" : "Off"));
      for (int i = 0; i < alignmentButtons.size(); i++) {
        Button align = alignmentButtons.get(i);
        align.setCompoundDrawables(
            null, ReaderIcons.icon("align-" + i, app.ink(), dp(20)), null, null);
        align.setSelected(app.alignment == i);
      }
      for (int i = 0; i < pageColors.size(); i++) {
        Button b = pageColors.get(i);
        b.setTextColor(ReaderApp.PAPER_INKS[i]);
        b.setBackground(
            shape(
                ReaderApp.PAPER_COLORS[i],
                i == app.paper ? accent() : line(),
                i == app.paper ? 2 : 1));
      }
    }
  }

  void scheduleHide() {
    app.main.removeCallbacks(autoHide);
    if (controlsVisible) app.main.postDelayed(autoHide, 3200);
  }

  public void showControls() {
    if (controlsOverlay == null || dialog != null || app.turns.active) return;
    controlsVisible = true;
    for (View v : new View[] {controlsOverlay, appearanceOverlay})
      if (v != null) {
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        v.setAlpha(0);
        v.animate().alpha(1).setDuration(150).start();
      }
    scheduleHide();
  }

  public void hideControls() {
    app.main.removeCallbacks(autoHide);
    controlsVisible = false;
    controlsFocus = false;
    for (View v : new View[] {controlsOverlay, appearanceOverlay})
      if (v != null) {
        v.animate().cancel();
        v.setVisibility(View.GONE);
      }
  }

  public void toggleControls() {
    if (controlsVisible) hideControls();
    else showControls();
  }

  public void toast(String s) {
    boolean inDialog = dialog != null && dialog.isShowing() && dialogSurface != null;
    feedback.show(s, inDialog ? dialogSurface : root, inDialog);
  }

  public void hideFeedback() {
    feedback.dismiss();
  }

  public void message(String title, String msg) {
    LinearLayout body = column();
    TextView t = text(msg, 15, ink());
    t.setPadding(dp(5), dp(12), dp(5), dp(15));
    body.addView(t);
    showDialog(title, body);
  }

  Dialog showDialog(String title, View content) {
    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(false);
    scroll.addView(content);
    return showPanel(title, scroll, null);
  }

  Dialog showPanel(String title, View content, View footer) {
    feedback.dismiss();
    hideControls();
    app.turns.cancel();
    if (paper != null) paper.endSelection();
    if (dialog != null) dialog.dismiss();
    Dialog d =
        new Dialog(activity) {
          @Override
          public boolean dispatchKeyEvent(KeyEvent event) {
            return super.dispatchKeyEvent(ReaderInput.orient(event, app.rotation));
          }
        };
    d.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout layout = column();
    layout.setPadding(dp(16), dp(13), dp(16), dp(12));
    layout.setBackground(shape(shell(), line(), 1));
    TextView heading = text(title, 23, ink());
    heading.setMaxLines(1);
    heading.setEllipsize(TextUtils.TruncateAt.END);
    heading.setTypeface(Typeface.create("serif", 0));
    heading.setPadding(dp(3), dp(3), dp(3), dp(13));
    layout.addView(heading);
    layout.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    if (footer != null) layout.addView(footer, new LinearLayout.LayoutParams(-1, -2));
    Button close = button("Back", d::dismiss);
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(43));
    bp.setMargins(0, dp(10), 0, 0);
    layout.addView(close, bp);
    FrameLayout surface = new FrameLayout(activity);
    surface.addView(layout, new FrameLayout.LayoutParams(-1, -1));
    dialogHost = new PortraitHost(activity, surface, dp(11), dp(17));
    d.setContentView(dialogHost);
    dialog = d;
    dialogSurface = surface;
    d.setOnDismissListener(
        x -> {
          if (dialog == d) {
            feedback.dismiss();
            dialog = null;
            dialogSurface = null;
            cancelLookup();
          }
        });
    d.setOnKeyListener(
        (ignored, key, event) -> {
          if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) d.dismiss();
            return true;
          }
          if (key == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
              View f = d.getCurrentFocus();
              if (f != null) f.performClick();
            }
            return true;
          }
          return false;
        });
    d.show();
    Window w = d.getWindow();
    if (w != null) {
      w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      if (app.mode.equals("reading")) w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      w.setLayout(-1, -1);
      w.getDecorView()
          .setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    content.post(
        () -> {
          View first = content.findFocus();
          if (first == null) content.requestFocus(View.FOCUS_FORWARD);
        });
    return d;
  }

  void option(LinearLayout body, String label, Runnable action) {
    Button b = button(label, action);
    String icon = ReaderIcons.action(label);
    if (icon != null) actionIcon(b, icon);
    if ("Close app".equals(label)) styleAppClose(b);
    b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    b.setMaxLines(3);
    b.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(dp(2), dp(3), dp(2), dp(3));
    body.addView(b, p);
  }

  void actionIcon(Button button, String kind) {
    button.setCompoundDrawables(ReaderIcons.icon(kind, accent(), dp(19)), null, null, null);
    button.setCompoundDrawablePadding(dp(9));
  }

  LinearLayout actionGrid(LinearLayout actions) {
    LinearLayout grid = column();
    while (actions.getChildCount() > 0) {
      LinearLayout row = row();
      for (int i = 0; i < 2 && actions.getChildCount() > 0; i++) {
        View action = actions.getChildAt(0);
        actions.removeView(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(action, params);
      }
      grid.addView(row);
    }
    return grid;
  }

  public void updateDialogOrientation() {
    if (dialogHost != null && dialog != null && dialog.isShowing()) dialogHost.updateGrip();
  }

  void rotationControls(LinearLayout body) {
    LinearLayout choices = row();
    for (int angle : new int[] {0, 90, 180, 270}) {
      Button choice =
          button(
              angle + "°",
              () -> {
                app.controller.cancel();
                app.rotation = angle;
                app.appearance();
                if (dialog != null) dialog.dismiss();
              });
      choice.setCompoundDrawablesWithIntrinsicBounds(
          ReaderIcons.icon("rotate-" + angle, accent(), dp(22)), null, null, null);
      choice.setContentDescription("Rotate content " + angle + " degrees");
      choice.setSelected(app.rotation == angle);
      addWeighted(choices, choice);
    }
    body.addView(choices);
  }

  void menu(String title, String[] choices, java.util.function.IntConsumer action) {
    LinearLayout body = column();
    for (int i = 0; i < choices.length; i++) {
      final int index = i;
      option(
          body,
          choices[i],
          () -> {
            if (dialog != null) dialog.dismiss();
            action.accept(index);
          });
    }
    showDialog(title, body);
  }

  public void collectionIcon() {
    if (app.selected == null || !app.selected.folder || app.loadingFolder) {
      toast("Select a collection to change its icon");
      return;
    }
    iconSet =
        Math.max(
            0,
            Math.min(
                3,
                app.collectionPrefs.getInt(
                    "iconSet", CollectionIcons.index(app.collectionIcon(app.selected.uri)) / 8)));
    iconPicker(app.selected.uri, app.selected.title);
  }

  public void iconPicker(String uri, String title) {
    feedback.dismiss();
    if (dialog != null) dialog.dismiss();
    LinearLayout body = column();
    body.setPadding(dp(15), dp(14), dp(15), dp(12));
    body.setBackgroundColor(shell());
    LinearLayout header = row();
    TextView heading = text("Collection icon", 23, ink());
    header.addView(heading, new LinearLayout.LayoutParams(0, dp(42), 1));
    Button close =
        button(
            "Close",
            () -> {
              if (dialog != null) dialog.dismiss();
            });
    header.addView(close, new LinearLayout.LayoutParams(dp(65), dp(40)));
    body.addView(header);
    TextView name = text(title, 13, muted());
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.END);
    name.setPadding(dp(2), dp(5), dp(2), dp(12));
    body.addView(name);
    LinearLayout tabs = row();
    for (int i = 0; i < CollectionIcons.SETS.length; i++) {
      final int set = i;
      Button tab =
          button(
              CollectionIcons.SETS[i],
              () -> {
                iconSet = set;
                iconPicker(uri, title);
              });
      tab.setSelected(i == iconSet);
      addWeighted(tabs, tab);
    }
    body.addView(tabs);
    GridView grid = new GridView(activity);
    grid.setNumColumns(4);
    grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
    grid.setVerticalSpacing(dp(8));
    grid.setHorizontalSpacing(dp(7));
    grid.setPadding(dp(2), dp(12), dp(2), dp(6));
    grid.setClipToPadding(false);
    grid.setSelector(shape(Color.TRANSPARENT, accent(), 2));
    grid.setDrawSelectorOnTop(true);
    final int start = iconSet * 8;
    final String chosen = app.collectionIcon(uri);
    int cellHeight = Math.max(dp(100), Math.min(dp(190), (root.getHeight() - dp(208)) / 2));
    grid.setAdapter(
        new BaseAdapter() {
          public int getCount() {
            return 8;
          }

          public Object getItem(int position) {
            return CollectionIcons.IDS[start + position];
          }

          public long getItemId(int position) {
            return start + position;
          }

          public View getView(int position, View convert, ViewGroup parent) {
            int index = start + position;
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(6), dp(9), dp(6), dp(8));
            cell.setBackground(
                shape(
                    chosen.equals(CollectionIcons.IDS[index]) ? app.libraryTheme().soft : surface(),
                    chosen.equals(CollectionIcons.IDS[index]) ? accent() : line(),
                    1));
            ImageView icon = new ImageView(activity);
            icon.setImageDrawable(
                CollectionIcons.icon(CollectionIcons.IDS[index], app.libraryTheme(), dp(68)));
            cell.addView(icon, new LinearLayout.LayoutParams(dp(68), dp(68)));
            TextView label = text(CollectionIcons.NAMES[index], 12, ink());
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(10), 0, 0);
            cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
            cell.setContentDescription(
                CollectionIcons.NAMES[index]
                    + (chosen.equals(CollectionIcons.IDS[index]) ? ", current icon" : ""));
            cell.setLayoutParams(new AbsListView.LayoutParams(-1, cellHeight));
            return cell;
          }
        });
    java.util.function.IntConsumer apply =
        position -> {
          if (position < 0 || position >= 8) return;
          String icon = CollectionIcons.IDS[start + position];
          if (app.setCollectionIcon(uri, icon)) {
            app.collectionPrefs.edit().putInt("iconSet", iconSet).apply();
            if (dialog != null) dialog.dismiss();
            toast("Collection icon updated");
          } else toast("Could not save collection icon");
        };
    grid.setOnItemClickListener((parent, view, position, id) -> apply.accept(position));
    body.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView help =
        text(
            "D-pad + A Apply   ·   B Cancel   ·   L1/R1 Icon sets\nColors follow your "
                + app.libraryTheme().name
                + " library theme.",
            11,
            muted());
    help.setGravity(Gravity.CENTER);
    help.setPadding(0, dp(8), 0, 0);
    body.addView(help);
    Dialog picker =
        new Dialog(activity) {
          @Override
          public boolean dispatchKeyEvent(KeyEvent event) {
            return super.dispatchKeyEvent(ReaderInput.orient(event, app.rotation));
          }
        };
    picker.requestWindowFeature(Window.FEATURE_NO_TITLE);
    FrameLayout surface = new FrameLayout(activity);
    surface.addView(body, new FrameLayout.LayoutParams(-1, -1));
    dialogHost = new PortraitHost(activity, surface);
    picker.setContentView(dialogHost);
    dialog = picker;
    dialogSurface = surface;
    picker.setOnDismissListener(
        d -> {
          if (dialog == picker) {
            feedback.dismiss();
            dialog = null;
            dialogSurface = null;
          }
        });
    picker.setOnKeyListener(
        (d, key, event) -> {
          if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) picker.dismiss();
            return true;
          }
          if (key == KeyEvent.KEYCODE_BUTTON_L1 || key == KeyEvent.KEYCODE_BUTTON_R1) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
              iconSet = (iconSet + (key == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : 3)) % 4;
              iconPicker(uri, title);
            }
            return true;
          }
          if (key == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
              View focus = picker.getCurrentFocus();
              if (focus == grid) apply.accept(grid.getSelectedItemPosition());
              else if (focus != null) focus.performClick();
            }
            return true;
          }
          return false;
        });
    picker.show();
    Window window = picker.getWindow();
    window.setBackgroundDrawable(new ColorDrawable(shell()));
    window.setLayout(-1, -1);
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    window
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    grid.post(
        () -> {
          if (dialog == picker) {
            grid.requestFocusFromTouch();
            grid.setSelection(
                chosen.equals(CollectionIcons.IDS[CollectionIcons.index(chosen)])
                        && CollectionIcons.index(chosen) / 8 == iconSet
                    ? CollectionIcons.index(chosen) % 8
                    : 0);
          }
        });
  }

  public void readerMenu() {
    if (!app.mode.equals("reading")) {
      menu(
          "Library",
          new String[] {
            "Choose default books folder",
            "Preferences & appearance",
            "Change collection icon",
            "Refresh library",
            "Continue reading",
            "Button guide",
            "Close app"
          },
          i -> {
            switch (i) {
              case 0:
                chooseFolder();
                break;
              case 1:
                preferences();
                break;
              case 2:
                collectionIcon();
                break;
              case 3:
                app.loadFolder(app.folder);
                break;
              case 4:
                if (resumeButton != null) resumeButton.performClick();
                break;
              case 5:
                guide();
                break;
              case 6:
                app.close();
            }
          });
      return;
    }
    LinearLayout readingMenu = column();
    rotationControls(readingMenu);
    option(readingMenu, "Advanced display settings", this::advancedDisplay);
    String[] readingChoices = {
      "Bookmarks & notes  ·  " + app.marks.length(),
      "Select passage with buttons",
      "English dictionary",
      "Keybindings",
      "Button guide",
      "Close app"
    };
    java.util.function.IntConsumer readingAction =
        i -> {
          switch (i) {
            case 0:
              annotations();
              break;
            case 1:
              passages();
              break;
            case 2:
              dictionarySearch();
              break;
            case 3:
              keybindings();
              break;
            case 4:
              guide();
              break;
            case 5:
              app.close();
          }
        };
    for (int i = 0; i < readingChoices.length; i++) {
      final int index = i;
      option(
          readingMenu,
          readingChoices[i],
          () -> {
            if (dialog != null) dialog.dismiss();
            readingAction.accept(index);
          });
    }
    showDialog("Reading desk", readingMenu);
  }

  public void keybindings() {
    keybindings(-1);
  }

  void keybindings(int selected) {
    LinearLayout body = column();
    TextView hint =
        text(
            "Reading shortcuts · directions follow your portrait grip.\n"
                + "Menus keep D-pad + A to select and B to go back. Start always opens the reading"
                + " menu; Android Back always goes back.",
            13,
            muted());
    hint.setPadding(dp(3), 0, dp(3), dp(12));
    body.addView(hint);
    Button focus = null;
    for (int i = 0; i < ReadingBindings.KEYS.length; i++) {
      final int index = i, key = ReadingBindings.KEYS[i];
      Button b =
          button(
              ReadingBindings.LABELS[i] + "  ·  " + app.bindings.get(key).label,
              () -> bindingPicker(index));
      b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      b.setContentDescription(
          ReadingBindings.LABELS[i] + ", " + app.bindings.get(key).label + ", change shortcut");
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      p.setMargins(dp(2), dp(3), dp(2), dp(3));
      body.addView(b, p);
      if (i == selected) focus = b;
    }
    option(
        body,
        "Restore default keybindings",
        () -> {
          if (app.bindings.reset()) {
            toast("Default reading shortcuts restored");
            keybindings();
          } else toast("Could not save keybindings");
        });
    showDialog("Keybindings", body);
    if (focus != null) {
      final Button target = focus;
      target.post(() -> target.requestFocusFromTouch());
    }
  }

  void bindingPicker(int index) {
    LinearLayout body = column();
    int key = ReadingBindings.KEYS[index];
    Button focus = null;
    for (ReadingBindings.Action action : ReadingBindings.Action.values()) {
      boolean selected = app.bindings.get(key) == action;
      Button b =
          button(
              (selected ? "✓  " : "") + action.label,
              () -> {
                if (app.bindings.set(key, action)) {
                  toast(ReadingBindings.LABELS[index] + " · " + action.label);
                  keybindings(index);
                } else toast("Could not save keybinding");
              });
      b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      b.setSelected(selected);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      p.setMargins(dp(2), dp(3), dp(2), dp(3));
      body.addView(b, p);
      if (selected) focus = b;
    }
    showDialog(ReadingBindings.LABELS[index] + " shortcut", body);
    if (focus != null) {
      final Button target = focus;
      target.post(() -> target.requestFocusFromTouch());
    }
  }

  void focusReadingControls() {
    showControls();
    controlsFocus = true;
    View first = findButton(controlsOverlay, "Contents");
    if (first != null) first.requestFocusFromTouch();
  }

  void fontStep(int step) {
    int size = Math.max(12, Math.min(32, app.fontSize + step));
    if (size != app.fontSize) {
      app.fontSize = size;
      if (app.allowDifferentFontSizes) {
        app.upperFontSize = Math.max(12, Math.min(32, app.upperFontSize + step));
        app.lowerFontSize = Math.max(12, Math.min(32, app.lowerFontSize + step));
      }
      app.appearance();
    }
    toast("Font size " + app.fontSize);
  }

  void readingAction(ReadingBindings.Action action) {
    switch (action) {
      case PREVIOUS:
        app.turn(-1);
        break;
      case NEXT:
        app.turn(1);
        break;
      case FONT_UP:
        fontStep(1);
        break;
      case FONT_DOWN:
        fontStep(-1);
        break;
      case COLORS:
        app.paper = (app.paper + 1) % ReaderApp.PAPER_NAMES.length;
        app.appearance();
        toast(ReaderApp.PAPER_NAMES[app.paper]);
        break;
      case FONT:
        app.font = (app.font + 1) % ReaderApp.FONTS.length;
        app.appearance();
        toast(ReaderApp.FONTS[app.font]);
        break;
      case CONTROLS:
        focusReadingControls();
        break;
      case MENU:
        readerMenu();
        break;
      case BOOKMARK:
        app.bookmarkSmallPage();
        break;
      case CONTENTS:
        contents();
        break;
      case SEARCH:
        search();
        break;
      case NOTES:
        annotations();
        break;
      case DICTIONARY:
        dictionarySearch();
        break;
      case SELECT_PASSAGE:
        passages();
        break;
      case MATCH:
        app.matchScreens = !app.matchScreens;
        app.appearance();
        toast("Match screens " + (app.matchScreens ? "on" : "off"));
        break;
      case LIBRARY:
        app.library();
        break;
      case BACK:
        back();
        break;
      case NONE:
        break;
    }
  }

  public void contents() {
    if (app.book == null) return;
    String[] labels = new String[app.book.toc.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = app.book.toc.get(i).label;
    menu(
        "Contents",
        labels,
        i -> {
          Epub.Toc t = app.book.toc.get(i);
          app.jump(new ReaderApp.Location(t.chapter, app.book.anchor(t)));
        });
  }

  public void search() {
    if (app.book == null) return;
    keyboard(
        "Search this book",
        "",
        "Find",
        query -> {
          LinearLayout body = column();
          TextView t = text("Searching the entire book for “" + query + "”…", 15, ink());
          t.setPadding(dp(5), dp(15), dp(5), dp(15));
          body.addView(t);
          Dialog waiting = showDialog("Full-text search", body);
          Epub source = app.book;
          lookupTask =
              app.lookups.submit(
                  () -> {
                    try {
                      ArrayList<ReaderApp.Match> results = app.searchService.find(source, query);
                      app.main.post(
                          () -> {
                            if (app.book != source || dialog != waiting) return;
                            if (results.isEmpty()) {
                              message("No results", "No passages contain “" + query + "”.");
                              return;
                            }
                            searchResults(query, results);
                          });
                    } catch (Exception e) {
                      app.main.post(
                          () -> {
                            if (dialog == waiting) message("Search unavailable", e.getMessage());
                          });
                    }
                  });
        });
  }

  public static CharSequence highlightedMatch(String label, String query) {
    SpannableString text = new SpannableString(label);
    if (query.isEmpty()) return text;
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(query),
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE)
            .matcher(label);
    while (m.find()) {
      text.setSpan(
          new BackgroundColorSpan(0xffefd374),
          m.start(),
          m.end(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      text.setSpan(
          new ForegroundColorSpan(0xff303b35),
          m.start(),
          m.end(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      text.setSpan(
          new StyleSpan(Typeface.BOLD), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    return text;
  }

  public void searchResults(String query, ArrayList<ReaderApp.Match> results) {
    final Epub source = app.book;
    LinearLayout body = column();
    for (ReaderApp.Match result : results) {
      Button b =
          button(
              "",
              () -> {
                if (app.book != source || !app.mode.equals("reading")) return;
                if (dialog != null) dialog.dismiss();
                app.searchTerm = query;
                app.jump(result.location);
              });
      b.setText(highlightedMatch(result.label, query));
      b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      b.setMaxLines(3);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      p.setMargins(dp(2), dp(4), dp(2), dp(4));
      body.addView(b, p);
    }
    showDialog((results.size() == 300 ? "First 300" : results.size()) + " matches", body);
  }

  public void dictionarySearch() {
    keyboard("English dictionary", "", "Look up", this::dictionary);
  }

  public void dictionary(String word) {
    LinearLayout body = column();
    TextView loading = text("Looking up “" + word + "”…", 15, ink());
    body.addView(loading);
    Dialog waiting = showDialog("English dictionary", body);
    lookupTask =
        app.lookups.submit(
            () -> {
              try {
                String definition = app.dictionary.lookup(word);
                app.main.post(
                    () -> {
                      if (dialog != waiting) return;
                      LinearLayout result = column();
                      TextView content =
                          text(
                              definition.isEmpty()
                                  ? "No entry found for “"
                                      + word
                                      + "”. Try a single English word or another form."
                                  : definition,
                              15,
                              ink());
                      content.setPadding(dp(5), dp(8), dp(5), dp(15));
                      content.setTextIsSelectable(true);
                      result.addView(content);
                      option(result, "Look up another word", this::dictionarySearch);
                      TextView credit =
                          text("WordNet 3.0 · Princeton University · Offline", 10, muted());
                      credit.setPadding(dp(5), dp(12), 0, 0);
                      result.addView(credit);
                      showDialog("English dictionary", result);
                    });
              } catch (Exception e) {
                app.main.post(
                    () -> {
                      if (dialog == waiting) message("English dictionary", e.getMessage());
                    });
              }
            });
  }

  public void passages() {
    if (app.book == null) return;
    ArrayList<ReaderApp.Match> passages = app.sentences();
    if (passages.isEmpty()) {
      toast("No text to select on these pages");
      return;
    }
    String[] labels = new String[passages.size()];
    for (int i = 0; i < labels.length; i++) {
      ReaderApp.Match m = passages.get(i);
      labels[i] = ReaderApp.snippet(m.label, 0, 180);
    }
    menu("Select a passage · both pages", labels, i -> passageActions(passages.get(i)));
  }

  public void selectPassage(int chapter, int offset) {
    ArrayList<ReaderApp.Match> matches = app.sentences();
    ReaderApp.Match best = null;
    for (ReaderApp.Match m : matches)
      if (m.location.chapter == chapter && m.location.offset <= offset) best = m;
    if (best != null) passageActions(best);
    else passages();
  }

  void passageActions(ReaderApp.Match match) {
    menu(
        "Selected passage",
        new String[] {"Highlight passage", "Add a note", "Choose another passage"},
        i -> {
          if (i == 0)
            chooseHighlightColor(
                color -> {
                  app.addMark(
                      "highlight",
                      match.location,
                      match.location.offset + match.label.length(),
                      "",
                      match.label,
                      color);
                  toast("Passage highlighted");
                });
          else if (i == 1)
            noteSelection(
                match.location, match.location.offset + match.label.length(), match.label);
          else passages();
        });
  }

  public void chooseHighlightColor(java.util.function.IntConsumer chosen) {
    LinearLayout body = row();
    for (int i = 0; i < ReaderApp.HIGHLIGHT_COLORS.length; i++) {
      final int color = ReaderApp.HIGHLIGHT_COLORS[i];
      Button b =
          button(
              ReaderApp.HIGHLIGHT_NAMES[i],
              () -> {
                if (dialog != null) dialog.dismiss();
                chosen.accept(color);
              });
      b.setTextColor(0xff303b35);
      StateListDrawable bg = new StateListDrawable();
      bg.addState(new int[] {android.R.attr.state_focused}, shape(color, accent(), 3));
      bg.addState(new int[] {}, shape(color, line(), 1));
      b.setBackground(bg);
      addWeighted(body, b);
    }
    showDialog("Highlight color", body);
  }

  public void noteSelection(ReaderApp.Location start, int end, String quote) {
    keyboard(
        "Note on this passage",
        "",
        "Save note",
        true,
        note -> {
          app.addMark("note", start, end, note, quote);
          toast("Note saved");
        });
  }

  public void annotations() {
    if (app.marks.length() == 0) {
      message(
          "Bookmarks & notes",
          "No saved passages yet. Tap the smaller screen’s upper-right corner to bookmark its page."
              + " Hold a word to highlight text or add a note.");
      return;
    }
    String[] labels = new String[app.marks.length()];
    for (int i = 0; i < labels.length; i++) {
      JSONObject m = app.marks.optJSONObject(i);
      labels[i] =
          (m.optString("kind").equals("bookmark")
                  ? "◆ Bookmark"
                  : m.optString("kind").equals("note") ? "✎ Note" : "▰ Highlight")
              + " · section "
              + (m.optInt("chapter") + 1)
              + "\n"
              + ReaderApp.snippet(m.optString("quote"), 0, 95)
              + (m.optString("note").isEmpty() ? "" : "\n" + m.optString("note"));
    }
    menu("Bookmarks & notes", labels, this::annotation);
  }

  public void annotation(int index) {
    JSONObject m = app.marks.optJSONObject(index);
    if (m == null) return;
    LinearLayout body = column();
    TextView quote = text(m.optString("quote"), 13, muted());
    quote.setMaxLines(2);
    quote.setEllipsize(TextUtils.TruncateAt.END);
    quote.setPadding(dp(5), dp(2), dp(5), dp(8));
    body.addView(quote);
    TextView label = text(m.optString("note").isEmpty() ? "SAVED PASSAGE" : "NOTE", 12, accent());
    label.setTypeface(Typeface.DEFAULT_BOLD);
    body.addView(label);
    NumberedText noteText = new NumberedText(this, false);
    noteText.setText(m.optString("note").isEmpty() ? m.optString("quote") : m.optString("note"));
    body.addView(noteText, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout actions = column();
    option(
        actions,
        "Go to passage",
        () -> {
          dialog.dismiss();
          app.jump(new ReaderApp.Location(m.optInt("chapter"), m.optInt("start")));
        });
    option(
        actions,
        "Edit note",
        () ->
            keyboard(
                "Edit note",
                m.optString("note"),
                "Save note",
                true,
                note -> {
                  try {
                    m.put("note", note);
                    if (!m.optString("kind").equals("bookmark")) m.put("kind", "note");
                    app.annotationsChanged();
                    toast("Note updated");
                  } catch (Exception ignored) {
                  }
                }));
    if (!m.optString("kind").equals("bookmark"))
      option(
          actions,
          "Change highlight color",
          () ->
              chooseHighlightColor(
                  color -> {
                    try {
                      m.put("color", color);
                      app.annotationsChanged();
                    } catch (Exception ignored) {
                    }
                  }));
    option(
        actions,
        "Delete",
        () ->
            menu(
                "Delete saved passage?",
                new String[] {"Keep this passage", "Delete this passage"},
                i -> {
                  if (i == 1) {
                    app.marks.remove(index);
                    app.annotationsChanged();
                    toast("Saved passage deleted");
                  }
                }));
    showPanel(m.optString("note").isEmpty() ? "Saved passage" : "Note", body, actionGrid(actions));
  }

  public void preferences() {
    LinearLayout body = column();
    TextView info =
        text(
            "DEFAULT BOOKS FOLDER\n"
                + (app.root == null ? "Preparing library…" : Uri.decode(app.root)),
            12,
            muted());
    info.setPadding(dp(5), dp(8), dp(5), dp(13));
    body.addView(info);
    option(body, "Choose Android folder…", this::chooseFolder);
    option(
        body,
        "Use app book folder",
        () -> {
          if (dialog != null) dialog.dismiss();
          app.setRoot(Uri.fromFile(Library.appDirectory(app)).toString());
        });
    option(body, "Library theme  ·  " + ReaderApp.THEMES[app.theme], this::libraryThemes);
    rotationControls(body);
    option(body, "Advanced display settings", this::advancedDisplay);
    option(
        body,
        "Match larger screen to smaller  ·  " + (app.matchScreens ? "On" : "Off"),
        () -> {
          app.matchScreens = !app.matchScreens;
          app.appearance();
          preferences();
        });
    Button awake =
        button(
            "Keep screens awake  ·  " + (app.awake ? "On" : "Off"),
            () -> {
              app.awake = !app.awake;
              app.appearance();
              preferences();
            });
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
    p.setMargins(dp(2), dp(4), dp(2), dp(4));
    body.addView(awake, p);
    option(
        body,
        "Brightness  ·  "
            + (app.brightness < 0 ? "System" : Math.round(app.brightness * 100) + "%"),
        () ->
            menu(
                "Screen brightness",
                new String[] {"Use Android brightness", "15%", "30%", "50%", "75%", "100%"},
                i -> {
                  app.brightness = new float[] {-1, .15f, .3f, .5f, .75f, 1f}[i];
                  app.appearance();
                  preferences();
                }));
    option(body, "Button guide", this::guide);
    showDialog("Preferences", body);
  }

  void advancedDisplay() {
    LinearLayout body = column();
    TextView explanation =
        text(
            "Text sizes automatically compensate for each screen’s pixel density. "
                + "Enable separate sizes only to give the two pages different text sizes.",
            14,
            muted());
    body.addView(explanation);
    option(
        body,
        "Allow different font sizes  ·  " + (app.allowDifferentFontSizes ? "On" : "Off"),
        () -> {
          app.allowDifferentFontSizes = !app.allowDifferentFontSizes;
          app.appearance();
          advancedDisplay();
        });
    for (boolean lower : new boolean[] {false, true}) {
      LinearLayout sizes = row();
      TextView label =
          text(
              (lower ? "Smaller" : "Larger")
                  + " screen  ·  "
                  + (lower ? app.lowerFontSize : app.upperFontSize),
              14,
              ink());
      sizes.addView(label, new LinearLayout.LayoutParams(0, -2, 2));
      for (int delta : new int[] {-1, 1}) {
        Button control =
            button(
                delta < 0 ? "A−" : "A+",
                () -> {
                  if (lower) app.lowerFontSize += delta;
                  else app.upperFontSize += delta;
                  app.appearance();
                  advancedDisplay();
                });
        control.setContentDescription(
            (lower ? "Smaller" : "Larger")
                + " screen font "
                + (delta < 0 ? "decrease" : "increase"));
        control.setEnabled(app.allowDifferentFontSizes);
        control.setAlpha(app.allowDifferentFontSizes ? 1f : .4f);
        addWeighted(sizes, control);
      }
      body.addView(sizes);
    }
    showDialog("Advanced display", body);
  }

  void chooseFolder() {
    if (dialog != null) dialog.dismiss();
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    intent.putExtra(
        DocumentsContract.EXTRA_INITIAL_URI,
        Uri.parse("content://com.android.externalstorage.documents/document/primary%3ABooks"));
    try {
      app.pairedTasks.beginExternalActivity(activity);
      activity.startActivityForResult(intent, 41);
    } catch (Exception e) {
      app.pairedTasks.externalActivityReturned();
      message("Choose folder", e.getMessage());
    }
  }

  public void appearance() {
    if (dialog != null) dialog.dismiss();
    showControls();
  }

  public void fontPicker() {
    LinearLayout body = column();
    for (int i = 0; i < ReaderApp.FONTS.length; i++) {
      final int family = i;
      Button b =
          button(
              ReaderApp.FONTS[i] + " · The world is a book",
              () -> {
                dialog.dismiss();
                app.font = family;
                app.appearance();
                showControls();
              });
      b.setTypeface(app.fontTypeface(i));
      b.setTextSize(17);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58));
      p.setMargins(dp(2), dp(4), dp(2), dp(4));
      body.addView(b, p);
    }
    showDialog("Typeface", body);
  }

  public void libraryThemes() {
    LinearLayout body = column();
    for (int i = 0; i < LibraryTheme.ALL.length; i++) {
      final int theme = i;
      LibraryTheme palette = LibraryTheme.ALL[i];
      LinearLayout card = column();
      card.setPadding(dp(12), dp(8), dp(12), dp(5));
      card.setBackground(
          shape(
              palette.surface,
              i == app.theme ? palette.accent : palette.line,
              i == app.theme ? 2 : 1));
      card.setFocusable(true);
      card.setOnClickListener(
          v -> {
            dialog.dismiss();
            app.theme = theme;
            app.appearance();
            preferences();
          });
      TextView title = text(palette.name + (i == app.theme ? "  ✓" : ""), 17, palette.ink);
      card.addView(title);
      card.addView(hardwareStrip(palette));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(80));
      p.setMargins(dp(2), dp(4), dp(2), dp(4));
      body.addView(card, p);
    }
    showDialog("Library theme", body);
  }

  public void guide() {
    StringBuilder keys =
        new StringBuilder(
            "HOLD YOUR DUO LIKE A BOOK\n"
                + "Larger screen left, smaller touch screen right, D-pad at the "
                + (app.rotation + "°")
                + ". Directions follow this grip.\n\nYOUR READING SHORTCUTS\n");
    for (int i = 0; i < ReadingBindings.KEYS.length; i++)
      keys.append(ReadingBindings.LABELS[i])
          .append(" · ")
          .append(app.bindings.get(ReadingBindings.KEYS[i]).label)
          .append('\n');
    keys.append(
        "\n"
            + "Change these in Reading menu > Keybindings. Hold font-size or page-turn shortcuts to"
            + " repeat. Start always opens the menu; Android Back always goes back. D-pad Center"
            + " always focuses reading controls.\n\n"
            + "MENUS & FOCUSED CONTROLS\n"
            + "D-pad + A · Navigate and activate\n"
            + "B · Close a panel or leave control focus\n\n"
            + "TOUCH\n"
            + "Tap to show or hide translucent controls. Font, page colors, spacing and margins are"
            + " at the top; Contents, Search and Menu are at the bottom. They fade away without"
            + " resizing the text.\n\n"
            + "Tap the smaller page’s upper-right corner to bookmark it. Drag to hold and curl a"
            + " page; angle and finish speed follow your gesture. Flick to turn quickly. Pull back"
            + " to cancel.\n\n"
            + "TEXT, NOTES & DICTIONARY\n"
            + "Hold a word, adjust the handles and choose a highlight color, Note, Copy or Define."
            + " Definitions are English and work offline. Notes have an underline and a margin"
            + " icon; tap the icon or marked passage to read or edit the note.\n\n"
            + "The menu lists saved passages and supports selecting passages with buttons. Library"
            + " themes are in Preferences outside the book.\n\n"
            + "TEXT ENTRY\n"
            + "D-pad + A types using the on-screen keyboard. Y inserts a space; L1 deletes.\n\n"
            + "Your position and annotations are written to storage at each committed change,"
            + " before a new page is presented.");
    message("Read your way", keys.toString());
  }

  public void keyboard(String title, String initial, String submit, Consumer<String> done) {
    keyboard(title, initial, submit, false, done);
  }

  void keyboard(
      String title, String initial, String submit, boolean multiline, Consumer<String> done) {
    LinearLayout body = column();
    NumberedText field = new NumberedText(this, true);
    field.setText(initial);
    field.setSelection(field.length());
    body.addView(field, new LinearLayout.LayoutParams(-1, 0, 1));
    AppKeyboard keys = AppKeyboard.forText(this, field, multiline, 4000);
    body.addView(keys, new LinearLayout.LayoutParams(-1, 0, 3));
    LinearLayout footer = column();
    option(
        footer,
        submit,
        () -> {
          String value = field.getText().toString();
          if (!multiline) value = value.replace('\n', ' ').trim();
          if (value.trim().isEmpty()) {
            toast("Enter some text first");
            return;
          }
          if (dialog != null) dialog.dismiss();
          done.accept(value);
        });
    Dialog input = showPanel(title, body, footer);
    input.setOnKeyListener(
        (d, key, event) -> {
          if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP) input.dismiss();
            return true;
          }
          if (key == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && input.getCurrentFocus() != null)
              input.getCurrentFocus().performClick();
            return true;
          }
          return keys.handle(event);
        });
    keys.post(() -> keys.requestFocus(View.FOCUS_FORWARD));
  }

  public boolean handleKey(KeyEvent event) {
    if (app.mode.equals("editing") && dialog == null) return app.editor.handle(event);
    int key = event.getKeyCode();
    if (dialog != null && dialog.isShowing()) {
      // Gamepad events may be routed to the upper activity; deliver to the lower dialog.
      if (activity.getWindow().getDecorView().hasWindowFocus()) return false;
      return dialog.dispatchKeyEvent(event);
    }
    boolean isAction =
        app.bindings.contains(key)
            || key == KeyEvent.KEYCODE_BUTTON_START
            || key == KeyEvent.KEYCODE_BACK
            || key == KeyEvent.KEYCODE_DPAD_CENTER
            || key == KeyEvent.KEYCODE_ENTER;
    boolean arrow =
        key == KeyEvent.KEYCODE_DPAD_LEFT
            || key == KeyEvent.KEYCODE_DPAD_RIGHT
            || key == KeyEvent.KEYCODE_DPAD_UP
            || key == KeyEvent.KEYCODE_DPAD_DOWN;
    if (event.getAction() != KeyEvent.ACTION_DOWN)
      return isAction || (app.mode.equals("reading") && arrow);
    if (key == KeyEvent.KEYCODE_BACK) {
      if (event.getRepeatCount() == 0) back();
      return true;
    }
    if (key == KeyEvent.KEYCODE_BUTTON_START) {
      if (event.getRepeatCount() == 0) readerMenu();
      return true;
    }
    if (app.mode.equals("reading")) {
      if (controlsFocus) {
        scheduleHide();
        if (arrow) return false;
        if (key == KeyEvent.KEYCODE_BUTTON_B) {
          if (event.getRepeatCount() == 0) back();
          return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_A
            || key == KeyEvent.KEYCODE_DPAD_CENTER
            || key == KeyEvent.KEYCODE_ENTER) {
          if (event.getRepeatCount() == 0) {
            View focus = activity.getCurrentFocus();
            if (focus != null) focus.performClick();
          }
          return true;
        }
      }
      if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
        if (event.getRepeatCount() == 0) focusReadingControls();
        return true;
      }
      if (app.bindings.contains(key)) {
        ReadingBindings.Action action = app.bindings.get(key);
        if (event.getRepeatCount() == 0 || action.repeatable) readingAction(action);
        return true;
      }
    } else {
      if (arrow) {
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
          if (event.getRepeatCount() == 0) {
            if (!app.folders.isEmpty()) app.parent();
            else toast("At your default books folder");
          }
          return true;
        }
        if (app.loadingFolder || app.busy) return true;
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
          if (event.getRepeatCount() == 0 && app.selected != null) app.enter(app.selected);
          return true;
        }
        if (list != null && !app.entries.isEmpty()) {
          int index = app.selectedIndex(),
              next =
                  Math.max(
                      0,
                      Math.min(
                          app.entries.size() - 1,
                          index + (key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
          list.requestFocusFromTouch();
          list.setSelection(next);
          preview(app.entries.get(next));
        }
        return true;
      }
      if (key == KeyEvent.KEYCODE_BUTTON_B) {
        back();
        return true;
      }
      if (key == KeyEvent.KEYCODE_BUTTON_X) {
        readerMenu();
        return true;
      }
      if (key == KeyEvent.KEYCODE_BUTTON_Y) {
        if (event.getRepeatCount() == 0) collectionIcon();
        return true;
      }
      if (key == KeyEvent.KEYCODE_BUTTON_L1) {
        app.parent();
        return true;
      }
      if (key == KeyEvent.KEYCODE_BUTTON_A) {
        if (event.getRepeatCount() == 0 && !app.loadingFolder && !app.busy) {
          View focus = activity.getCurrentFocus();
          if (focus != null && focus != list && focus instanceof Button) focus.performClick();
          else if (app.selected != null) app.enter(app.selected);
        }
        return true;
      }
    }
    return false;
  }

  View findButton(View view, String title) {
    if (view instanceof Button && ((Button) view).getText().toString().equals(title)) return view;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findButton(group.getChildAt(i), title);
        if (found != null) return found;
      }
    }
    return null;
  }

  public void routeControllerKey(KeyEvent event) {
    if (dialog != null && dialog.isShowing()) {
      View focus = dialog.getCurrentFocus();
      if (focus != null) focus.requestFocusFromTouch();
      dialog.dispatchKeyEvent(event);
      return;
    }
    if (!app.mode.equals("reading") && list != null && !list.hasFocus()) {
      list.requestFocusFromTouch();
      if (list.getSelectedItemPosition() < 0) list.setSelection(0);
    }
    if (!handleKey(event)) activity.getWindow().superDispatchKeyEvent(event);
  }

  public void back() {
    if (dialog != null) {
      dialog.dismiss();
      return;
    }
    if (paper != null && paper.selectionActive) {
      paper.endSelection();
      return;
    }
    if (controlsFocus) {
      hideControls();
      if (paper != null) paper.requestFocus();
      return;
    }
    if (app.mode.equals("reading") || app.mode.equals("editing")) {
      app.library();
      return;
    }
    if (!app.folders.isEmpty()) {
      app.parent();
      return;
    }
    app.close();
  }

  public void destroy() {
    feedback.dismiss();
    cancelLookup();
    app.main.removeCallbacks(autoHide);
    if (dialog != null) {
      dialog.dismiss();
      dialog = null;
    }
  }
}
