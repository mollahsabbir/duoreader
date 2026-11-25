package io.duoreader;

import android.app.Activity;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;

public final class ControlsActivity extends Activity {
  ReaderApp app;
  public ReaderUi ui;
  private PortraitHost orientationHost;
  private static final int IMPORT_FILES = 42;
  private String importRoot, importParent;
  private final Runnable restorePrimary =
      () -> {
        if (!app.pairedTasks.canRestorePanels()
            || isFinishing()
            || app.top != null && app.top.isForeground()) return;
        Intent intent =
            new Intent(this, MainActivity.class)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        startActivity(
            intent,
            android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                .toBundle());
      };
  private final Runnable refresh =
      () -> {
        if (ui != null) ui.refresh();
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    app = (ReaderApp) getApplication();
    app.bottom = this;
    if (state != null) {
      importRoot = state.getString("importRoot");
      importParent = state.getString("importParent");
      if (importParent != null) app.pairedTasks.beginExternalActivity(this);
    }
    windowSettings();
    ui = new ReaderUi(this, app);
    app.controls = ui;
    orientationHost = new PortraitHost(this, ui.root);
    setContentView(orientationHost);
    app.listeners.add(refresh);
    ui.refresh();
  }

  @Override
  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    app.pairedTasks.launchRequested();
    windowSettings();
    getWindow().getDecorView().removeCallbacks(restorePrimary);
    getWindow().getDecorView().postDelayed(restorePrimary, 240);
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

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    event = ReaderInput.orient(event, app.rotation);
    if (ui != null && ui.handleKey(event)) return true;
    return super.dispatchKeyEvent(event);
  }

  @Override
  public boolean dispatchGenericMotionEvent(MotionEvent event) {
    if (app.controller.motion(event)) return true;
    return super.dispatchGenericMotionEvent(event);
  }

  @Override
  public void onBackPressed() {
    ui.back();
  }

  @Override
  public void onPause() {
    app.pairedTasks.paused(this);
    if (ui != null) ui.hideFeedback();
    getWindow().getDecorView().removeCallbacks(restorePrimary);
    app.controller.cancel();
    app.save();
    super.onPause();
  }

  @Override
  public void onResume() {
    super.onResume();
    app.pairedTasks.resumed(this);
    windowSettings();
    getWindow().getDecorView().postDelayed(restorePrimary, 240);
  }

  @Override
  public void onConfigurationChanged(Configuration config) {
    super.onConfigurationChanged(config);
    ui.rebuild();
    windowSettings();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 41 || request == IMPORT_FILES) app.pairedTasks.externalActivityReturned();
    if (request == IMPORT_FILES) {
      String root = importRoot, parent = importParent;
      importRoot = importParent = null;
      if (result == RESULT_OK && data != null && root != null && parent != null) {
        try {
          app.libraryManager.importFiles(root, parent, LibraryImporter.selected(data));
        } catch (Exception error) {
          ui.message("Import", error.getMessage());
        }
      }
      return;
    }
    if (request == 41 && result == RESULT_OK && data != null) {
      Uri tree = data.getData();
      if (tree == null) return;
      try {
        getContentResolver()
            .takePersistableUriPermission(
                tree,
                data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        app.setRoot(tree.toString());
        ui.toast("Default library folder saved");
      } catch (Exception e) {
        ui.message("Folder permission", e.getMessage());
      }
    }
  }

  @Override
  public void onDestroy() {
    app.pairedTasks.destroyed(this);
    app.listeners.remove(refresh);
    if (ui != null) ui.destroy();
    if (app.bottom == this) app.bottom = null;
    if (app.controls == ui) app.controls = null;
    super.onDestroy();
  }

  @Override
  public void onSaveInstanceState(Bundle state) {
    state.putString("importRoot", importRoot);
    state.putString("importParent", importParent);
    super.onSaveInstanceState(state);
  }

  void importFiles() {
    if (app.busy || app.loadingFolder || app.libraryManager.isWorking()) {
      ui.toast("Wait for the current operation");
      return;
    }
    if (ui.dialog != null) ui.dialog.dismiss();
    importRoot = app.root;
    importParent = app.folder;
    Intent picker =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try {
      app.pairedTasks.beginExternalActivity(this);
      startActivityForResult(picker, IMPORT_FILES);
    } catch (Exception error) {
      app.pairedTasks.externalActivityReturned();
      importRoot = importParent = null;
      ui.message("Import", "Android’s file picker could not open: " + error.getMessage());
    }
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
