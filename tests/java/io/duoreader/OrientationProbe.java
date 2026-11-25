package io.duoreader;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Verify an actual Close/relaunch leaves OS rotation preferences and landscape windows intact. */
public final class OrientationProbe extends Instrumentation {
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

  void until(BooleanSupplier condition, String label) throws Exception {
    long end = SystemClock.uptimeMillis() + 20000;
    while (SystemClock.uptimeMillis() < end) {
      AtomicBoolean ready = new AtomicBoolean();
      runOnMainSync(() -> ready.set(condition.getAsBoolean()));
      if (ready.get()) return;
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

  String setting(String name) {
    return Settings.System.getString(getTargetContext().getContentResolver(), name);
  }

  void launch() {
    getTargetContext()
        .startActivity(
            new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
  }

  @Override
  public void onStart() {
    boolean passed = false;
    String failure = "";
    try {
      app = (ReaderApp) getTargetContext().getApplicationContext();
      String rotation = setting(Settings.System.USER_ROTATION),
          sensor = setting(Settings.System.ACCELEROMETER_ROTATION);
      getContext()
          .startActivity(
              new Intent(getContext(), BackdropPrimary.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
      getContext()
          .startActivity(
              new Intent(getContext(), BackdropSecondary.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              ActivityOptions.makeBasic().setLaunchDisplayId(2).toBundle());
      launch();
      until(
          () -> app.ready && app.top != null && app.bottom != null && !app.busy && !app.restoring,
          "reader startup");
      File fixture = TestBooks.create(getTargetContext());
      ui(
          () -> {
            app.rotation = 90;
            app.appearance();
            app.open(
                new Library.Entry(
                    android.net.Uri.fromFile(fixture).toString(), fixture.getName(), false));
          });
      until(
          () ->
              app.top != null
                  && app.bottom != null
                  && !app.restoring
                  && !app.busy
                  && app.controls.paper != null,
          "resumed reading");
      SystemClock.sleep(500);
      waitForIdleSync();
      check(
          "Both app windows report landscape configuration",
          app.top.getResources().getConfiguration().orientation == 2
              && app.bottom.getResources().getConfiguration().orientation == 2);
      check(
          "Both reading surfaces remain portrait inside landscape windows",
          app.top.paper.getHeight() > app.top.paper.getWidth()
              && app.controls.root.getHeight() > app.controls.root.getWidth());
      ui(
          () -> {
            app.controls.hideControls();
            PaperView paper = app.controls.paper;
            paper.passageSelection.begin(
                paper.getPaddingLeft() + paper.dp(25),
                paper.getPaddingTop() + paper.getLayout().getLineBaseline(0) - paper.dp(5));
          });
      until(
          () ->
              app.controls.paper.selectionActive
                  && app.controls.paper.selectionMode.getCustomView().getWidth() > 0,
          "portrait selection overlay layout");
      ui(
          () -> {
            PaperView paper = app.controls.paper;
            Bitmap bitmap =
                Bitmap.createBitmap(
                    app.controls.root.getWidth(),
                    app.controls.root.getHeight(),
                    Bitmap.Config.ARGB_8888);
            app.controls.root.draw(new Canvas(bitmap));
            try (OutputStream out =
                new FileOutputStream(
                    new File(getTargetContext().getFilesDir(), "portrait-selection.png"))) {
              bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
            bitmap.recycle();
            paper.endSelection();
          });
      ReaderApp.Location anchor = app.position;
      String uri = app.current.uri;
      ui(() -> app.close());
      until(() -> app.top == null && app.bottom == null, "both app tasks closed");
      SystemClock.sleep(300);
      boolean landscape = true;
      for (Display display :
          ((DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE)).getDisplays()) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        landscape &= metrics.widthPixels > metrics.heightPixels;
      }
      check("Closing the reader leaves both OS displays in landscape", landscape);
      check(
          "Close leaves the OS rotation-lock and sensor settings unchanged",
          java.util.Objects.equals(rotation, setting(Settings.System.USER_ROTATION))
              && java.util.Objects.equals(sensor, setting(Settings.System.ACCELEROMETER_ROTATION)));
      launch();
      until(
          () -> app.top != null && app.bottom != null && app.controls.paper != null && !app.busy,
          "reopened screens");
      check(
          "Reopening restores the same book and character on both displays",
          app.current.uri.equals(uri)
              && app.position.equals(anchor)
              && app.top.getDisplay().getDisplayId() == 0
              && app.bottom.getDisplay().getDisplayId() == 2);
      check(
          "Reopening still leaves OS rotation preferences unchanged",
          java.util.Objects.equals(rotation, setting(Settings.System.USER_ROTATION))
              && java.util.Objects.equals(sensor, setting(Settings.System.ACCELEROMETER_ROTATION)));
      passed = true;
    } catch (Throwable error) {
      failure = error.toString();
      Bundle b = new Bundle();
      b.putString("stream", failure + "\n");
      sendStatus(-1, b);
    }
    try {
      JSONObject report =
          new JSONObject().put("passed", passed).put("failure", failure).put("checks", checks);
      try (Writer out =
          new OutputStreamWriter(
              new FileOutputStream(
                  new File(getTargetContext().getFilesDir(), "orientation-verification.json")),
              "UTF-8")) {
        out.write(report.toString(2));
      }
    } catch (Exception ignored) {
    }
    Bundle result = new Bundle();
    result.putString(
        "stream", passed ? "ORIENTATION_OK\n" : "ORIENTATION_FAILED " + failure + "\n");
    finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }
}
