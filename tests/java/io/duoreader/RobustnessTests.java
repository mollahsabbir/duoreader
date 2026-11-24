package io.duoreader;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.view.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import org.json.*;

/** Edge cases for the new layout, identities, cancellation, caches, and landscape presentation. */
public final class RobustnessTests extends Instrumentation {
  ReaderApp app;
  final JSONArray checks = new JSONArray();
  File fixtures;

  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  void ui(Runnable action) {
    runOnMainSync(action);
    waitForIdleSync();
  }

  void until(BooleanSupplier condition, String label) throws Exception {
    long end = SystemClock.uptimeMillis() + 20000;
    while (SystemClock.uptimeMillis() < end) {
      AtomicBoolean ready = new AtomicBoolean();
      runOnMainSync(() -> ready.set(condition.getAsBoolean()));
      if (ready.get()) return;
      SystemClock.sleep(25);
    }
    throw new AssertionError("Timeout: " + label);
  }

  void check(String name, boolean passed) throws Exception {
    checks.put(new JSONObject().put("check", name).put("passed", passed));
    Bundle b = new Bundle();
    b.putString("stream", (passed ? "PASS " : "FAIL ") + name + "\n");
    sendStatus(passed ? 0 : -1, b);
    if (!passed) throw new AssertionError(name);
  }

  void identities() throws Exception {
    SharedPreferences prefs = app.getSharedPreferences("state-regression", 0);
    prefs.edit().clear().commit();
    try {
      String a = "file:///Aa",
          b = "file:///BB",
          old = "book." + Integer.toHexString(a.hashCode()) + ".";
      check(
          "Fixture has two different URIs with the same legacy Java hash",
          !a.equals(b) && a.hashCode() == b.hashCode());
      String marks = "[{\"kind\":\"note\",\"note\":\"Keep my note\",\"start\":21,\"end\":38}]";
      prefs
          .edit()
          .putString("lastUri", a)
          .putInt(old + "chapter", 3)
          .putInt(old + "offset", 91)
          .putInt(old + "percent", 24)
          .putString(old + "marks", marks)
          .putString(old + "title", "My book")
          .commit();
      BookStateStore store = new BookStateStore(prefs);
      String kb = store.key(b), ka = store.key(a);
      check(
          "Colliding book URIs have separate SHA-256 identities",
          !ka.equals(kb) && ka.length() > 70);
      check(
          "A colliding URI cannot inherit the last book's location or notes",
          !prefs.contains(kb + "chapter") && !prefs.contains(kb + "marks"));
      check(
          "Migration preserves chapter, character, progress, title and annotations",
          prefs.getInt(ka + "chapter", 0) == 3
              && prefs.getInt(ka + "offset", 0) == 91
              && prefs.getInt(ka + "percent", 0) == 24
              && prefs.getString(ka + "title", "").equals("My book")
              && prefs.getString(ka + "marks", "").equals(marks));
      check(
          "Legacy reading data remains intact during migration",
          prefs.getString(old + "marks", "").equals(marks)
              && prefs.getInt(old + "offset", 0) == 91);
      prefs
          .edit()
          .putString(kb + "uri", b)
          .putInt(kb + "offset", 74)
          .putInt(ka + "offset", 150)
          .commit();
      BookStateStore reopened = new BookStateStore(prefs);
      check(
          "Reopening does not overwrite new locations with legacy data",
          prefs.getInt(reopened.key(a) + "offset", 0) == 150
              && prefs.getInt(reopened.key(b) + "offset", 0) == 74);
    } finally {
      prefs.edit().clear().commit();
    }
  }

  void cacheGuards() throws Exception {
    boolean limited = false, cancelled = false;
    try {
      EpubCache.copy(new ByteArrayInputStream(new byte[100]), new ByteArrayOutputStream(), 50);
    } catch (IOException expected) {
      limited = true;
    }
    check("EPUB copying enforces its byte limit while streaming", limited);
    Thread.currentThread().interrupt();
    try {
      EpubCache.copy(new ByteArrayInputStream(new byte[100]), new ByteArrayOutputStream(), 200);
    } catch (InterruptedIOException expected) {
      cancelled = true;
    } finally {
      Thread.interrupted();
    }
    check("Cancelled EPUB copies stop before publishing a destination", cancelled);
    boolean missing = false;
    try {
      EpubCache.resolve(app, Uri.fromFile(new File(fixtures, "missing.epub")).toString(), 0, 0);
    } catch (FileNotFoundException expected) {
      missing = true;
    }
    check("Deleted local EPUBs fail with a useful error", missing);
    File partial = new File(fixtures, "partial.epub");
    try (FileOutputStream out = new FileOutputStream(partial)) {
      out.write(new byte[] {1, 2, 3});
    }
    boolean invalid = false;
    try (Epub ignored = new Epub(partial)) {
    } catch (Exception expected) {
      invalid = true;
    }
    check("Partial EPUB archives are rejected", invalid);
    byte[] pixels;
    Bitmap image = Bitmap.createBitmap(480, 720, Bitmap.Config.RGB_565);
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      image.compress(Bitmap.CompressFormat.PNG, 100, out);
      pixels = out.toByteArray();
    }
    image.recycle();
    Bitmap thumbnail = Epub.decode(pixels, 128, 256), hero = Epub.decode(pixels, 480, 960);
    check(
        "List covers decode to thumbnail dimensions instead of retaining oversized bitmaps",
        thumbnail.getWidth() <= 128 && thumbnail.getHeight() <= 256);
    check(
        "Selected book covers retain separate high-resolution artwork",
        hero.getWidth() == 480 && hero.getHeight() == 720);
    thumbnail.recycle();
    hero.recycle();
  }

  File fixture() throws Exception {
    File file = new File(fixtures, "layout.epub");
    StringBuilder html =
        new StringBuilder(
            "<html><head><style>badstyle</style></head><body><script>badscript</script><h1>İstanbul"
                + " before TARGET</h1><p><b>Bold opening</b> and 😀 an emoji.</p><p>Before image"
                + " <img src='image.png'/> after image.</p>");
    for (int i = 0; i < 400; i++)
      html.append("<p>Paragraph ")
          .append(i)
          .append(
              ": The bright river carries a little boat past the willow trees. Words remain"
                  + " consecutive across each page. 😀 A new journey begins.</p>");
    html.append("</body></html>");
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
      TestBooks.part(
          zip,
          "META-INF/container.xml",
          "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>");
      TestBooks.part(
          zip,
          "OPS/book.opf",
          "<package xmlns='http://www.idpf.org/2007/opf'"
              + " xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>Regression"
              + " book</dc:title></metadata><manifest><item id='one' href='chapter.xhtml'"
              + " media-type='application/xhtml+xml'/></manifest><spine><itemref"
              + " idref='one'/></spine></package>");
      TestBooks.part(zip, "OPS/chapter.xhtml", html.toString());
      Bitmap bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.RGB_565);
      zip.putNextEntry(new ZipEntry("OPS/image.png"));
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip);
      zip.closeEntry();
      bitmap.recycle();
    }
    return file;
  }

  void settings() throws Exception {
    ui(
        () -> {
          app.theme = -20;
          app.paper = 200;
          app.font = 200;
          app.fontSize = 0;
          app.margin = 200;
          app.spacing = Float.NaN;
          app.brightness = Float.POSITIVE_INFINITY;
          ReaderSettings.sanitize(app);
        });
    check(
        "Invalid saved palette and font indexes are clamped",
        app.theme == 0 && app.paper == 2 && app.font == 2);
    check(
        "Invalid size, margin, spacing and brightness remain safe",
        app.fontSize == 12 && app.margin == 64 && app.spacing == 1.4f && app.brightness == -1);
    ui(
        () -> {
          app.theme = 2;
          app.paper = 1;
          app.font = 0;
          app.fontSize = 18;
          app.margin = 24;
          app.spacing = 1.4f;
          app.brightness = -1;
          app.appearance();
        });
  }

  void layoutAndSearch(File file) throws Exception {
    Library.Entry entry =
        new Library.Entry(Uri.fromFile(file).toString(), "Regression book.epub", false);
    ui(() -> app.open(entry));
    until(
        () -> !app.busy && app.current != null && app.current.uri.equals(entry.uri),
        "fixture open");
    Epub source = app.book;
    String text =
        source.text(0, app.topWidth, Math.min(app.topHeight, app.bottomHeight) - 20).toString();
    check(
        "Search text and displayed text have identical image and Unicode offsets",
        text.equals(source.plainText(0)));
    check(
        "Scripts and styles cannot enter chapter text",
        !text.contains("badscript") && !text.contains("badstyle"));
    ArrayList<ReaderApp.Match> found = app.search("target");
    check(
        "Unicode case-insensitive search keeps the original character offset",
        found.size() == 1 && found.get(0).location.offset == text.indexOf("TARGET"));
    Spanned highlighted = (Spanned) ReaderUi.highlightedMatch("İstanbul before TARGET", "target");
    BackgroundColorSpan[] spans =
        highlighted.getSpans(0, highlighted.length(), BackgroundColorSpan.class);
    check(
        "Search result highlighting uses original Unicode offsets",
        spans.length == 1
            && highlighted
                .subSequence(highlighted.getSpanStart(spans[0]), highlighted.getSpanEnd(spans[0]))
                .toString()
                .equals("TARGET"));
    check("Full-text search has a fixed 300-result bound", app.search("river").size() == 300);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      synchronized (source) {
        Future<ArrayList<ReaderApp.Match>> result =
            executor.submit(() -> new BookSearch().find(source, "TARGET"));
        check(
            "Search owns a separate document and cannot wait on the reading document lock",
            result.get(4, TimeUnit.SECONDS).size() == 1);
      }
      Future<Boolean> cancelled =
          executor.submit(
              () -> {
                Thread.currentThread().interrupt();
                try {
                  new BookSearch().find(source, "river");
                  return false;
                } catch (InterruptedIOException expected) {
                  return true;
                } finally {
                  Thread.interrupted();
                }
              });
      check("Obsolete searches honor interruption", cancelled.get(4, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
    AtomicReference<String> joined = new AtomicReference<>();
    AtomicInteger count = new AtomicInteger();
    AtomicBoolean fit = new AtomicBoolean(true),
        surrogates = new AtomicBoolean(true),
        cache = new AtomicBoolean();
    ui(
        () -> {
          try {
            ReaderApp.Location cursor = new ReaderApp.Location(0, 0);
            StringBuilder all = new StringBuilder();
            ReaderApp.Page first = app.page(cursor, app.topWidth, app.topHeight);
            cache.set(first == app.page(cursor, app.topWidth, app.topHeight));
            while (cursor.offset < text.length() && count.get() < 1000) {
              ReaderApp.Page page = app.page(cursor, app.topWidth, app.topHeight);
              if (page.next.offset <= cursor.offset)
                throw new AssertionError("Page did not advance");
              all.append(page.text);
              fit.set(
                  fit.get()
                      && (page.layout.getHeight() <= app.topHeight
                          || page.layout.getLineCount() == 1));
              surrogates.set(
                  surrogates.get()
                      && (page.text.length() == 0 || !Character.isLowSurrogate(page.text.charAt(0)))
                      && (page.text.length() == 0
                          || !Character.isHighSurrogate(page.text.charAt(page.text.length() - 1))));
              count.incrementAndGet();
              cursor = page.next;
            }
            joined.set(all.toString());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    check(
        "Bounded layout reproduces the complete long chapter without missing or repeated"
            + " characters",
        joined.get().equals(text) && count.get() > 20);
    check("Each bounded page fits the viewport", fit.get());
    check("Page boundaries do not split Unicode surrogate pairs", surrogates.get());
    check("Identical page requests reuse cached layouts", cache.get());
    ui(
        () -> {
          try {
            int emoji = text.indexOf("😀");
            ReaderApp.Page normalized =
                app.page(new ReaderApp.Location(0, emoji + 1), app.topWidth, app.topHeight);
            if (normalized.start.offset != emoji)
              throw new AssertionError("Surrogate anchor not normalized");
            ReaderApp.Page outside =
                app.page(
                    new ReaderApp.Location(999, Integer.MAX_VALUE), app.topWidth, app.topHeight);
            if (outside.start.chapter != 0 || outside.start.offset != text.length())
              throw new AssertionError("Anchor not clamped");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    check("Restored out-of-range and surrogate anchors are normalized safely", true);
    ui(
        () -> {
          app.jump(new ReaderApp.Location(0, 0));
          app.marks = new JSONArray();
          app.addMark(
              "highlight", new ReaderApp.Location(0, 0), 10, "", "", ReaderApp.HIGHLIGHT_COLORS[0]);
        });
    ReaderApp.Page marked = app.topPage;
    ui(
        () -> {
          try {
            app.marks.getJSONObject(0).put("color", ReaderApp.HIGHLIGHT_COLORS[2]);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          app.annotationsChanged();
        });
    boolean blue = false;
    for (BackgroundColorSpan span :
        ((Spanned) app.topPage.text).getSpans(0, 10, BackgroundColorSpan.class))
      blue |= span.getBackgroundColor() == app.highlightColor(ReaderApp.HIGHLIGHT_COLORS[2]);
    check(
        "Editing a highlight invalidates cached decoration even when the mark count is unchanged",
        app.topPage != marked && blue);
    ReaderApp.Page sizeBefore = app.topPage;
    ui(
        () -> {
          app.fontSize = 24;
          app.appearance();
        });
    check(
        "Typography changes invalidate cached layouts while preserving the anchor",
        app.topPage != sizeBefore && app.position.equals(new ReaderApp.Location(0, 0)));
    ui(
        () -> {
          app.fontSize = 18;
          app.marks = new JSONArray();
          app.appearance();
        });
  }

  void landscape() throws Exception {
    check(
        "Both Android activities request landscape orientation",
        app.top.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            && app.bottom.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    check(
        "Android configuration remains landscape on both screens",
        app.top.getResources().getConfiguration().orientation == 2
            && app.bottom.getResources().getConfiguration().orientation == 2);
    ui(
        () -> {
          app.dpadBottom = false;
          app.appearance();
        });
    until(() -> app.controls.root.getRotation() == -90, "opposite grip layout");
    check(
        "Changing grip rotates app content without rotating Android",
        app.top.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            && app.bottom.getDisplay().getRotation() == Surface.ROTATION_0
            && app.controls.root.getHeight() > app.controls.root.getWidth());
    ui(
        () -> {
          app.dpadBottom = true;
          app.appearance();
        });
    until(() -> app.controls.root.getRotation() == 90, "bottom D-pad grip");
    ui(
        () -> {
          app.controls.hideControls();
          PaperView page = app.controls.paper;
          page.passageSelection.begin(
              page.getPaddingLeft() + page.dp(24),
              page.getPaddingTop() + page.getLayout().getLineBaseline(0) - page.dp(4));
        });
    check(
        "Selection toolbar is inside the portrait surface",
        app.controls.paper.selectionActive
            && app.controls.paper.selectionMode.getCustomView().getParent() == app.controls.root);
    ui(
        () ->
            app.controls
                .paper
                .selectionMode
                .getMenu()
                .performIdentifierAction(PaperView.COPY_ACTION, 0));
    check("Copy closes the portrait selection toolbar", !app.controls.paper.selectionActive);
    ui(
        () -> {
          PaperView page = app.controls.paper;
          page.passageSelection.begin(
              page.getPaddingLeft() + page.dp(24),
              page.getPaddingTop() + page.getLayout().getLineBaseline(0) - page.dp(4));
          page.selectionMode.getMenu().performIdentifierAction(PaperView.NOTE_ACTION, 0);
        });
    check("Note opens from the portrait selection toolbar", app.controls.dialog != null);
    ui(() -> app.controls.dialog.dismiss());
  }

  void staleWork(File file) throws Exception {
    String root = Uri.fromFile(fixtures).toString();
    ui(
        () -> {
          app.library();
          app.open(new Library.Entry(Uri.fromFile(file).toString(), "Regression.epub", false));
          app.setRoot(root);
        });
    until(() -> !app.loadingFolder && !app.busy, "cancel open with folder switch");
    SystemClock.sleep(300);
    check(
        "An obsolete book-open task cannot replace the selected folder",
        app.mode.equals("library") && app.folder.equals(root));
    ui(
        () -> {
          app.loadFolder(Uri.fromFile(new File(fixtures, "missing")).toString());
          app.loadFolder(root);
        });
    until(() -> !app.loadingFolder, "last folder wins");
    check(
        "Rapid folder switches publish only the latest result",
        app.folder.equals(root)
            && !app.entries.isEmpty()
            && !app.status.startsWith("Folder unavailable"));
  }

  @Override
  public void onStart() {
    boolean passed = false;
    String failure = "";
    try {
      app = (ReaderApp) getTargetContext().getApplicationContext();
      getTargetContext()
          .startActivity(
              new Intent(getTargetContext(), MainActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
      until(
          () -> app.ready && app.top != null && app.bottom != null && !app.busy && !app.restoring,
          "both screens");
      fixtures = new File(getTargetContext().getCacheDir(), "robustness-fixtures");
      fixtures.mkdirs();
      identities();
      cacheGuards();
      settings();
      File file = fixture();
      layoutAndSearch(file);
      landscape();
      staleWork(file);
      passed = true;
    } catch (Throwable error) {
      failure = error.toString();
      StringWriter trace = new StringWriter();
      error.printStackTrace(new PrintWriter(trace));
      Bundle b = new Bundle();
      b.putString("stream", trace.toString());
      sendStatus(-1, b);
    }
    try {
      JSONObject report =
          new JSONObject().put("passed", passed).put("failure", failure).put("checks", checks);
      try (Writer out =
          new OutputStreamWriter(
              new FileOutputStream(
                  new File(getTargetContext().getFilesDir(), "robustness-verification.json")),
              "UTF-8")) {
        out.write(report.toString(2));
      }
    } catch (Exception ignored) {
    }
    Bundle result = new Bundle();
    result.putString(
        "stream",
        passed
            ? "All " + checks.length() + " robustness checks passed.\n"
            : "Robustness checks failed: " + failure + "\n");
    finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }
}
