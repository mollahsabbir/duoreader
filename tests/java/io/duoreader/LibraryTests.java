package io.duoreader;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import org.json.*;

/** End-to-end library, preview, customization, and dual-screen launch scenarios on the Duo. */
public final class LibraryTests extends Instrumentation {
  ReaderApp app;
  JSONArray checks = new JSONArray();
  File fixture;
  String originalRoot;
  boolean hadIconSet;
  int originalIconSet;

  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  void ui(Runnable action) {
    runOnMainSync(action);
    waitForIdleSync();
  }

  void until(BooleanSupplier condition, String description) throws Exception {
    long end = SystemClock.uptimeMillis() + 20000;
    while (SystemClock.uptimeMillis() < end) {
      AtomicBoolean okay = new AtomicBoolean();
      runOnMainSync(() -> okay.set(condition.getAsBoolean()));
      if (okay.get()) return;
      SystemClock.sleep(60);
    }
    throw new AssertionError("Timeout: " + description);
  }

  void check(String label, boolean pass) throws Exception {
    checks.put(new JSONObject().put("check", label).put("passed", pass));
    Bundle b = new Bundle();
    b.putString("stream", (pass ? "PASS " : "FAIL ") + label + "\n");
    sendStatus(pass ? 0 : -1, b);
    if (!pass) throw new AssertionError(label);
  }

  void press(int key) {
    try (ParcelFileDescriptor fd =
            getUiAutomation()
                .executeShellCommand(
                    "input -d " + app.bottom.getDisplay().getDisplayId() + " keyevent " + key);
        InputStream in = new FileInputStream(fd.getFileDescriptor())) {
      byte[] b = new byte[256];
      while (in.read(b) != -1) {}
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    waitForIdleSync();
    SystemClock.sleep(100);
  }

  void launch() {
    getTargetContext()
        .startActivity(
            getTargetContext()
                .getPackageManager()
                .getLaunchIntentForPackage("io.duoreader")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
  }

  View find(View view, String label) {
    if (view instanceof TextView && ((TextView) view).getText().toString().equals(label))
      return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View found = find(((ViewGroup) view).getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  GridView grid(View view) {
    if (view instanceof GridView) return (GridView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        GridView found = grid(((ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  void select(String name) throws Exception {
    until(() -> !app.loadingFolder, "folder ready");
    ui(
        () -> {
          for (int i = 0; i < app.entries.size(); i++)
            if (app.entries.get(i).name.equals(name)) {
              app.controls.list.setSelection(i);
              app.controls.preview(app.entries.get(i));
              return;
            }
          throw new AssertionError("Missing entry " + name);
        });
  }

  void capture(String name, View view) throws Exception {
    Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
    ui(() -> view.draw(new Canvas(image)));
    try (OutputStream out =
        new FileOutputStream(new File(getTargetContext().getFilesDir(), name + ".png"))) {
      image.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    image.recycle();
  }

  static void part(ZipOutputStream zip, String path, String text) throws Exception {
    zip.putNextEntry(new ZipEntry(path));
    zip.write(text.getBytes("UTF-8"));
    zip.closeEntry();
  }

  void book(File target, String description) throws Exception {
    StringBuilder prose = new StringBuilder();
    for (int i = 0; i < 100; i++)
      prose.append(
          "The river carried a small boat through the quiet valley. Mira watched the bright birds"
              + " return to the trees. ");
    try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(target))) {
      part(z, "mimetype", "application/epub+zip");
      part(
          z,
          "META-INF/container.xml",
          "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>");
      part(
          z,
          "book.opf",
          "<package xmlns='http://www.idpf.org/2007/opf'"
              + " xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>A very long"
              + " collection testing book title that wraps over several lines and must finish with"
              + " an ellipsis rather than collide with nearby information</dc:title><dc:creator>A"
              + " writer with a deliberately lengthy name</dc:creator><dc:description>"
              + description
              + "</dc:description></metadata><manifest><item id='front' href='copyright.xhtml'"
              + " media-type='application/xhtml+xml'/><item id='main' href='one.xhtml'"
              + " media-type='application/xhtml+xml'/><item id='nav' href='toc.ncx'"
              + " media-type='application/x-dtbncx+xml'/></manifest><spine toc='nav'><itemref"
              + " idref='front'/><itemref idref='main'/></spine></package>");
      part(
          z,
          "toc.ncx",
          "<ncx><navMap><navPoint><navLabel><text>Chapter 1. At the river</text></navLabel><content"
              + " src='one.xhtml'/></navPoint></navMap></ncx>");
      part(
          z,
          "copyright.xhtml",
          "<html><body><h1>Copyright</h1><p>Publisher boilerplate should not become the"
              + " excerpt.</p></body></html>");
      part(
          z,
          "one.xhtml",
          "<html><body><h1>Chapter 1. At the river</h1><p>" + prose + "</p></body></html>");
    }
  }

  public void onStart() {
    boolean passed = false;
    String error = "";
    try {
      app = (ReaderApp) getTargetContext().getApplicationContext();
      launch();
      until(
          () -> app.ready && app.top != null && app.bottom != null && !app.restoring,
          "both displays");
      originalRoot = app.root;
      hadIconSet = app.collectionPrefs.contains("iconSet");
      originalIconSet = app.collectionPrefs.getInt("iconSet", 0);
      fixture = new File(getTargetContext().getCacheDir(), "library-v05-test");
      fixture.mkdirs();
      File empty = new File(fixture, "A Empty"), collection = new File(fixture, "B Stories");
      empty.mkdirs();
      collection.mkdirs();
      book(new File(collection, "Opening.epub"), "");
      book(
          new File(fixture, "Description.epub"),
          "A publisher description with useful book details that is long enough to serve as the"
              + " preferred preview.");
      book(new File(fixture, "Opening.epub"), "");
      new FileOutputStream(new File(fixture, "ignore.txt")).close();
      ui(() -> app.setRoot(Uri.fromFile(fixture).toString()));
      until(() -> !app.loadingFolder && app.entries.size() == 4, "fixture library");
      check(
          "Collections precede books and unsupported files are hidden",
          app.entries.get(0).folder && app.entries.get(1).folder && !app.entries.get(2).folder);
      select("A Empty");
      press(KeyEvent.KEYCODE_DPAD_UP);
      check(
          "Up at the first item stays in the book list",
          app.selectedIndex() == 0 && app.controls.list.hasFocus());
      press(KeyEvent.KEYCODE_DPAD_DOWN);
      check("Down browses collections", app.selected.name.equals("B Stories"));
      press(KeyEvent.KEYCODE_DPAD_UP);
      press(KeyEvent.KEYCODE_DPAD_RIGHT);
      until(() -> !app.loadingFolder, "empty collection");
      check(
          "Right opens an empty collection without leaving the browser",
          app.entries.isEmpty() && app.folder.endsWith("A%20Empty") && app.mode.equals("library"));
      int depth = app.folders.size();
      press(KeyEvent.KEYCODE_DPAD_RIGHT);
      press(KeyEvent.KEYCODE_DPAD_DOWN);
      check(
          "Right and Down are safe with no items",
          app.folders.size() == depth && app.selected == null);
      press(KeyEvent.KEYCODE_DPAD_LEFT);
      until(() -> !app.loadingFolder, "parent");
      check(
          "Left closes a collection and restores its selection",
          app.folder.equals(Uri.fromFile(fixture).toString())
              && app.selected.name.equals("A Empty"));
      press(KeyEvent.KEYCODE_DPAD_LEFT);
      check(
          "Left at the library root does not close the app",
          !app.closing && app.top != null && app.folders.isEmpty());
      select("B Stories");
      until(() -> !app.previewLoading && app.selectedPreview != null, "collection preview");
      check(
          "Collection preview lists its books",
          app.selectedPreview.books == 1
              && app.selectedPreview.children.get(0).name.equals("Opening.epub"));
      ui(() -> app.collectionPrefs.edit().putInt("iconSet", 0).commit());
      press(KeyEvent.KEYCODE_BUTTON_Y);
      check(
          "Y opens an icon grid covering the small display",
          app.controls.dialog != null
              && app.controls.dialog.getWindow().getAttributes().width == -1
              && app.controls.dialog.getWindow().getAttributes().height == -1
              && grid(app.controls.dialog.getWindow().getDecorView()).getCount() == 8);
      capture("v05-icon-picker", app.controls.dialog.getWindow().getDecorView());
      press(KeyEvent.KEYCODE_BUTTON_R1);
      check(
          "Shoulder buttons switch matching icon sets",
          find(app.controls.dialog.getWindow().getDecorView(), "Sword") != null);
      press(KeyEvent.KEYCODE_BUTTON_R1);
      press(KeyEvent.KEYCODE_BUTTON_A);
      check(
          "A applies the focused themed icon to this collection",
          app.controls.dialog == null
              && app.collectionIcon(app.selected.uri).equals("tree")
              && app.collectionIcon(Uri.fromFile(empty).toString()).equals("folder"));
      String iconKey = "icon." + Library.id(app.selected.uri);
      check(
          "Collection icon is durably associated with the folder URI",
          app.collectionPrefs.getString(iconKey, "").equals("tree"));
      select("Description.epub");
      until(() -> !app.previewLoading, "description");
      check(
          "Publisher descriptions are preferred when present",
          app.selectedPreview.label.equals("ABOUT THIS BOOK")
              && app.selectedPreview.text.contains("publisher description"));
      select("Opening.epub");
      until(() -> !app.previewLoading, "opening excerpt");
      check(
          "Missing descriptions fall back to chapter one and skip copyright pages",
          app.selectedPreview.label.equals("OPENING EXCERPT")
              && app.selectedPreview.text.contains("The river carried")
              && !app.selectedPreview.text.contains("boilerplate"));
      ui(
          () -> {
            app.controls.preview(app.entries.get(2));
            app.controls.preview(app.entries.get(1));
          });
      until(() -> !app.previewLoading, "fast selection");
      check(
          "Fast browsing cannot display a stale book preview for a collection",
          app.selected.folder && app.selectedPreview.books == 1);
      select("B Stories");
      Library.Entry outside =
          new Library.Entry(
              Uri.fromFile(new File(collection, "Opening.epub")).toString(), "Opening.epub", false);
      ui(() -> app.open(outside));
      until(() -> !app.busy && app.mode.equals("reading"), "book outside current folder");
      ui(() -> app.library());
      until(() -> !app.previewLoading, "returned folder preview");
      check(
          "Returning from Continue restores a valid selection and its own preview",
          app.selected.folder
              && app.selected.name.equals("B Stories")
              && app.selectedPreview.books == 1);
      String paletteSelection = app.selected.uri;
      int oldTheme = app.theme, oldPaper = app.paper;
      Set<Integer> pixels = new HashSet<>();
      for (int theme = 0; theme < 5; theme++) {
        final int choice = theme;
        ui(
            () -> {
              app.theme = choice;
              app.appearance();
            });
        Bitmap image =
            Bitmap.createBitmap(
                app.top.paper.getWidth(), app.top.paper.getHeight(), Bitmap.Config.ARGB_8888);
        ui(() -> app.top.paper.draw(new Canvas(image)));
        pixels.add(image.getPixel(2, 2));
        capture("v05-theme-" + theme, app.controls.root);
        if (theme == 2) capture("v05-collection-preview", app.top.paper);
        check(
            "Library palette " + theme + " colors both displays independently of paper",
            image.getPixel(2, 2) == LibraryTheme.get(theme).shell
                && app.controls.root.getBackground()
                    instanceof android.graphics.drawable.ColorDrawable
                && ((android.graphics.drawable.ColorDrawable) app.controls.root.getBackground())
                        .getColor()
                    == LibraryTheme.get(theme).shell
                && app.paper == oldPaper);
        image.recycle();
      }
      check(
          "Changing hardware themes preserves the selected collection",
          app.selected.uri.equals(paletteSelection));
      check("All five hardware finishes have distinct library shells", pixels.size() == 5);
      ui(
          () -> {
            app.theme = oldTheme;
            app.appearance();
          });
      select("Opening.epub");
      new File(fixture, "Opening.epub").delete();
      ui(() -> app.loadFolder(app.folder));
      until(() -> !app.loadingFolder, "removed book refresh");
      check(
          "Refreshing after a selected book disappears selects the nearest remaining item",
          app.entries.size() == 3 && app.selected.name.equals("Description.epub"));
      book(new File(fixture, "Opening.epub"), "");
      ui(() -> app.loadFolder(app.folder));
      until(() -> !app.loadingFolder, "restored fixture book");
      select("B Stories");
      press(KeyEvent.KEYCODE_DPAD_RIGHT);
      until(() -> !app.loadingFolder, "stories");
      ui(
          () ->
              app.controls.routeControllerKey(
                  new ReaderInput.ScreenKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1)));
      check(
          "Holding Right does not accidentally open a book after entering its collection",
          app.mode.equals("library") && app.folders.size() == 1);
      String inside = app.folder;
      ui(() -> app.close());
      until(() -> app.top == null && app.bottom == null, "close library");
      check("Close dismisses both library screens", app.closing);
      launch();
      until(() -> app.top != null && app.bottom != null && !app.loadingFolder, "reopen library");
      check(
          "Launcher opens both panels and restores the collection",
          app.folder.equals(inside)
              && app.top.getDisplay().getDisplayId() == 0
              && app.bottom.getDisplay().getDisplayId() == 2);
      press(KeyEvent.KEYCODE_DPAD_RIGHT);
      until(() -> app.mode.equals("reading") && !app.busy, "open selected book");
      check("Right on a book opens it directly", app.current.name.equals("Opening.epub"));
      ReaderApp.Location position = app.position;
      ui(() -> app.controls.readerMenu());
      ui(
          () -> {
            View close = find(app.controls.dialog.getWindow().getDecorView(), "Close app");
            if (close == null) throw new AssertionError("No close menu action");
            close.performClick();
          });
      until(() -> app.top == null && app.bottom == null, "close reader");
      check(
          "Closing a book durably saves its anchor",
          app.prefs.getInt(app.bookKey() + "offset", -1) == position.offset
              && app.prefs.getBoolean("reading", false));
      launch();
      until(() -> app.top != null && app.bottom != null, "reopen reader");
      check(
          "Reopening after Close restores both reading screens and the same anchor",
          app.mode.equals("reading") && app.position.equals(position));
      ui(() -> app.bottom.finishAndRemoveTask());
      until(() -> app.bottom == null, "secondary dismissed");
      launch();
      until(() -> app.bottom != null, "warm launch repairs missing secondary");
      check(
          "Warm launcher intents repair a missing second screen",
          app.top.getDisplay().getDisplayId() == 0 && app.bottom.getDisplay().getDisplayId() == 2);
      getTargetContext()
          .startActivity(
              new Intent(getTargetContext(), MainActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              ActivityOptions.makeBasic().setLaunchDisplayId(2).toBundle());
      until(
          () ->
              app.top.getDisplay().getDisplayId() == 0
                  && app.bottom != null
                  && app.bottom.getDisplay().getDisplayId() == 2,
          "launcher intent on secondary display");
      check("Launches directed at the small display still restore the correct two panels", true);
      ui(
          () -> {
            app.collectionPrefs.edit().remove(iconKey).commit();
            app.library();
            app.setRoot(originalRoot);
          });
      until(() -> !app.loadingFolder, "restore library");
      passed = true;
    } catch (Throwable e) {
      error = e.toString();
      StringWriter trace = new StringWriter();
      e.printStackTrace(new PrintWriter(trace));
      Bundle b = new Bundle();
      b.putString("stream", trace.toString());
      sendStatus(-1, b);
    }
    if (app != null) {
      android.content.SharedPreferences.Editor edit = app.collectionPrefs.edit();
      if (hadIconSet) edit.putInt("iconSet", originalIconSet);
      else edit.remove("iconSet");
      if (fixture != null)
        edit.remove("icon." + Library.id(Uri.fromFile(new File(fixture, "B Stories")).toString()));
      edit.commit();
    }
    try {
      JSONObject result =
          new JSONObject().put("passed", passed).put("failure", error).put("checks", checks);
      try (Writer out =
          new OutputStreamWriter(
              new FileOutputStream(
                  new File(getTargetContext().getFilesDir(), "library-verification.json")),
              "UTF-8")) {
        out.write(result.toString(2));
      }
    } catch (Exception ignored) {
    }
    Bundle result = new Bundle();
    result.putString(
        "stream",
        passed
            ? "All " + checks.length() + " library checks passed.\n"
            : "Library checks failed: " + error + "\n");
    finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }
}
