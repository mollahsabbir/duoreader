package io.duoreader;

import android.graphics.Bitmap;
import android.view.View;

/** Reuse two snapshots during consecutive turns, then release them after a short idle period. */
final class PageTurnBuffers {
  private final View owner;
  private final Runnable release = this::release;
  private Bitmap before, after;
  private boolean inUse;

  PageTurnBuffers(View owner) {
    this.owner = owner;
  }

  void acquire(int width, int height) {
    owner.removeCallbacks(release);
    if (before != null && (before.getWidth() != width || before.getHeight() != height)) release();
    if (before == null) {
      before = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
      after = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
    }
    inUse = true;
  }

  Bitmap before() {
    return before;
  }

  Bitmap after() {
    return after;
  }

  void idle() {
    inUse = false;
    owner.removeCallbacks(release);
    owner.postDelayed(release, 1500);
  }

  void release() {
    owner.removeCallbacks(release);
    if (inUse) return;
    if (before != null) before.recycle();
    if (after != null) after.recycle();
    before = null;
    after = null;
  }
}
