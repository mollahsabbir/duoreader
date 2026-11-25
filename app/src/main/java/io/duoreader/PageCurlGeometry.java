package io.duoreader;

/** A sheet rolls around a moving cylinder, then reflects onto its reverse side. */
final class PageCurlGeometry {
  float width, height, nx, ny, crease, radius, halfBand;

  void update(float width, float height, float progress, int direction, float origin, float tilt) {
    this.width = width;
    this.height = height;
    float p = Math.max(0, Math.min(1, progress));
    float bias = Math.max(-1, Math.min(1, (origin - .5f) * 2));
    float angle = (bias * .60f - Math.max(-.16f, Math.min(.16f, tilt)) * 1.3f) * (1 - p);
    float x = (float) Math.cos(angle), y = (float) Math.sin(angle);
    float outer = x * width + Math.max(0, y * height), inner = Math.min(0, y * height);
    float plane = outer - p * (outer - inner);
    nx = direction > 0 ? x : -x;
    ny = y;
    crease = direction > 0 ? plane : plane - x * width;
    radius =
        Math.max(
            .001f,
            Math.min(
                width * .075f * (float) Math.sin(Math.PI * p),
                Math.min(p, 1 - p) * (outer - inner) * .25f));
    halfBand = radius * (float) Math.PI / 2;
  }

  float distance(float x, float y) {
    return nx * x + ny * y - crease;
  }

  float project(float distance) {
    if (distance <= -halfBand) return distance;
    if (distance >= halfBand) return -distance;
    return -halfBand + radius * (float) Math.sin((distance + halfBand) / radius);
  }
}
