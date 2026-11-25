package io.duoreader;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Pointer, focus and task-lifecycle regressions for portrait feedback and paired minimization. */
public final class UiBehaviorTests extends Instrumentation {
  ReaderApp app;
  final JSONArray checks = new JSONArray();

  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  void ui(Runnable action) {
    runOnMainSync(action);
    waitForIdleSync();
  }

  void until(BooleanSupplier predicate, String label) throws Exception {
    long end = SystemClock.uptimeMillis() + 20000;
    while (SystemClock.uptimeMillis() < end) {
      AtomicBoolean result = new AtomicBoolean();
      runOnMainSync(() -> result.set(predicate.getAsBoolean()));
      if (result.get()) return;
      SystemClock.sleep(40);
    }
    throw new AssertionError("Timeout: " + label);
  }

  void check(String label, boolean passed) throws Exception {
    checks.put(new JSONObject().put("check", label).put("passed", passed));
    Bundle b = new Bundle();
    b.putString("stream", (passed ? "PASS " : "FAIL ") + label + "\n");
    sendStatus(passed ? 0 : -1, b);
    if (!passed) throw new AssertionError(label);
  }

  void launch(boolean lower) {
    getTargetContext()
        .startActivity(
            new Intent(getTargetContext(), lower ? ControlsActivity.class : MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            ActivityOptions.makeBasic().setLaunchDisplayId(lower ? 2 : 0).toBundle());
  }

  boolean bothVisible() {
    return app.top != null
        && app.bottom != null
        && app.top.isForeground()
        && app.top.getWindow().getDecorView().getWindowVisibility() == View.VISIBLE
        && app.bottom.getWindow().getDecorView().getWindowVisibility() == View.VISIBLE;
  }

  boolean bothHidden() {
    return app.top != null
        && app.bottom != null
        && !app.top.isForeground()
        && app.top.getWindow().getDecorView().getWindowVisibility() != View.VISIBLE
        && app.bottom.getWindow().getDecorView().getWindowVisibility() != View.VISIBLE;
  }

  void shell(String command) throws Exception {
    try (ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand(command);
        InputStream in = new FileInputStream(descriptor.getFileDescriptor())) {
      byte[] buffer = new byte[256];
      while (in.read(buffer) != -1) {}
    }
    waitForIdleSync();
  }

  void capture(View view, String name) {
    Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
    try (OutputStream out =
        new FileOutputStream(new File(getTargetContext().getFilesDir(), name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    bitmap.recycle();
  }

  boolean aligned(View message, View content) {
    int[] a = ScreenCoordinates.point(message, 0, 0),
        b = ScreenCoordinates.point(message, 100, 0),
        c = ScreenCoordinates.point(content, 0, 0),
        d = ScreenCoordinates.point(content, 100, 0);
    int ax = b[0] - a[0], ay = b[1] - a[1], bx = d[0] - c[0], by = d[1] - c[1];
    return Math.abs(ax * by - ay * bx) < 120 && ax * bx + ay * by > 0;
  }

  void feedback() throws Exception {
    ReaderUi ui = app.controls;
    int height = ui.paper.getHeight(), body = app.bottomHeight;
    ReaderApp.Location anchor = app.position;
    View focus = ui.activity.getCurrentFocus();
    ui(
        () -> {
          ui.hideControls();
          ui.toast("Passage highlighted");
        });
    until(
        () ->
            ui.feedback.message != null
                && ui.feedback.message.getWidth() > 0
                && ui.feedback.message.getAlpha() > .99f,
        "portrait feedback");
    check(
        "Feedback follows the book orientation inside the smaller display",
        ui.feedback.message.getParent() == ui.root && aligned(ui.feedback.message, ui.paper));
    check(
        "Feedback keeps the page geometry, saved anchor and controller focus",
        ui.paper.getHeight() == height
            && app.bottomHeight == body
            && app.position.equals(anchor)
            && ui.activity.getCurrentFocus() == focus);
    ui(() -> capture(ui.root, "feedback-portrait"));
    TextView old = ui.feedback.message;
    ui(() -> ui.toast("Bookmark saved"));
    check(
        "Repeated messages replace the previous overlay",
        old.getParent() == null
            && ui.feedback.message.getText().toString().equals("Bookmark saved"));
    until(
        () -> ui.feedback.message.getWidth() > 0 && ui.feedback.message.getAlpha() > .99f,
        "replacement feedback layout");
    int[] point =
        ScreenCoordinates.point(
            ui.feedback.message,
            ui.feedback.message.getWidth() / 2f,
            ui.feedback.message.getHeight() / 2f);
    shell("input -d 2 tap " + point[0] + " " + point[1]);
    check(
        "A feedback message does not intercept page taps",
        ui.controlsVisible && app.position.equals(anchor));
    ui(
        () -> {
          ui.readerMenu();
          ui.toast("Reading menu feedback");
        });
    until(
        () ->
            ui.feedback.message != null
                && ui.feedback.message.getWidth() > 0
                && ui.feedback.message.getAlpha() > .99f,
        "dialog feedback");
    check(
        "Feedback stays above the current dialog with portrait alignment",
        ui.feedback.message.getParent() == ui.dialogSurface
            && aligned(ui.feedback.message, ui.root));
    ui(() -> capture(ui.dialogSurface, "reading-menu-polish"));
    until(() -> ui.feedback.message == null, "feedback expiry");
    check(
        "Feedback expires while leaving the menu usable",
        ui.dialog != null && ui.dialog.isShowing());
    ui(
        () -> {
          ui.toast("Dismiss this message");
          ui.dialog.dismiss();
        });
    check("Closing a dialog removes its pending feedback", ui.feedback.message == null);
    ui(
        () -> {
          app.rotation = 270;
          app.appearance();
          ui.toast("Opposite grip");
        });
    until(
        () ->
            ui.root.getRotation() == 270
                && ui.feedback.message != null
                && ui.feedback.message.getWidth() > 0
                && ui.feedback.message.getAlpha() > .99f,
        "opposite grip feedback");
    check(
        "Feedback also follows the opposite portrait grip", aligned(ui.feedback.message, ui.paper));
    ui(
        () -> {
          app.rotation = 90;
          app.appearance();
          ui.toast("Library feedback");
          app.library();
        });
    check("Changing between reader and library clears old feedback", ui.feedback.message == null);
    ui(app::resume);
    until(() -> app.controls.paper != null, "reading again");
  }

  void minimize() throws Exception {
    ReaderApp.Location anchor = app.position;
    String book = app.current.uri;
    ui(() -> app.top.moveTaskToBack(true));
    until(() -> app.pairedTasks.minimized && bothHidden(), "upper minimized with lower");
    check("Minimizing the upper task hides both app displays", bothHidden() && !app.closing);
    launch(false);
    until(() -> bothVisible() && !app.pairedTasks.minimized, "both restored from launcher");
    check(
        "Opening the launcher icon restores both tasks without changing the book",
        app.current.uri.equals(book) && app.position.equals(anchor));
    ui(() -> app.bottom.moveTaskToBack(true));
    until(() -> app.pairedTasks.minimized && bothHidden(), "lower minimized with upper");
    check("Minimizing the smaller task also hides the upper display", bothHidden() && !app.closing);
    launch(true);
    until(() -> bothVisible() && !app.pairedTasks.minimized, "both restored from smaller task");
    check(
        "Restoring the smaller task brings back the upper task",
        app.top.getDisplay().getDisplayId() == 0
            && app.bottom.getDisplay().getDisplayId() == 2
            && app.position.equals(anchor));
    shell("input -d 0 keyevent 3");
    until(() -> app.pairedTasks.minimized && bothHidden(), "Home minimizes both");
    check("Android Home minimizes the reader on both displays", bothHidden());
    launch(false);
    until(this::bothVisible, "restore after Home");
    getContext()
        .startActivity(
            new Intent(getContext(), BackdropSecondary.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(2).toBundle());
    until(() -> app.pairedTasks.minimized && bothHidden(), "switch apps on smaller display");
    check("Switching apps on the smaller display backgrounds the upper task", bothHidden());
    launch(false);
    until(this::bothVisible, "restore after switching apps");
    ui(() -> app.controls.chooseFolder());
    until(
        () -> app.bottom.getWindow().getDecorView().getWindowVisibility() != View.VISIBLE,
        "system folder picker");
    SystemClock.sleep(700);
    check(
        "The system folder picker keeps the upper reader visible",
        !app.pairedTasks.minimized
            && app.top.isForeground()
            && app.top.getWindow().getDecorView().getWindowVisibility() == View.VISIBLE);
    for (int attempt = 0;
        attempt < 3 && app.bottom.getWindow().getDecorView().getWindowVisibility() != View.VISIBLE;
        attempt++) {
      shell("input -d 2 keyevent 4");
      SystemClock.sleep(350);
    }
    until(() -> bothVisible() && app.pairedTasks.canRestorePanels(), "cancel folder picker");
    check(
        "Cancelling the folder picker restores normal paired task behavior",
        app.current.uri.equals(book) && app.position.equals(anchor));
    ui(() -> app.bottom.moveTaskToBack(true));
    until(() -> app.pairedTasks.minimized && bothHidden(), "minimize after picker");
    launch(false);
    until(this::bothVisible, "final restore");
    check(
        "Paired minimization still works after returning from a system activity",
        app.position.equals(anchor) && app.lastSaveSuccessful);
  }

  @Override
  public void onStart() {
    boolean passed = false;
    String failure = "";
    try {
      app = (ReaderApp) getTargetContext().getApplicationContext();
      launch(false);
      until(() -> app.ready && bothVisible() && !app.busy && !app.restoring, "both screens");
      Library.Entry sample =
          new Library.Entry(Uri.fromFile(TestBooks.create(app)).toString(), "Fixture.epub", false);
      ui(
          () -> {
            app.font = 0;
            app.fontSize = 18;
            app.paper = 1;
            app.margin = 24;
            app.matchScreens = false;
            app.rotation = 90;
            app.appearance();
            app.open(sample);
          });
      until(
          () ->
              !app.busy
                  && app.current != null
                  && app.current.uri.equals(sample.uri)
                  && app.controls.paper != null,
          "sample book");
      SystemClock.sleep(500);
      waitForIdleSync();
      feedback();
      minimize();
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
                  new File(getTargetContext().getFilesDir(), "ui-behavior-verification.json")),
              "UTF-8")) {
        out.write(report.toString(2));
      }
    } catch (Exception ignored) {
    }
    Bundle result = new Bundle();
    result.putString(
        "stream",
        passed
            ? "All " + checks.length() + " UI behavior checks passed.\n"
            : "UI checks failed: " + failure + "\n");
    finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }
}
