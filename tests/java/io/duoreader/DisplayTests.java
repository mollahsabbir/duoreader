package io.duoreader;

import android.content.pm.ActivityInfo;
import android.view.*;

public final class DisplayTests extends FeatureTest {
  void exercise() throws Exception {
    int original = app.rotation;
    try {
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
      }
    } finally {
      ui(
          () -> {
            app.rotation = original;
            app.appearance();
          });
    }
  }
}
