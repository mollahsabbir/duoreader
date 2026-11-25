package io.duoreader;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;

/** Converts one reading size to equal physical text heights on both panels. */
public final class DisplayTypography {
  private final ReaderApp app;
  private float upperPpi = 160, lowerPpi = 160, referenceScale = 1;

  DisplayTypography(ReaderApp app) {
    this.app = app;
  }

  public void refresh() {
    DisplayManager manager = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
    Display upper = manager.getDisplay(Display.DEFAULT_DISPLAY), lower = null;
    for (Display display : manager.getDisplays()) {
      if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
        lower = display;
        break;
      }
    }
    if (upper == null) return;
    if (lower == null) lower = upper;
    DisplayMetrics a = new DisplayMetrics(), b = new DisplayMetrics();
    upper.getRealMetrics(a);
    lower.getRealMetrics(b);
    upperPpi = ppi(a);
    lowerPpi = ppi(b);
    // Duo firmware reports roughly 320 dpi for BOTH panels. Use its physical diagonals
    // only when model AND native resolutions identify these exact panels.
    // https://www.goretroid.com/products/retroid-pocket-duo-handheld
    if (Build.MODEL
            .toLowerCase(java.util.Locale.ROOT)
            .replace('_', ' ')
            .contains("retroid pocket duo")
        && resolution(upper, 1920, 1080)
        && resolution(lower, 1280, 960)) {
      float knownUpper = (float) Math.hypot(1920, 1080) / 5.5f;
      float knownLower = (float) Math.hypot(1280, 960) / 4.5f;
      if (Math.abs(upperPpi / lowerPpi - knownUpper / knownLower) > .04f) {
        upperPpi = knownUpper;
        lowerPpi = knownLower;
      }
    }
    referenceScale =
        app.createDisplayContext(lower).getResources().getDisplayMetrics().scaledDensity;
  }

  private static boolean resolution(Display display, int wide, int narrow) {
    Display.Mode mode = display.getMode();
    return Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight()) == wide
        && Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight()) == narrow;
  }

  private static float ppi(DisplayMetrics metrics) {
    float dpi = (float) Math.sqrt(metrics.xdpi * metrics.ydpi);
    return Float.isNaN(dpi) || dpi < 80 || dpi > 1200 ? metrics.densityDpi : dpi;
  }

  public float ppi(boolean lower) {
    return lower ? lowerPpi : upperPpi;
  }

  public float pixels(boolean lower) {
    int size =
        app.allowDifferentFontSizes
            ? (lower ? app.lowerFontSize : app.upperFontSize)
            : app.fontSize;
    return size * referenceScale * (lower ? 1 : upperPpi / lowerPpi);
  }
}
