package io.duoreader;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.util.Log;
import java.io.*;
import java.text.BreakIterator;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class ReaderApp extends Application {
  public final Handler main = new Handler(Looper.getMainLooper());
  public final ThreadPoolExecutor worker = TaskExecutors.create("reader-book", 1);
  private final ThreadPoolExecutor folderWorker = TaskExecutors.create("reader-folder", 2);
  private Future<?> folderTask, openTask;
  private android.os.CancellationSignal folderCancellation;
  public final LibraryRepository libraryRepository = new LibraryRepository(this);
  private final ThreadPoolExecutor previews = TaskExecutors.create("reader-preview", 1);
  private Future<?> previewTask;
  private int previewGeneration;
  private final LinkedHashMap<String, Library.Preview> previewCache =
      new LinkedHashMap<String, Library.Preview>(24, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Library.Preview> e) {
          return size() > 24;
        }
      };
  public Library.Preview selectedPreview;
  public boolean previewLoading, loadingFolder, closing;
  public final ArrayList<Runnable> listeners = new ArrayList<>();
  public final SpreadTurn turns = new SpreadTurn(this);
  public final PairedTasks pairedTasks = new PairedTasks(this);
  public final Paginator paginator = new Paginator(this);
  public final DisplayTypography typography = new DisplayTypography(this);
  public final EnglishDictionary dictionary = new EnglishDictionary(this);
  public final BookSearch searchService = new BookSearch();
  public final ThreadPoolExecutor lookups = TaskExecutors.create("reader-lookups", 1);
  public final ReaderInput.Controller controller = new ReaderInput.Controller(this);
  public android.content.SharedPreferences prefs;
  public android.content.SharedPreferences browserPrefs, collectionPrefs;
  public ReadingBindings bindings;
  public BookStateStore stateStore;
  public MainActivity top;
  public ControlsActivity bottom;
  public ReaderUi controls;
  public Epub book;
  public Library.Entry current, selected;
  public ArrayList<Library.Entry> entries = new ArrayList<>();
  public final ArrayDeque<String> folders = new ArrayDeque<>();
  public String root, folder, mode = "library", status = "Loading your library…";
  public int folderGeneration = 0, openGeneration = 0;
  public boolean busy, ready, restoring, resumePending, lastSaveSuccessful = true;
  private int resumeAttempts;
  private boolean saveFailureNotified;
  public Location position = new Location(0, 0);
  public final ArrayDeque<Location> back = new ArrayDeque<>(), forward = new ArrayDeque<>();
  public Page topPage, bottomPage;
  public int topWidth = 420, topHeight = 600, bottomWidth = 420, bottomHeight = 450;
  public int theme = 2, paper = 1, font = 0, fontSize = 18, margin = 24;
  public float spacing = 1.4f, brightness = -1f;
  public boolean animate = true, awake = true, matchScreens = false;
  public int rotation = 90;
  public boolean allowDifferentFontSizes;
  public int upperFontSize = 18, lowerFontSize = 18;
  public int alignment;
  public JSONArray marks = new JSONArray();
  public String searchTerm = "";
  public static final String[] THEMES = {"Black", "16Bit", "GC", "Ice Blue", "Clear Purple"};
  public static final String[] FONTS = {"Literary serif", "Clean sans", "Monospace"};
  public static final String[] PAPER_NAMES = {"Light", "Warm paper", "Night"};
  public static final int[] PAPER_COLORS = {0xfffbfaf5, 0xfff4edda, 0xff181b1b};
  public static final int[] PAPER_INKS = {0xff2f342d, 0xff2f342d, 0xffdadacf};
  public static final int[] HIGHLIGHT_COLORS = {0xffefd374, 0xffa7d59b, 0xff9dcee5, 0xffefb0c8};
  public static final String[] HIGHLIGHT_NAMES = {"Yellow", "Green", "Blue", "Pink"};

  public static final class Location {
    public final int chapter, offset;

    public Location(int c, int o) {
      chapter = c;
      offset = o;
    }

    public boolean equals(Object o) {
      return o instanceof Location
          && ((Location) o).chapter == chapter
          && ((Location) o).offset == offset;
    }

    public int hashCode() {
      return chapter * 31 + offset;
    }
  }

  public static final class Page {
    public Location start, next;
    public StaticLayout layout;
    public String chapterTitle;
    public CharSequence text;
  }

  public static final class Match {
    public String label;
    public Location location;

    public Match(String s, Location l) {
      label = s;
      location = l;
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    prefs = getSharedPreferences("reader", MODE_PRIVATE);
    bindings = new ReadingBindings(prefs);
    stateStore = new BookStateStore(prefs);
    browserPrefs = getSharedPreferences("browser", MODE_PRIVATE);
    collectionPrefs = getSharedPreferences("collections", MODE_PRIVATE);
    theme = prefs.getInt("theme", 2);
    paper = prefs.getInt("paper", 1);
    font = prefs.getInt("font", 0);
    fontSize = prefs.getInt("size", 18);
    alignment = prefs.getInt("alignment", 0);
    allowDifferentFontSizes = prefs.getBoolean("allowDifferentFontSizes", false);
    upperFontSize = prefs.getInt("upperFontSize", fontSize);
    lowerFontSize = prefs.getInt("lowerFontSize", fontSize);
    margin = prefs.getInt("margin", 24);
    spacing = prefs.getFloat("spacing", 1.4f);
    animate = true;
    awake = prefs.getBoolean("awake", true);
    brightness = prefs.getFloat("brightness", -1f);
    matchScreens = prefs.getBoolean("matchScreens", false);
    rotation = prefs.getInt("rotation", prefs.getBoolean("dpadBottom", true) ? 90 : 270);
    restoring = prefs.getBoolean("reading", false);
    resumePending = restoring;
    ReaderSettings.sanitize(this);
    typography.refresh();
    worker.execute(
        () -> {
          String defaultRoot = Uri.fromFile(Library.appDirectory(this)).toString();
          main.post(
              () -> {
                ready = true;
                root = prefs.getString("root", defaultRoot);
                folder = root;
                restoreBrowser();
                if (restoring) {
                  String uri = prefs.getString("lastUri", "");
                  if (!uri.isEmpty()) {
                    Library.Entry entry =
                        new Library.Entry(uri, prefs.getString("lastName", "Book"), false);
                    entry.modified = prefs.getLong("lastModified", 0);
                    entry.size = prefs.getLong("lastSize", 0);
                    open(entry);
                  } else {
                    restoring = false;
                    resumePending = false;
                  }
                }
              });
        });
  }

  public String key(String uri) {
    return stateStore.key(uri);
  }

  public String bookKey() {
    return current == null ? "" : key(current.uri);
  }

  public String folderLabel() {
    if (folder == null) return "Library";
    try {
      Uri u = Uri.parse(folder);
      String path = u.getPath();
      if ("file".equals(u.getScheme())) return new File(path).getName();
      String doc =
          android.provider.DocumentsContract.isDocumentUri(this, u)
              ? android.provider.DocumentsContract.getDocumentId(u)
              : android.provider.DocumentsContract.getTreeDocumentId(u);
      int slash = doc.lastIndexOf('/');
      return slash >= 0 ? doc.substring(slash + 1) : doc.substring(doc.indexOf(':') + 1);
    } catch (Exception e) {
      return "Your library";
    }
  }

  public void changed() {
    for (Runnable r : new ArrayList<>(listeners)) r.run();
  }

  public LibraryTheme libraryTheme() {
    return LibraryTheme.get(theme);
  }

  private void restoreBrowser() {
    if (root.equals(browserPrefs.getString("root", ""))) {
      try {
        JSONArray stack = new JSONArray(browserPrefs.getString("parents", "[]"));
        for (int i = 0; i < Math.min(64, stack.length()); i++) folders.addLast(stack.getString(i));
        folder = browserPrefs.getString("folder", root);
      } catch (Exception e) {
        folders.clear();
        folder = root;
      }
    }
    loadFolder(folder);
  }

  private void saveBrowser() {
    JSONArray stack = new JSONArray();
    for (String parent : folders) stack.put(parent);
    browserPrefs
        .edit()
        .putString("root", root)
        .putString("folder", folder)
        .putString("parents", stack.toString())
        .apply();
  }

  public int selectedIndex() {
    if (selected != null)
      for (int i = 0; i < entries.size(); i++)
        if (entries.get(i).uri.equals(selected.uri)) return i;
    return entries.isEmpty() ? -1 : 0;
  }

  public void selectEntry(Library.Entry entry) {
    if (entry == null) return;
    boolean different = selected == null || !selected.uri.equals(entry.uri);
    selected = entry;
    int index = -1;
    for (int i = 0; i < entries.size(); i++)
      if (entries.get(i).uri.equals(entry.uri)) {
        index = i;
        break;
      }
    if (folder != null && index >= 0)
      browserPrefs
          .edit()
          .putString("selected." + Library.id(folder), entry.uri)
          .putInt("index." + Library.id(folder), index)
          .apply();
    libraryRepository.request(entry, true);
    if (!busy && (different || selectedPreview == null && !previewLoading)) requestPreview(entry);
  }

  public void requestPreview(Library.Entry entry) {
    int generation = ++previewGeneration;
    TaskExecutors.cancel(previewTask, previews);
    String key = entry.uri + ":" + entry.modified + ":" + entry.size;
    selectedPreview = previewCache.get(key);
    previewLoading = selectedPreview == null;
    if (!previewLoading) return;
    previewTask =
        previews.submit(
            () -> {
              Library.Preview result = Library.preview(this, entry);
              main.post(
                  () -> {
                    if (generation != previewGeneration) return;
                    previewCache.put(key, result);
                    selectedPreview = result;
                    previewLoading = false;
                    changed();
                  });
            });
  }

  public String collectionIcon(String uri) {
    return CollectionIcons.valid(collectionPrefs.getString("icon." + Library.id(uri), "folder"));
  }

  public boolean setCollectionIcon(String uri, String icon) {
    boolean saved =
        collectionPrefs
            .edit()
            .putString("icon." + Library.id(uri), CollectionIcons.valid(icon))
            .commit();
    if (saved) changed();
    return saved;
  }

  public void loadFolder(String target) {
    if (target == null) return;
    if (folder != null && selected != null) selectEntry(selected);
    libraryRepository.cancelRequests();
    folder = target;
    loadingFolder = true;
    entries = new ArrayList<>();
    selected = null;
    selectedPreview = null;
    previewLoading = false;
    ++previewGeneration;
    TaskExecutors.cancel(previewTask, previews);
    previewCache.clear();
    status = "Reading folder…";
    int generation = ++folderGeneration;
    saveBrowser();
    if (folderCancellation != null) folderCancellation.cancel();
    TaskExecutors.cancel(folderTask, folderWorker);
    final android.os.CancellationSignal cancellation = new android.os.CancellationSignal();
    folderCancellation = cancellation;
    folderTask =
        folderWorker.submit(
            () -> {
              ArrayList<Library.Entry> found;
              String message = "";
              try {
                found = Library.list(this, target, cancellation);
              } catch (Exception e) {
                found = new ArrayList<>();
                message = "Folder unavailable. Choose it again in Preferences. " + e.getMessage();
              }
              final ArrayList<Library.Entry> result = found;
              final String error = message;
              main.post(
                  () -> {
                    if (generation != folderGeneration) return;
                    loadingFolder = false;
                    entries = result;
                    int index =
                        Math.max(
                            0,
                            Math.min(
                                result.size() - 1,
                                browserPrefs.getInt("index." + Library.id(target), 0)));
                    String selectedUri =
                        browserPrefs.getString("selected." + Library.id(target), "");
                    for (int i = 0; i < result.size(); i++)
                      if (result.get(i).uri.equals(selectedUri)) index = i;
                    selected = null;
                    if (!result.isEmpty()) selectEntry(result.get(index));
                    status =
                        error.isEmpty()
                            ? (result.size()
                                + (result.size() == 1 ? " item" : " items")
                                + " · EPUB library")
                            : error;
                    changed();
                  });
            });
    changed();
  }

  public void enter(Library.Entry entry) {
    if (entry == null || loadingFolder || busy || closing) return;
    selectEntry(entry);
    if (entry.folder) {
      if (entry.uri.equals(folder) || folders.size() >= 64) return;
      folders.addLast(folder);
      loadFolder(entry.uri);
    } else open(entry);
  }

  public void parent() {
    if (!folders.isEmpty()) {
      String parent = folders.removeLast();
      loadFolder(parent);
    }
  }

  public void requestCover(Library.Entry entry) {
    libraryRepository.request(entry, selected != null && selected.uri.equals(entry.uri));
  }

  public void setRoot(String uri) {
    turns.cancel();
    root = uri;
    folders.clear();
    resumePending = false;
    mode = "library";
    cancelOpen();
    restoring = false;
    prefs.edit().putString("root", uri).putBoolean("reading", false).commit();
    loadFolder(uri);
  }

  private void cancelOpen() {
    ++openGeneration;
    TaskExecutors.cancel(openTask, worker);
    openTask = null;
    busy = false;
  }

  public void open(Library.Entry entry) {
    if (busy || closing) return;
    turns.cancel();
    libraryRepository.cancelRequests();
    TaskExecutors.cancel(previewTask, previews);
    ++previewGeneration;
    previewLoading = false;
    selectedPreview = null;
    busy = true;
    status = "Opening " + entry.title + "…";
    int generation = ++openGeneration;
    final Library.Entry opened = new Library.Entry(entry.uri, entry.name, false);
    opened.modified = entry.modified;
    opened.size = entry.size;
    opened.cover = entry.cover;
    final int width = topWidth,
        secondWidth = bottomWidth,
        imageHeight = Math.max(60, Math.min(topHeight, bottomHeight) - 20);
    changed();
    openTask =
        worker.submit(
            () -> {
              Epub candidate = null;
              try {
                candidate =
                    new Epub(Library.cached(this, opened.uri, opened.modified, opened.size));
                if (candidate.title.equals("Untitled")) candidate.title = opened.title;
                opened.title = candidate.title;
                opened.author = candidate.author;
                opened.description = candidate.description;
                opened.hasCover = !candidate.coverPath.isEmpty();
                opened.loaded = true;
                if (opened.cover == null) opened.cover = candidate.cover(128);
                String key = key(opened.uri);
                Location restored =
                    new Location(
                        Math.max(
                            0,
                            Math.min(
                                candidate.chapters.size() - 1, prefs.getInt(key + "chapter", 0))),
                        Math.max(0, prefs.getInt(key + "offset", 0)));
                if (Thread.currentThread().isInterrupted())
                  throw new InterruptedIOException("Open cancelled");
                candidate.text(restored.chapter, Math.max(40, width), imageHeight);
                if (secondWidth != width)
                  candidate.text(restored.chapter, Math.max(40, secondWidth), imageHeight);
                if (Thread.currentThread().isInterrupted())
                  throw new InterruptedIOException("Open cancelled");
                final Epub epub = candidate;
                candidate = null;
                main.post(
                    () -> {
                      if (generation != openGeneration || closing) {
                        try {
                          epub.close();
                        } catch (Exception ignored) {
                        }
                        return;
                      }
                      if (book != null)
                        try {
                          book.close();
                        } catch (Exception ignored) {
                        }
                      book = epub;
                      current = opened;
                      selected = opened;
                      busy = false;
                      mode = "reading";
                      restoring = false;
                      resumePending = false;
                      resumeAttempts = 0;
                      searchTerm = "";
                      status = "";
                      position = restored;
                      try {
                        marks = new JSONArray(prefs.getString(key + "marks", "[]"));
                      } catch (Exception e) {
                        marks = new JSONArray();
                      }
                      back.clear();
                      forward.clear();
                      topPage = null;
                      bottomPage = null;
                      paginate();
                      save();
                      changed();
                    });
              } catch (Exception error) {
                if (candidate != null)
                  try {
                    candidate.close();
                  } catch (IOException ignored) {
                  }
                main.post(
                    () -> {
                      if (generation != openGeneration || closing) return;
                      busy = false;
                      restoring = false;
                      status = "Could not open this EPUB: " + error.getMessage();
                      boolean retry = resumePending && current == null && resumeAttempts++ < 3;
                      save();
                      changed();
                      if (retry) main.postDelayed(this::retryResume, 600L << resumeAttempts);
                      else if (controls != null) controls.message("Cannot open book", status);
                    });
              }
            });
  }

  public void resumeIfPending() {
    if (ready && resumePending && current == null && !busy && !closing) {
      restoring = true;
      retryResume();
    }
  }

  private void retryResume() {
    if (!resumePending || busy || current != null) return;
    String uri = prefs.getString("lastUri", "");
    if (uri.isEmpty()) return;
    Library.Entry entry = new Library.Entry(uri, prefs.getString("lastName", "Book"), false);
    entry.modified = prefs.getLong("lastModified", 0);
    entry.size = prefs.getLong("lastSize", 0);
    open(entry);
  }

  public Typeface fontTypeface(int family) {
    return Typeface.create(
        family == 1 ? "sans-serif" : family == 2 ? "monospace" : "serif", Typeface.NORMAL);
  }

  public Typeface typeface() {
    return fontTypeface(font);
  }

  public int ink() {
    return PAPER_INKS[Math.max(0, Math.min(2, paper))];
  }

  public int pageColor() {
    return PAPER_COLORS[Math.max(0, Math.min(2, paper))];
  }

  public TextPaint textPaint() {
    return textPaint(false);
  }

  public TextPaint textPaint(boolean lower) {
    TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    p.setTypeface(typeface());
    p.setTextSize(typography.pixels(lower));
    p.setColor(ink());
    return p;
  }

  public Page page(Location location, int width, int height) throws IOException {
    return page(location, width, height, false);
  }

  public Page page(Location location, int width, int height, boolean lower) throws IOException {
    return paginator.page(location, width, height, lower);
  }

  public boolean end(Location loc) {
    try {
      return book != null
          && loc.chapter == book.chapters.size() - 1
          && loc.offset
              >= book.text(
                      loc.chapter, topWidth, Math.max(60, Math.min(topHeight, bottomHeight) - 20))
                  .length();
    } catch (Exception e) {
      return false;
    }
  }

  public void paginate() {
    if (book == null) return;
    try {
      topPage = page(position, topWidth, topHeight);
      position = topPage.start;
      paginator.rememberSpread(position);
      bottomPage = end(topPage.next) ? null : page(topPage.next, bottomWidth, bottomHeight, true);
    } catch (Exception e) {
      status = "Page error: " + e.getMessage();
      Log.e("DuoReader", "Pagination", e);
    }
  }

  public Location spreadEnd() {
    return bottomPage != null ? bottomPage.next : topPage != null ? topPage.next : position;
  }

  public void dimensions(boolean lower, int width, int height) {
    if (width < 40 || height < 40) return;
    if (lower) {
      if (bottomWidth == width && bottomHeight == height) return;
      bottomWidth = width;
      bottomHeight = height;
    } else {
      if (topWidth == width && topHeight == height) return;
      topWidth = width;
      topHeight = height;
    }
    turns.cancel();
    if (mode.equals("reading")) paginate();
    changed();
  }

  public void turn(int direction) {
    if (book == null || busy || !mode.equals("reading")) return;
    if (turns.active) {
      if (!turns.interactive) turns.queued = direction;
      return;
    }
    Location next = turnTarget(direction);
    if (next == null) {
      if (direction > 0 && controls != null) controls.toast("You’ve reached the end of this book");
      return;
    }
    if (animate) turns.start(direction);
    else {
      if (controls != null) controls.hideControls();
      commitTurn(direction, next);
    }
  }

  public Location turnTarget(int direction) {
    if (direction > 0) {
      Location next = forward.isEmpty() ? spreadEnd() : forward.peekLast();
      return end(next) ? null : next;
    }
    if (position.chapter == 0 && position.offset == 0) return null;
    return back.isEmpty() ? previous() : back.peekLast();
  }

  public void commitTurn(int direction, Location next) {
    if (next == null) return;
    if (direction > 0) {
      if (next.equals(forward.peekLast())) forward.removeLast();
      back.addLast(position);
      if (back.size() > 500) back.removeFirst();
    } else {
      forward.addLast(position);
      if (forward.size() > 500) forward.removeFirst();
      if (next.equals(back.peekLast())) back.removeLast();
    }
    position = next;
    paginate();
    save();
    changed();
  }

  private Location previous() {
    return paginator.previous(position);
  }

  public void annotationsChanged() {
    paginator.invalidateAnnotations();
    paginate();
    save();
    changed();
  }

  public void jump(Location loc) {
    if (book == null) return;
    turns.cancel();
    back.addLast(position);
    if (back.size() > 500) back.removeFirst();
    forward.clear();
    position = loc;
    paginate();
    save();
    changed();
  }

  public void library() {
    turns.cancel();
    resumePending = false;
    restoring = false;
    cancelOpen();
    mode = "library";
    Library.Entry target = null;
    if (selected != null)
      for (Library.Entry entry : entries)
        if (entry.uri.equals(selected.uri)) {
          target = entry;
          break;
        }
    if (target == null && !entries.isEmpty()) {
      String saved = browserPrefs.getString("selected." + Library.id(folder), "");
      int index =
          Math.max(
              0,
              Math.min(entries.size() - 1, browserPrefs.getInt("index." + Library.id(folder), 0)));
      target = entries.get(index);
      for (Library.Entry entry : entries)
        if (entry.uri.equals(saved)) {
          target = entry;
          break;
        }
    }
    selected = null;
    selectedPreview = null;
    if (target != null) selectEntry(target);
    else {
      ++previewGeneration;
      previewLoading = false;
      TaskExecutors.cancel(previewTask, previews);
    }
    save();
    changed();
  }

  public void close() {
    turns.cancel();
    save();
    libraryRepository.cancelRequests();
    closing = true;
    cancelOpen();
    if (folderCancellation != null) folderCancellation.cancel();
    TaskExecutors.cancel(folderTask, folderWorker);
    ++folderGeneration;
    ++previewGeneration;
    loadingFolder = false;
    busy = false;
    TaskExecutors.cancel(previewTask, previews);
    controller.cancel();
    if (controls != null) controls.destroy();
    ControlsActivity lower = bottom;
    MainActivity upper = top;
    if (lower != null) lower.finishAndRemoveTask();
    if (upper != null) upper.finishAndRemoveTask();
  }

  public void resume() {
    if (book != null) {
      mode = "reading";
      save();
      changed();
    }
  }

  public String progress() {
    if (book == null) return "";
    return "Section "
        + (position.chapter + 1)
        + " of "
        + book.chapters.size()
        + " · "
        + progressPercent()
        + "%";
  }

  public int progressPercent() {
    return progressPercent(position);
  }

  public int progressPercent(Location location) {
    if (book == null) return 0;
    float fraction = 0;
    try {
      fraction =
          location.offset
              / (float)
                  Math.max(
                      1,
                      book.text(
                              location.chapter,
                              topWidth,
                              Math.max(60, Math.min(topHeight, bottomHeight) - 20))
                          .length());
    } catch (Exception ignored) {
    }
    return Math.min(100, Math.round(100 * (location.chapter + fraction) / book.chapters.size()));
  }

  public void save() {
    if (!ready || restoring || (resumePending && current == null)) return;
    android.content.SharedPreferences.Editor e =
        prefs.edit().putBoolean("reading", mode.equals("reading"));
    if (current != null)
      e.putString(bookKey() + "uri", current.uri)
          .putString("lastUri", current.uri)
          .putString("lastName", current.name)
          .putLong("lastModified", current.modified)
          .putLong("lastSize", current.size)
          .putString(bookKey() + "title", current.title)
          .putInt(bookKey() + "chapter", position.chapter)
          .putInt(bookKey() + "offset", position.offset)
          .putInt(bookKey() + "percent", progressPercent())
          .putString(bookKey() + "marks", marks.toString());
    // SharedPreferences uses an atomic backup/replace and fsync. Complete the write
    // before changed() can publish a new position; lifecycle callbacks are only extra checkpoints.
    lastSaveSuccessful = e.commit();
    if (!lastSaveSuccessful) {
      status = "Your reading position could not be saved. Check available storage.";
      Log.e("DuoReader", status);
      if (!saveFailureNotified && controls != null) controls.toast(status);
      saveFailureNotified = true;
    } else saveFailureNotified = false;
  }

  @Override
  public void onTrimMemory(int level) {
    if (level >= TRIM_MEMORY_UI_HIDDEN) {
      turns.cancel();
      save();
      paginator.trim();
      if (book != null) book.trim();
      lookups.execute(
          () -> {
            try {
              searchService.close();
            } catch (IOException error) {
              Log.w("DuoReader", "Search cache cleanup", error);
            }
          });
      libraryRepository.trim();
      if (top != null && top.paper != null) top.paper.releaseTurnBuffers();
      if (controls != null && controls.paper != null) controls.paper.releaseTurnBuffers();
    }
    super.onTrimMemory(level);
  }

  public void appearance() {
    ReaderSettings.sanitize(this);
    typography.refresh();
    turns.cancel();
    prefs
        .edit()
        .putInt("theme", theme)
        .putInt("paper", paper)
        .putInt("font", font)
        .putInt("size", fontSize)
        .putInt("alignment", alignment)
        .putBoolean("allowDifferentFontSizes", allowDifferentFontSizes)
        .putInt("upperFontSize", upperFontSize)
        .putInt("lowerFontSize", lowerFontSize)
        .putInt("margin", margin)
        .putFloat("spacing", spacing)
        .putBoolean("animate", animate)
        .putBoolean("awake", awake)
        .putFloat("brightness", brightness)
        .putBoolean("matchScreens", matchScreens)
        .putInt("rotation", rotation)
        .remove("dpadBottom")
        .apply();
    if (mode.equals("reading")) paginate();
    save();
    changed();
    if (controls != null) controls.updateDialogOrientation();
    if (top != null) top.windowSettings();
    if (bottom != null) bottom.windowSettings();
  }

  public void bookmark() {
    bookmarkPage(position, topPage);
  }

  public void bookmarkSmallPage() {
    if (bottomPage != null) bookmarkPage(bottomPage.start, bottomPage);
  }

  private void bookmarkPage(Location location, Page page) {
    for (int i = 0; i < marks.length(); i++) {
      JSONObject m = marks.optJSONObject(i);
      if (m != null
          && m.optString("kind").equals("bookmark")
          && m.optInt("chapter") == location.chapter
          && m.optInt("start") == location.offset) {
        marks.remove(i);
        save();
        changed();
        if (controls != null) controls.toast("Bookmark removed");
        return;
      }
    }
    addMark(
        "bookmark",
        location,
        location.offset,
        "",
        page == null ? "Bookmark" : snippet(page.text.toString(), 0, 85));
    if (controls != null) controls.toast("Bookmark saved");
  }

  public int markAt(Location location) {
    int highlight = -1;
    for (int i = marks.length() - 1; i >= 0; i--) {
      JSONObject m = marks.optJSONObject(i);
      if (m != null
          && !m.optString("kind").equals("bookmark")
          && m.optInt("chapter") == location.chapter
          && m.optInt("start") <= location.offset
          && m.optInt("end") > location.offset) {
        if (!m.optString("note").isEmpty() || m.optString("kind").equals("note")) return i;
        if (highlight < 0) highlight = i;
      }
    }
    return highlight;
  }

  public void addMark(String kind, Location start, int end, String note, String quote) {
    addMark(kind, start, end, note, quote, HIGHLIGHT_COLORS[0]);
  }

  public void addMark(String kind, Location start, int end, String note, String quote, int color) {
    turns.cancel();
    try {
      JSONObject m = new JSONObject();
      m.put("kind", kind)
          .put("chapter", start.chapter)
          .put("start", start.offset)
          .put("end", end)
          .put("quote", quote)
          .put("note", note)
          .put("color", color)
          .put("created", System.currentTimeMillis());
      marks.put(m);
      annotationsChanged();
    } catch (Exception e) {
      Log.e("DuoReader", "Annotation", e);
    }
  }

  public int highlightColor(int color) {
    if (paper != 2) return color;
    int bg = pageColor();
    return Color.rgb(
        Math.round(Color.red(color) * .38f + Color.red(bg) * .62f),
        Math.round(Color.green(color) * .38f + Color.green(bg) * .62f),
        Math.round(Color.blue(color) * .38f + Color.blue(bg) * .62f));
  }

  public boolean bookmarked() {
    return bookmarkedAt(position);
  }

  public boolean bookmarkedAt(Location loc) {
    for (int i = 0; i < marks.length(); i++) {
      JSONObject m = marks.optJSONObject(i);
      if (m != null
          && m.optString("kind").equals("bookmark")
          && m.optInt("chapter") == loc.chapter
          && m.optInt("start") == loc.offset) return true;
    }
    return false;
  }

  public static String snippet(String text, int offset, int count) {
    int a = Math.max(0, offset), b = Math.min(text.length(), a + count);
    return text.substring(a, b).replaceAll("\\s+", " ").trim();
  }

  public ArrayList<Match> search(String query) throws Exception {
    return searchService.find(book, query);
  }

  public ArrayList<Match> sentences() {
    ArrayList<Match> result = new ArrayList<>();
    for (Page p : new Page[] {topPage, bottomPage})
      if (p != null) {
        String text = p.text.toString();
        BreakIterator split = BreakIterator.getSentenceInstance(Locale.getDefault());
        split.setText(text);
        int start = split.first(), end;
        while ((end = split.next()) != BreakIterator.DONE) {
          int first = start;
          while (first < end && Character.isWhitespace(text.charAt(first))) first++;
          String s = text.substring(first, end).trim();
          if (!s.isEmpty())
            result.add(new Match(s, new Location(p.start.chapter, p.start.offset + first)));
          start = end;
        }
      }
    return result;
  }

  public void clearCache() {
    File[] files = getCacheDir().listFiles();
    if (files != null)
      for (File f : files)
        if (f.getName().startsWith("book-") && !f.getName().endsWith(".part")) f.delete();
  }
}
