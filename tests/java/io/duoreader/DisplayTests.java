package io.duoreader;

import android.content.pm.ActivityInfo;
import android.view.*;

public final class DisplayTests extends FeatureTest {
  void exercise() throws Exception {
    int original = app.rotation;
    String originalMode = app.mode;
    boolean separate = app.allowDifferentFontSizes;
    int upperSize = app.upperFontSize, lowerSize = app.lowerFontSize, baseSize = app.fontSize;
    try {
      ui(
          () -> {
            app.allowDifferentFontSizes = false;
            app.appearance();
          });
      float upper = app.textPaint(false).getTextSize(), lower = app.textPaint(true).getTextSize();
      check(
          "Default text has equal physical height",
          Math.abs(upper / app.typography.ppi(false) - lower / app.typography.ppi(true)) < .0001f);
      check(
          "Duo upper panel receives larger pixel text "
              + upper
              + "/"
              + lower
              + " ppi "
              + app.typography.ppi(false)
              + "/"
              + app.typography.ppi(true),
          upper > lower * 1.1f);
      ui(
          () -> {
            app.upperFontSize = 25;
            app.lowerFontSize = 14;
            app.appearance();
          });
      check(
          "Separate sizes have no effect until enabled",
          app.textPaint(false).getTextSize() == upper);
      ui(
          () -> {
            app.allowDifferentFontSizes = true;
            app.appearance();
          });
      check(
          "Enabled separate sizes affect both pages",
          app.textPaint(false).getTextSize() / app.typography.ppi(false)
              > app.textPaint(true).getTextSize() / app.typography.ppi(true) * 1.7f);
      check(
          "Separate sizes and gate persist",
          app.prefs.getBoolean("allowDifferentFontSizes", false)
              && app.prefs.getInt("upperFontSize", 0) == 25
              && app.prefs.getInt("lowerFontSize", 0) == 14);
      ui(
          () -> {
            app.fontSize = 32;
            app.controls.fontStep(1);
          });
      check(
          "Common size controls still adjust separate fonts when the base is at its limit",
          app.fontSize == 32 && app.upperFontSize == 26 && app.lowerFontSize == 15);
      ui(() -> app.library());
      for (int angle : new int[] {0, 90, 180, 270}) {
        ui(
            () -> {
              app.rotation = angle;
              app.appearance();
            });
        until(() -> app.controls.root.getRotation() == angle, "rotation " + angle);
        check(
            "Content follows internal rotation " + angle,
            app.controls.root.getRotation() == angle && app.top.pageHost.getRotation() == angle);
        check(
            "Rotation " + angle + " persists without changing Android landscape windows",
            app.prefs.getInt("rotation", -1) == angle
                && app.top.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                && app.bottom.getRequestedOrientation()
                    == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        boolean swapped = angle % 180 != 0;
        check(
            "Content dimensions follow rotation " + angle,
            swapped == (app.controls.root.getHeight() > app.controls.root.getWidth()));
        if (!swapped)
          check(
              "Sideways library leaves room for its book list at " + angle,
              app.controls.list.getHeight() >= app.controls.dp(140));
        int[] keys = {
          KeyEvent.KEYCODE_DPAD_UP,
          KeyEvent.KEYCODE_DPAD_RIGHT,
          KeyEvent.KEYCODE_DPAD_DOWN,
          KeyEvent.KEYCODE_DPAD_LEFT
        };
        boolean correct = true;
        for (int i = 0; i < keys.length; i++) {
          KeyEvent event =
              new KeyEvent(
                  0, 0, KeyEvent.ACTION_DOWN, keys[i], 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD);
          KeyEvent mapped = ReaderInput.orient(event, angle);
          correct &= mapped.getKeyCode() == keys[(i - angle / 90 + 4) % 4];
          correct &= ReaderInput.orient(mapped, angle).getKeyCode() == mapped.getKeyCode();
        }
        check("Controller directions rotate once at " + angle, correct);
        ui(() -> app.controls.preferences());
        boolean iconsVisible = true;
        for (int choice : new int[] {0, 90, 180, 270}) {
          android.widget.Button button =
              (android.widget.Button)
                  app.controls.findButton(app.controls.dialogSurface, choice + "°");
          android.graphics.drawable.Drawable icon = button.getCompoundDrawables()[0];
          iconsVisible &=
              icon != null && icon.getBounds().width() > 0 && icon.getBounds().height() > 0;
        }
        check("All direction icons have visible bounds at rotation " + angle, iconsVisible);
        ui(() -> app.controls.dialog.dismiss());
      }
    } finally {
      ui(
          () -> {
            if (app.controls.dialog != null) app.controls.dialog.dismiss();
            app.rotation = original;
            app.allowDifferentFontSizes = separate;
            app.upperFontSize = upperSize;
            app.fontSize = baseSize;
            app.lowerFontSize = lowerSize;
            app.appearance();
            if (!originalMode.equals("library")) app.resume();
          });
    }
  }
}
