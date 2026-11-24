package io.duoreader;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** A Duo held counterclockwise as a book has its physical D-pad at the bottom. */
public final class ReaderInput {
  /** Marker survives in-process forwarding to the other activity or a dialog. */
  public static final class ScreenKey extends KeyEvent {
    ScreenKey(KeyEvent original, int key) {
      super(
          original.getDownTime(),
          original.getEventTime(),
          original.getAction(),
          key,
          original.getRepeatCount(),
          original.getMetaState(),
          original.getDeviceId(),
          original.getScanCode(),
          original.getFlags(),
          InputDevice.SOURCE_DPAD);
    }

    ScreenKey(int action, int key, int repeat) {
      super(
          SystemClock.uptimeMillis(),
          SystemClock.uptimeMillis(),
          action,
          key,
          repeat,
          0,
          -1,
          0,
          0,
          InputDevice.SOURCE_DPAD);
    }
  }

  public static KeyEvent orient(KeyEvent event, boolean dpadBottom) {
    int source = event.getSource();
    if (event instanceof ScreenKey) return event;
    boolean controller =
        event.isFromSource(InputDevice.SOURCE_GAMEPAD)
            || event.isFromSource(InputDevice.SOURCE_JOYSTICK);
    InputDevice device = event.getDevice();
    if (!controller && device != null)
      controller = device.getName().contains("Retroid Pocket Controller");
    if (!controller) return event;
    int key = event.getKeyCode(), mapped = key;
    if (dpadBottom) {
      switch (key) {
        case KeyEvent.KEYCODE_DPAD_UP:
          mapped = KeyEvent.KEYCODE_DPAD_LEFT;
          break;
        case KeyEvent.KEYCODE_DPAD_RIGHT:
          mapped = KeyEvent.KEYCODE_DPAD_UP;
          break;
        case KeyEvent.KEYCODE_DPAD_DOWN:
          mapped = KeyEvent.KEYCODE_DPAD_RIGHT;
          break;
        case KeyEvent.KEYCODE_DPAD_LEFT:
          mapped = KeyEvent.KEYCODE_DPAD_DOWN;
          break;
      }
    } else {
      switch (key) {
        case KeyEvent.KEYCODE_DPAD_UP:
          mapped = KeyEvent.KEYCODE_DPAD_RIGHT;
          break;
        case KeyEvent.KEYCODE_DPAD_RIGHT:
          mapped = KeyEvent.KEYCODE_DPAD_DOWN;
          break;
        case KeyEvent.KEYCODE_DPAD_DOWN:
          mapped = KeyEvent.KEYCODE_DPAD_LEFT;
          break;
        case KeyEvent.KEYCODE_DPAD_LEFT:
          mapped = KeyEvent.KEYCODE_DPAD_UP;
          break;
      }
    }
    if (mapped == key) return event;
    return new ScreenKey(event, mapped);
  }

  public static final class Controller {
    private final ReaderApp app;
    private int pressed, repeats;
    private final Runnable repeat =
        new Runnable() {
          public void run() {
            if (pressed != 0 && app.controls != null) {
              app.controls.routeControllerKey(
                  new ScreenKey(KeyEvent.ACTION_DOWN, pressed, ++repeats));
              app.main.postDelayed(this, 110);
            }
          }
        };

    public Controller(ReaderApp app) {
      this.app = app;
    }

    public boolean motion(MotionEvent event) {
      if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)
          || event.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
      float x = event.getAxisValue(MotionEvent.AXIS_HAT_X),
          y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
      if (Math.abs(x) < .5f && Math.abs(y) < .5f) {
        x = event.getAxisValue(MotionEvent.AXIS_X);
        y = event.getAxisValue(MotionEvent.AXIS_Y);
      }
      int raw =
          Math.abs(x) > .6f
              ? (x < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT)
              : Math.abs(y) > .6f
                  ? (y < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN)
                  : 0;
      int key =
          raw == 0
              ? 0
              : orient(
                      new KeyEvent(
                          0,
                          SystemClock.uptimeMillis(),
                          KeyEvent.ACTION_DOWN,
                          raw,
                          0,
                          0,
                          event.getDeviceId(),
                          0,
                          0,
                          InputDevice.SOURCE_GAMEPAD),
                      app.dpadBottom)
                  .getKeyCode();
      if (key == 0 && pressed == 0) return false;
      if (key != pressed) {
        cancel();
        pressed = key;
        repeats = 0;
        if (key != 0 && app.controls != null) {
          app.controls.routeControllerKey(new ScreenKey(KeyEvent.ACTION_DOWN, key, 0));
          app.main.postDelayed(repeat, 380);
        }
      }
      return true;
    }

    public void cancel() {
      app.main.removeCallbacks(repeat);
      if (pressed != 0 && app.controls != null)
        app.controls.routeControllerKey(new ScreenKey(KeyEvent.ACTION_UP, pressed, 0));
      pressed = 0;
    }
  }
}
