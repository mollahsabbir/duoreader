package io.duoreader;

import android.graphics.Matrix;
import android.view.View;

/** Device input uses landscape pixels; test gestures use logical portrait content coordinates. */
final class ScreenCoordinates {
  static int[] point(View view, float x, float y) {
    Matrix transform = new Matrix();
    view.transformMatrixToGlobal(transform);
    float[] point = {x, y};
    transform.mapPoints(point);
    return new int[] {Math.round(point[0]), Math.round(point[1])};
  }

  static String gesture(View view, String command) {
    String[] parts = command.split(" ");
    if (parts[0].equals("tap")) {
      int[] point = point(view, Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
      return "tap " + point[0] + " " + point[1];
    }
    if (parts[0].equals("swipe")) {
      int[] start = point(view, Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
      int[] end = point(view, Float.parseFloat(parts[3]), Float.parseFloat(parts[4]));
      return "swipe " + start[0] + " " + start[1] + " " + end[0] + " " + end[1] + " " + parts[5];
    }
    return command;
  }
}
