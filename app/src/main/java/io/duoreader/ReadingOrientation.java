package io.duoreader;

/** App-owned clockwise rotation, independent of Android's fixed landscape windows. */
public final class ReadingOrientation {
  private ReadingOrientation() {}

  public static int normalize(int degrees) {
    int value = ((degrees % 360) + 360) % 360;
    return value % 90 == 0 ? value : 90;
  }

  public static boolean swapsAxes(int degrees) {
    return normalize(degrees) % 180 != 0;
  }

  public static int direction(int physicalDirection, int degrees) {
    return (physicalDirection - normalize(degrees) / 90 + 4) % 4;
  }
}
