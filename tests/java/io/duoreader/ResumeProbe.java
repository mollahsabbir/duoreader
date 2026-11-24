package io.duoreader;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads the restored in-memory book and position, rather than only re-reading a saved file. */
public final class ResumeProbe extends Instrumentation {
  private Bundle arguments;

  @Override
  public void onCreate(Bundle args) {
    arguments = args;
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    try {
      ReaderApp app = (ReaderApp) getTargetContext().getApplicationContext();
      getTargetContext()
          .startActivity(
              new Intent(getTargetContext(), MainActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
      AtomicBoolean ready = new AtomicBoolean();
      long until = SystemClock.uptimeMillis() + 20000;
      while (SystemClock.uptimeMillis() < until) {
        runOnMainSync(
            () ->
                ready.set(
                    app.ready
                        && !app.restoring
                        && !app.busy
                        && app.current != null
                        && app.topPage != null
                        && app.bottom != null));
        if (ready.get()) break;
        SystemClock.sleep(40);
      }
      if (!ready.get()) throw new AssertionError("Reader did not restore a book");
      if ("true".equals(arguments.getString("setupBinding"))) {
        int key = Integer.parseInt(arguments.getString("bindingKey"));
        String id = arguments.getString("bindingAction");
        ReadingBindings.Action selected = null;
        for (ReadingBindings.Action action : ReadingBindings.Action.values())
          if (action.id.equals(id)) selected = action;
        if (selected == null) throw new AssertionError("Unknown test binding");
        final ReadingBindings.Action action = selected;
        runOnMainSync(
            () -> {
              if (!app.bindings.set(key, action))
                throw new AssertionError("Could not save test binding");
            });
      }
      String expectedUri = arguments.getString("uri");
      int chapter = Integer.parseInt(arguments.getString("chapter")),
          offset = Integer.parseInt(arguments.getString("offset"));
      if (!app.current.uri.equals(expectedUri)
          || app.position.chapter != chapter
          || app.position.offset != offset
          || !app.mode.equals("reading"))
        throw new AssertionError(
            "Restored different position: "
                + app.current.uri
                + " "
                + app.position.chapter
                + ":"
                + app.position.offset);
      if (arguments.containsKey("size")
          && app.fontSize != Integer.parseInt(arguments.getString("size")))
        throw new AssertionError("Restored different font size");
      if (arguments.containsKey("paper")
          && app.paper != Integer.parseInt(arguments.getString("paper")))
        throw new AssertionError("Restored different page colors");
      if (arguments.containsKey("bindingKey")
          && !app.bindings
              .get(Integer.parseInt(arguments.getString("bindingKey")))
              .id
              .equals(arguments.getString("bindingAction")))
        throw new AssertionError("Restored different keybinding");
      Bundle result = new Bundle();
      result.putString("stream", "RESUME_OK " + chapter + ":" + offset + "\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      Bundle error = new Bundle();
      error.putString("stream", "RESUME_FAILED " + e + "\n");
      finish(Activity.RESULT_CANCELED, error);
    }
  }
}
