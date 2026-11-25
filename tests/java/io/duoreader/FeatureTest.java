package io.duoreader;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Shared harness for focused development regressions; fixtures never use user books. */
abstract class FeatureTest extends Instrumentation {
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

  void until(BooleanSupplier condition, String description) throws Exception {
    long end = SystemClock.uptimeMillis() + 20000;
    while (SystemClock.uptimeMillis() < end) {
      AtomicBoolean ready = new AtomicBoolean();
      runOnMainSync(() -> ready.set(condition.getAsBoolean()));
      if (ready.get()) return;
      SystemClock.sleep(40);
    }
    throw new AssertionError("Timeout: " + description);
  }

  void check(String label, boolean passed) throws Exception {
    checks.put(new JSONObject().put("check", label).put("passed", passed));
    Bundle status = new Bundle();
    status.putString("stream", (passed ? "PASS " : "FAIL ") + label + "\n");
    sendStatus(passed ? 0 : -1, status);
    if (!passed) throw new AssertionError(label);
  }

  abstract void exercise() throws Exception;

  public void onStart() {
    Throwable failure = null;
    try {
      app = (ReaderApp) getTargetContext().getApplicationContext();
      getTargetContext()
          .startActivity(
              new Intent(getTargetContext(), MainActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
      until(
          () ->
              app.ready
                  && app.top != null
                  && app.bottom != null
                  && !app.busy
                  && !app.restoring
                  && app.top.isForeground()
                  && app.bottom.getWindow().getDecorView().getWindowVisibility()
                      == android.view.View.VISIBLE,
          "both displays");
      exercise();
    } catch (Throwable error) {
      failure = error;
    }
    try (Writer writer =
        new OutputStreamWriter(
            new FileOutputStream(
                new File(getTargetContext().getFilesDir(), getClass().getSimpleName() + ".json")),
            "UTF-8")) {
      writer.write(
          new JSONObject()
              .put("passed", failure == null)
              .put("failure", failure == null ? "" : failure.toString())
              .put("checks", checks)
              .toString(2));
    } catch (Exception error) {
      if (failure == null) failure = error;
    }
    Bundle result = new Bundle();
    if (failure != null) {
      StringWriter trace = new StringWriter();
      failure.printStackTrace(new PrintWriter(trace));
      result.putString("stream", trace.toString());
    } else
      result.putString(
          "stream", getClass().getSimpleName() + "_OK: " + checks.length() + " checks\n");
    finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }
}
