package io.duoreader;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Real pointer taps exercise ListView's touch/selection path, which D-pad navigation bypasses. */
public final class TouchNavigationTests extends Instrumentation {
  ReaderApp app;
  final JSONArray checks = new JSONArray();
  File root;
  String originalRoot;

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
      SystemClock.sleep(50);
    }
    throw new AssertionError(
        "Timeout: "
            + description
            + "; mode="
            + app.mode
            + "; busy="
            + app.busy
            + "; closing="
            + app.closing
            + "; minimized="
            + app.pairedTasks.minimized
            + "; status="
            + app.status
            + "; selected="
            + (app.selected == null ? "none" : app.selected.uri));
  }

  void check(String label, boolean passed) throws Exception {
    checks.put(new JSONObject().put("check", label).put("passed", passed));
    Bundle b = new Bundle();
    b.putString("stream", (passed ? "PASS " : "FAIL ") + label + "\n");
    sendStatus(passed ? 0 : -1, b);
    if (!passed) throw new AssertionError(label);
  }

  void shell(String action) throws Exception {
    try (ParcelFileDescriptor fd = getUiAutomation().executeShellCommand("input -d 2 " + action);
        InputStream in = new FileInputStream(fd.getFileDescriptor())) {
      byte[] buffer = new byte[256];
      while (in.read(buffer) != -1) {}
    }
    waitForIdleSync();
    SystemClock.sleep(75);
  }

  void tap(String name) throws Exception {
    until(() -> !app.loadingFolder, "folder ready");
    final int[] position = {-1}, point = {0, 0};
    ui(
        () -> {
          for (int i = 0; i < app.entries.size(); i++)
            if (app.entries.get(i).name.equals(name)) position[0] = i;
          if (position[0] >= 0) app.controls.list.setSelection(position[0]);
        });
    if (position[0] < 0) throw new AssertionError("Missing row " + name + " in " + app.folder);
    until(
        () ->
            app.controls.list.getChildAt(position[0] - app.controls.list.getFirstVisiblePosition())
                != null,
        "row laid out");
    ui(
        () -> {
          View row =
              app.controls.list.getChildAt(
                  position[0] - app.controls.list.getFirstVisiblePosition());
          int[] screen =
              ScreenCoordinates.point(
                  row,
                  32 * app.controls.activity.getResources().getDisplayMetrics().density,
                  row.getHeight() / 2f);
          point[0] = screen[0];
          point[1] = screen[1];
        });
    shell("tap " + point[0] + " " + point[1]);
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

  void parent() throws Exception {
    String before = app.folder;
    final int[] point = {0, 0};
    ui(
        () -> {
          View button = find(app.controls.root, "‹");
          int[] screen =
              ScreenCoordinates.point(button, button.getWidth() / 2f, button.getHeight() / 2f);
          point[0] = screen[0];
          point[1] = screen[1];
        });
    shell("tap " + point[0] + " " + point[1]);
    until(() -> !app.folder.equals(before) && !app.loadingFolder, "parent folder");
  }

  static void copy(File from, File to) throws Exception {
    try (InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(to)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }
  }

  public void onStart() {
    boolean success = false;
    String failure = "";
    try {
      app = (ReaderApp) getTargetContext().getApplicationContext();
      getTargetContext()
          .startActivity(
              getTargetContext()
                  .getPackageManager()
                  .getLaunchIntentForPackage("io.duoreader")
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
      until(
          () -> app.ready && !app.restoring && app.top != null && app.bottom != null,
          "reader ready");
      originalRoot = app.root;
      root = new File(getTargetContext().getCacheDir(), "touch-navigation-test");
      File empty = new File(root, "A Empty"),
          books = new File(root, "B Books"),
          nested = new File(books, "Nested");
      empty.mkdirs();
      nested.mkdirs();
      copy(TestBooks.create(app), new File(books, "Book.epub"));
      ui(() -> app.setRoot(Uri.fromFile(root).toString()));
      until(() -> !app.loadingFolder && app.entries.size() == 2, "touch fixture");
      tap("B Books");
      until(
          () -> !app.loadingFolder && app.folder.equals(Uri.fromFile(books).toString()),
          "touch opens nonempty collection");
      check(
          "Tapping the second collection's icon survives the empty loading state",
          app.entries.size() == 2 && app.top != null && app.bottom != null);
      parent();
      for (int i = 0; i < 8; i++) {
        tap("A Empty");
        until(
            () -> !app.loadingFolder && app.folder.equals(Uri.fromFile(empty).toString()),
            "empty collection");
        check(
            "Touch opens an empty collection safely · cycle " + (i + 1),
            app.entries.isEmpty() && app.selected == null);
        parent();
        tap("B Books");
        until(
            () -> !app.loadingFolder && app.folder.equals(Uri.fromFile(books).toString()),
            "books collection");
        tap("Nested");
        until(
            () -> !app.loadingFolder && app.folder.equals(Uri.fromFile(nested).toString()),
            "nested collection");
        parent();
        parent();
        check(
            "Touch navigation and two parent transitions stay consistent · cycle " + (i + 1),
            app.folder.equals(Uri.fromFile(root).toString())
                && app.selected.name.equals("B Books"));
      }
      tap("B Books");
      until(
          () -> !app.loadingFolder && app.folder.equals(Uri.fromFile(books).toString()),
          "books collection");
      tap("Book.epub");
      until(() -> app.mode.equals("reading") && !app.busy, "book opened");
      check(
          "Tapping a cover opens its book after collection navigation",
          app.current.name.equals("Book.epub"));
      shell("keyevent 97");
      until(() -> app.mode.equals("library"), "return from book");
      final long heldDown = SystemClock.uptimeMillis();
      final float[] heldPoint = new float[2];
      ui(
          () -> {
            int index = app.selectedIndex();
            ListView list = app.controls.list;
            View row = list.getChildAt(index - list.getFirstVisiblePosition());
            heldPoint[0] = 32 * app.controls.activity.getResources().getDisplayMetrics().density;
            heldPoint[1] = row.getTop() + row.getHeight() / 2f;
            MotionEvent down =
                MotionEvent.obtain(
                    heldDown, heldDown, MotionEvent.ACTION_DOWN, heldPoint[0], heldPoint[1], 0);
            list.dispatchTouchEvent(down);
            down.recycle();
            // These are the same non-structural publishes as arriving covers and previews.
            for (int refresh = 0; refresh < 5; refresh++) app.changed();
          });
      SystemClock.sleep(60);
      ui(
          () -> {
            MotionEvent up =
                MotionEvent.obtain(
                    heldDown,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    heldPoint[0],
                    heldPoint[1],
                    0);
            app.controls.list.dispatchTouchEvent(up);
            up.recycle();
          });
      until(() -> app.mode.equals("reading") && !app.busy, "book tap after metadata refresh");
      check(
          "Cover and preview updates during a held tap preserve its book click",
          app.current.name.equals("Book.epub"));
      shell("keyevent 97");
      until(() -> app.mode.equals("library"), "return after held tap");
      for (int i = 0; i < 4; i++) {
        ui(() -> app.loadFolder(app.folder));
        until(() -> !app.loadingFolder, "refresh after touch");
        check(
            "Refresh after a touched book retains the correct selection · cycle " + (i + 1),
            app.selected.name.equals("Book.epub"));
      }
      final View[] oldRow = {null};
      final AdapterView.OnItemClickListener[] listener = {null};
      final long[] oldId = {0};
      ui(
          () -> {
            oldRow[0] = app.controls.adapter.getView(1, null, app.controls.list);
            listener[0] = app.controls.list.getOnItemClickListener();
            oldId[0] = app.controls.adapter.getItemId(1);
            app.parent();
          });
      until(() -> !app.loadingFolder, "parent before stale tap");
      String before = app.folder;
      ui(() -> listener[0].onItemClick(app.controls.list, oldRow[0], 1, oldId[0]));
      check(
          "An old row's delayed tap cannot open a different folder",
          app.folder.equals(before) && !app.busy && !app.loadingFolder);
      ui(() -> app.setRoot(originalRoot));
      until(() -> !app.loadingFolder, "restore root");
      success = true;
    } catch (Throwable e) {
      failure = e.toString();
      Bundle status = new Bundle();
      StringWriter trace = new StringWriter();
      e.printStackTrace(new PrintWriter(trace));
      status.putString("stream", trace.toString());
      sendStatus(-1, status);
    }
    try {
      JSONObject report =
          new JSONObject().put("passed", success).put("failure", failure).put("checks", checks);
      try (Writer out =
          new OutputStreamWriter(
              new FileOutputStream(
                  new File(getTargetContext().getFilesDir(), "touch-verification.json")),
              "UTF-8")) {
        out.write(report.toString(2));
      }
    } catch (Exception ignored) {
    }
    Bundle result = new Bundle();
    result.putString(
        "stream",
        success
            ? "All " + checks.length() + " touch-navigation checks passed.\n"
            : "Touch-navigation checks failed: " + failure + "\n");
    finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }
}
