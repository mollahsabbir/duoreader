package io.duoreader;

import android.app.Activity;
import android.content.Context;
import android.os.PowerManager;
import android.view.Display;
import java.util.HashSet;
import java.util.Set;

/** The reader's two Android tasks minimize together, and return together from either task. */
public final class PairedTasks {
  private final ReaderApp app;
  private final Set<Activity> resumed = new HashSet<>();
  private final Runnable minimizePending = this::minimizeIfStillAway;
  private Activity leaving, externalSource;
  private boolean explicitLeave, externalLaunchHint;
  public boolean minimized;

  PairedTasks(ReaderApp app) {
    this.app = app;
  }

  public void resumed(Activity activity) {
    resumed.add(activity);
    if (leaving == activity) cancelPending();
    minimized = false;
  }

  public void paused(Activity activity) {
    resumed.remove(activity);
  }

  public void launchRequested() {
    minimized = false;
    cancelPending();
  }

  public boolean canRestorePanels() {
    return !app.closing && !minimized && leaving == null && externalSource == null;
  }

  public void beginExternalActivity(Activity source) {
    externalSource = source;
    externalLaunchHint = true;
    cancelPending();
  }

  public void externalActivityReturned() {
    externalSource = null;
    externalLaunchHint = false;
  }

  public void userLeaving(Activity activity) {
    // The folder picker's initial launch is an intentional part of this app flow.
    if (externalSource == activity && externalLaunchHint) {
      externalLaunchHint = false;
      return;
    }
    schedule(activity, true);
  }

  public void stopped(Activity activity) {
    if (externalSource != null) return;
    // A launcher can briefly reparent the primary task before it returns to display 0.
    if (activity == app.top && activity.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY)
      return;
    schedule(activity, false);
  }

  private void schedule(Activity activity, boolean explicit) {
    if (app.closing || minimized || activity.isFinishing() || activity.isChangingConfigurations())
      return;
    PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
    if (power != null && !power.isInteractive()) return;
    if (leaving != null && leaving != activity) return;
    leaving = activity;
    explicitLeave |= explicit;
    app.main.removeCallbacks(minimizePending);
    app.main.postDelayed(minimizePending, 240);
  }

  private void minimizeIfStillAway() {
    Activity source = leaving;
    boolean explicit = explicitLeave;
    cancelPending();
    if (source == null
        || app.closing
        || source.isFinishing()
        || resumed.contains(source)
        || externalSource != null && !explicit) return;
    if (source != app.top && source != app.bottom) return;
    minimized = true;
    app.turns.cancel();
    app.controller.cancel();
    app.save();
    if (app.controls != null) app.controls.hideFeedback();
    if (app.top != null && !app.top.isFinishing()) app.top.moveTaskToBack(true);
    if (app.bottom != null && !app.bottom.isFinishing()) app.bottom.moveTaskToBack(true);
  }

  public void destroyed(Activity activity) {
    resumed.remove(activity);
    if (leaving == activity) cancelPending();
    if (externalSource == activity) externalActivityReturned();
  }

  private void cancelPending() {
    app.main.removeCallbacks(minimizePending);
    leaving = null;
    explicitLeave = false;
  }
}
