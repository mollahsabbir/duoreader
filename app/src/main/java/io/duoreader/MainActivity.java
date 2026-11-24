package io.duoreader;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.FrameLayout;

public final class MainActivity extends Activity {
  public ReaderApp app;
  public PaperView paper;
  FrameLayout pageHost;
  private PortraitHost orientationHost;
  private ReaderUi fallback;
  private boolean foreground;
  private int attachAttempts;
  private final Runnable attachLower = this::attachBottom;
  private final Runnable refresh =
      () -> {
        applyViewport();
        if (paper != null) paper.appearance();
        if (fallback != null) fallback.refresh();
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    app = (ReaderApp) getApplication();
    boolean reopen = app.closing;
    app.closing = false;
    app.top = this;
    if (reopen && app.ready && app.mode.equals("library")) app.loadFolder(app.folder);
    windowSettings();
    Display[] displays = ((DisplayManager) getSystemService(DISPLAY_SERVICE)).getDisplays();
    ensurePrimaryDisplay(getIntent(), displays);
    if (displays.length > 1) {
      paper = new PaperView(this, false);
      pageHost = new FrameLayout(this);
      pageHost.addView(paper, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
      pageHost.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyViewport());
      orientationHost = new PortraitHost(this, pageHost);
      setContentView(orientationHost);
    } else {
      fallback = new ReaderUi(this, app);
      app.controls = fallback;
      orientationHost = new PortraitHost(this, fallback.root);
      setContentView(orientationHost);
    }
    app.listeners.add(refresh);
    refresh.run();
  }

  public void windowSettings() {
    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    if (orientationHost != null) orientationHost.updateGrip();
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    if (app.awake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    WindowManager.LayoutParams p = getWindow().getAttributes();
    p.screenBrightness = app.brightness;
    getWindow().setAttributes(p);
  }

  public void applyViewport() {
    if (pageHost == null || pageHost.getWidth() == 0) return;
    int width = -1, height = -1;
    if (app.matchScreens && app.mode.equals("reading")) {
      DisplayMetrics own = new DisplayMetrics(), other = new DisplayMetrics();
      getDisplay().getRealMetrics(own);
      for (Display d : ((DisplayManager) getSystemService(DISPLAY_SERVICE)).getDisplays())
        if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
          d.getRealMetrics(other);
          break;
        }
      if (other.widthPixels > 0) {
        float referenceWidth =
            Math.min(other.widthPixels, other.heightPixels) * own.xdpi / Math.max(1, other.xdpi);
        float referenceHeight =
            Math.max(other.widthPixels, other.heightPixels) * own.ydpi / Math.max(1, other.ydpi);
        float fit =
            Math.min(pageHost.getWidth() / referenceWidth, pageHost.getHeight() / referenceHeight);
        width = Math.min(pageHost.getWidth(), Math.round(referenceWidth * fit));
        height = Math.min(pageHost.getHeight(), Math.round(referenceHeight * fit));
      }
    }
    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) paper.getLayoutParams();
    if (p.width != width || p.height != height) {
      p.width = width;
      p.height = height;
      p.gravity = Gravity.CENTER;
      paper.setLayoutParams(p);
    }
    pageHost.setBackgroundColor(
        !app.mode.equals("reading")
            ? app.libraryTheme().shell
            : app.matchScreens ? 0xff111514 : app.pageColor());
  }

  public boolean isForeground() {
    return foreground && !isFinishing();
  }

  private void ensurePrimaryDisplay(Intent incoming, Display[] displays) {
    if (displays.length > 1
        && getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY
        && !incoming.getBooleanExtra("primaryRelay", false)) {
      Intent primary =
          new Intent(this, MainActivity.class)
              .addFlags(
                  Intent.FLAG_ACTIVITY_NEW_TASK
                      | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                      | Intent.FLAG_ACTIVITY_NO_USER_ACTION)
              .putExtra("primaryRelay", true);
      startActivity(
          primary,
          ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    app.pairedTasks.launchRequested();
    app.closing = false;
    windowSettings();
    ensurePrimaryDisplay(
        intent, ((DisplayManager) getSystemService(DISPLAY_SERVICE)).getDisplays());
    attachAttempts = 0;
    getWindow().getDecorView().removeCallbacks(attachLower);
    getWindow().getDecorView().postDelayed(attachLower, 180);
  }

  @Override
  public void onResume() {
    super.onResume();
    foreground = true;
    app.pairedTasks.resumed(this);
    app.closing = false;
    app.resumeIfPending();
    attachAttempts = 0;
    windowSettings();
    getWindow().getDecorView().removeCallbacks(attachLower);
    getWindow().getDecorView().postDelayed(attachLower, 180);
  }

  private void attachBottom() {
    if (!foreground || isFinishing() || !app.pairedTasks.canRestorePanels() || fallback != null)
      return;
    // Android may reparent an existing singleTask after onNewIntent has returned.
    // Check again after that move, rather than trusting the display observed in onNewIntent.
    if (getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) {
      ensurePrimaryDisplay(
          new Intent(), ((DisplayManager) getSystemService(DISPLAY_SERVICE)).getDisplays());
      if (++attachAttempts < 4) getWindow().getDecorView().postDelayed(attachLower, 240);
      return;
    }
    for (Display display : ((DisplayManager) getSystemService(DISPLAY_SERVICE)).getDisplays())
      if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
        try {
          Intent intent =
              new Intent(this, ControlsActivity.class)
                  .addFlags(
                      Intent.FLAG_ACTIVITY_NEW_TASK
                          | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                          | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                          | Intent.FLAG_ACTIVITY_SINGLE_TOP);
          ActivityOptions opts =
              ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId());
          startActivity(intent, opts.toBundle());
        } catch (Exception e) {
          app.status = "Cannot open the lower display: " + e.getMessage();
          app.changed();
        }
        return;
      }
    if (++attachAttempts < 4) getWindow().getDecorView().postDelayed(attachLower, 400);
  }

  @Override
  public boolean dispatchTouchEvent(android.view.MotionEvent event) {
    return fallback == null || super.dispatchTouchEvent(event);
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    event = ReaderInput.orient(event, app.dpadBottom);
    if (app.controls != null && app.controls.handleKey(event)) return true;
    if (app.bottom != null) return app.bottom.getWindow().superDispatchKeyEvent(event);
    return super.dispatchKeyEvent(event);
  }

  @Override
  public boolean dispatchGenericMotionEvent(MotionEvent event) {
    if (app.controller.motion(event)) return true;
    return super.dispatchGenericMotionEvent(event);
  }

  @Override
  public void onBackPressed() {
    if (app.controls != null) app.controls.back();
  }

  @Override
  public void onConfigurationChanged(Configuration config) {
    super.onConfigurationChanged(config);
    if (paper != null) paper.requestLayout();
    windowSettings();
  }

  @Override
  public void onPause() {
    app.pairedTasks.paused(this);
    if (fallback != null) fallback.hideFeedback();
    foreground = false;
    getWindow().getDecorView().removeCallbacks(attachLower);
    app.save();
    super.onPause();
  }

  @Override
  public void onDestroy() {
    app.pairedTasks.destroyed(this);
    app.listeners.remove(refresh);
    if (app.top == this) app.top = null;
    if (fallback != null) {
      fallback.destroy();
      if (app.controls == fallback) app.controls = null;
    }
    super.onDestroy();
  }

  @Override
  public void onUserLeaveHint() {
    super.onUserLeaveHint();
    app.pairedTasks.userLeaving(this);
  }

  @Override
  public void onStop() {
    super.onStop();
    app.pairedTasks.stopped(this);
  }
}
