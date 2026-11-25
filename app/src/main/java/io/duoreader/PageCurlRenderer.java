package io.duoreader;

import android.graphics.*;

/** Reusable paths and texture transforms; animation frames allocate no Java drawing objects. */
final class PageCurlRenderer {
  private static final int STRIPS_PER_FACE = 16;
  final PageCurlGeometry geometry = new PageCurlGeometry();
  private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Paint paperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Matrix transform = new Matrix(), lighting = new Matrix();
  private final float[] matrix = new float[9],
      a = new float[32],
      b = new float[32],
      mapped = new float[32];
  private float[] polygon;
  private int count;
  private final Path strip = new Path(), front = new Path(), back = new Path();
  private final LinearGradient frontLight =
      new LinearGradient(
          0,
          0,
          1,
          0,
          new int[] {0x00000000, 0x10000000, 0x55000000},
          new float[] {0, .55f, 1},
          Shader.TileMode.CLAMP);
  private final LinearGradient backLight =
      new LinearGradient(
          0,
          0,
          1,
          0,
          new int[] {0x0a000000, 0x28ffffff, 0x55000000},
          new float[] {0, .58f, 1},
          Shader.TileMode.CLAMP);

  void draw(
      Canvas canvas,
      Bitmap before,
      Bitmap after,
      float progress,
      int direction,
      float origin,
      float tilt,
      int paperColor) {
    bitmapPaint.setAlpha(255);
    if (progress <= 0) {
      canvas.drawBitmap(before, 0, 0, bitmapPaint);
      return;
    }
    canvas.drawBitmap(after, 0, 0, bitmapPaint);
    if (progress >= 1) return;
    geometry.update(before.getWidth(), before.getHeight(), progress, direction, origin, tilt);
    outline(front, Float.NEGATIVE_INFINITY, 0);
    outline(back, 0, Float.POSITIVE_INFINITY);
    texture(canvas, before, Float.NEGATIVE_INFINITY, -geometry.halfBand, false);
    for (int i = 0; i < STRIPS_PER_FACE; i++)
      texture(
          canvas,
          before,
          -geometry.halfBand + geometry.halfBand * i / STRIPS_PER_FACE,
          -geometry.halfBand + geometry.halfBand * (i + 1) / STRIPS_PER_FACE,
          false);
    shade(canvas, front, frontLight);
    // Several translucent silhouettes make a soft shadow without a full-page blur layer.
    paperPaint.setColor(Color.BLACK);
    paperPaint.setAlpha(7);
    for (int layer = 4; layer >= 1; layer--) {
      canvas.save();
      float lift = geometry.radius * .13f * layer;
      canvas.translate(geometry.nx * lift, geometry.ny * lift + lift * .2f);
      canvas.drawPath(back, paperPaint);
      canvas.restore();
    }
    paperPaint.setAlpha(255);
    paperPaint.setColor(paperColor);
    canvas.drawPath(back, paperPaint);
    // Only material past the crease is reflected: right-edge text stays on a right-to-left flap.
    texture(canvas, before, geometry.halfBand, Float.POSITIVE_INFINITY, true);
    for (int i = STRIPS_PER_FACE - 1; i >= 0; i--)
      texture(
          canvas,
          before,
          geometry.halfBand * i / STRIPS_PER_FACE,
          geometry.halfBand * (i + 1) / STRIPS_PER_FACE,
          true);
    shade(canvas, back, backLight);
  }

  private void shade(Canvas canvas, Path surface, LinearGradient gradient) {
    float start = geometry.crease - geometry.halfBand;
    float width = Math.max(.001f, geometry.radius);
    matrix[0] = geometry.nx * width;
    matrix[1] = -geometry.ny;
    matrix[2] = geometry.nx * start;
    matrix[3] = geometry.ny * width;
    matrix[4] = geometry.nx;
    matrix[5] = geometry.ny * start;
    matrix[6] = matrix[7] = 0;
    matrix[8] = 1;
    lighting.setValues(matrix);
    gradient.setLocalMatrix(lighting);
    shadePaint.setShader(gradient);
    canvas.drawPath(surface, shadePaint);
    shadePaint.setShader(null);
  }

  private void texture(Canvas canvas, Bitmap bitmap, float low, float high, boolean reverse) {
    build(low, high);
    if (count < 3) return;
    float slope, intercept;
    if (Float.isInfinite(low) || Float.isInfinite(high)) {
      slope = reverse ? -1 : 1;
      intercept = 0;
    } else {
      slope = (geometry.project(high) - geometry.project(low)) / (high - low);
      intercept = geometry.project(low) - slope * low;
    }
    float delta = slope - 1, nx = geometry.nx, ny = geometry.ny;
    matrix[0] = 1 + delta * nx * nx;
    matrix[1] = delta * nx * ny;
    matrix[3] = matrix[1];
    matrix[4] = 1 + delta * ny * ny;
    float shift = intercept - delta * geometry.crease;
    matrix[2] = nx * shift;
    matrix[5] = ny * shift;
    matrix[6] = matrix[7] = 0;
    matrix[8] = 1;
    transform.setValues(matrix);
    transform.mapPoints(mapped, 0, polygon, 0, count);
    strip.rewind();
    strip.moveTo(mapped[0], mapped[1]);
    for (int i = 1; i < count; i++) strip.lineTo(mapped[i * 2], mapped[i * 2 + 1]);
    strip.close();
    canvas.save();
    canvas.clipPath(strip);
    bitmapPaint.setAlpha(reverse ? 46 : 255);
    canvas.drawBitmap(bitmap, transform, bitmapPaint);
    canvas.restore();
  }

  void outline(Path path, float low, float high) {
    build(low, high);
    path.rewind();
    if (count < 3) return;
    point(path, polygon[0], polygon[1], true);
    for (int i = 0; i < count; i++) {
      int next = (i + 1) % count;
      float x = polygon[i * 2], y = polygon[i * 2 + 1];
      float dx = polygon[next * 2] - x, dy = polygon[next * 2 + 1] - y;
      float d0 = geometry.distance(x, y), change = geometry.nx * dx + geometry.ny * dy;
      if (Math.abs(change) > .00001f) {
        float first = (-geometry.halfBand - d0) / change, last = (geometry.halfBand - d0) / change;
        float begin = Math.max(0, Math.min(first, last)), end = Math.min(1, Math.max(first, last));
        if (end > begin) {
          if (begin > 0) point(path, x + dx * begin, y + dy * begin, false);
          for (int step = 1; step <= STRIPS_PER_FACE; step++) {
            float t = begin + (end - begin) * step / STRIPS_PER_FACE;
            point(path, x + dx * t, y + dy * t, false);
          }
          if (end < 1) point(path, x + dx, y + dy, false);
          continue;
        }
      }
      point(path, x + dx, y + dy, false);
    }
    path.close();
  }

  private void point(Path path, float x, float y, boolean first) {
    float d = geometry.distance(x, y), shift = geometry.project(d) - d;
    x += geometry.nx * shift;
    y += geometry.ny * shift;
    if (first) path.moveTo(x, y);
    else path.lineTo(x, y);
  }

  private void build(float low, float high) {
    a[0] = a[1] = 0;
    a[2] = geometry.width;
    a[3] = 0;
    a[4] = geometry.width;
    a[5] = geometry.height;
    a[6] = 0;
    a[7] = geometry.height;
    count = 4;
    polygon = a;
    if (!Float.isInfinite(low)) {
      count = clip(a, count, b, low, true);
      polygon = b;
    }
    if (!Float.isInfinite(high)) {
      float[] target = polygon == a ? b : a;
      count = clip(polygon, count, target, high, false);
      polygon = target;
    }
  }

  private int clip(float[] input, int size, float[] output, float plane, boolean greater) {
    if (size == 0) return 0;
    int written = 0;
    float ax = input[(size - 1) * 2], ay = input[(size - 1) * 2 + 1];
    float da = geometry.distance(ax, ay) - plane;
    boolean insideA = greater ? da >= 0 : da <= 0;
    for (int i = 0; i < size; i++) {
      float bx = input[i * 2], by = input[i * 2 + 1], db = geometry.distance(bx, by) - plane;
      boolean insideB = greater ? db >= 0 : db <= 0;
      if (insideA != insideB) {
        float t = da / (da - db);
        output[written++] = ax + (bx - ax) * t;
        output[written++] = ay + (by - ay) * t;
      }
      if (insideB) {
        output[written++] = bx;
        output[written++] = by;
      }
      ax = bx;
      ay = by;
      da = db;
      insideA = insideB;
    }
    return written / 2;
  }
}
