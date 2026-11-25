package io.duoreader;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;

/** One clock for both pages. A held drag previews a turn without changing the saved place. */
public final class SpreadTurn {
  private final ReaderApp app;
  public boolean active, interactive;
  public float progress, upperProgress = 1, lowerProgress = 1;
  public int direction, queued;
  public float originY = .5f, tilt;
  public long lastSettleDuration;
  private ReaderApp.Location target;
  private PaperView upper, lower;
  private ValueAnimator animator;

  SpreadTurn(ReaderApp app) {
    this.app = app;
  }

  private boolean prepare(int dir, boolean finger) {
    if (active || app.book == null || app.busy || !app.mode.equals("reading")) return false;
    target = app.turnTarget(dir);
    if (target == null) return false;
    try {
      ReaderApp.Page first = app.page(target, app.topWidth, app.topHeight);
      ReaderApp.Page second =
          app.end(first.next) ? null : app.page(first.next, app.bottomWidth, app.bottomHeight, true);
      upper = app.top == null ? null : app.top.paper;
      lower = app.controls == null ? null : app.controls.paper;
      direction = dir;
      active = true;
      interactive = finger;
      int percent = app.progressPercent(target);
      originY = .5f;
      tilt = 0;
      if (upper != null) upper.prepareTurn(first, percent, app.bookmarkedAt(first.start), dir);
      if (lower != null)
        lower.prepareTurn(second, percent, second != null && app.bookmarkedAt(second.start), dir);
      frame(0);
      if (app.controls != null) app.controls.hideControls();
      return true;
    } catch (Exception e) {
      cancel();
      app.status = "Cannot turn this page: " + e.getMessage();
      return false;
    }
  }

  public void start(int dir) {
    if (prepare(dir, false)) settle(1, true, 400);
  }

  public boolean beginDrag(int dir) {
    return prepare(dir, true);
  }

  public void drag(float amount) {
    if (active && interactive) frame(Math.max(0, Math.min(.5f, amount * .5f)));
  }

  public void drag(float amount, float origin, float angle) {
    originY = Math.max(0, Math.min(1, origin));
    tilt = Math.max(-.16f, Math.min(.16f, angle));
    drag(amount);
  }

  public void release(boolean commit) {
    release(commit, 0);
  }

  public void release(boolean commit, float pixelsPerSecond) {
    if (active && interactive) {
      interactive = false;
      float width = lower == null ? 960 : Math.max(1, lower.getWidth());
      float speed = Math.min(4, Math.abs(pixelsPerSecond) / width);
      long total = Math.round(760 - 540 * Math.min(1, speed / 3));
      settle(commit ? 1 : 0, commit, total);
    }
  }

  private void frame(float value) {
    progress = Math.max(0, Math.min(1, value));
    float first = Math.max(0, Math.min(1, progress * 2)),
        second = Math.max(0, Math.min(1, progress * 2 - 1));
    upperProgress = direction > 0 ? second : first;
    lowerProgress = direction > 0 ? first : second;
    if (upper != null) upper.turnFrame(upperProgress, originY, tilt);
    if (lower != null) lower.turnFrame(lowerProgress, originY, tilt);
  }

  private void settle(float end, boolean commit, long total) {
    lastSettleDuration = Math.max(90, Math.round(total * Math.abs(end - progress)));
    animator = ValueAnimator.ofFloat(progress, end);
    animator.setDuration(lastSettleDuration);
    animator.setInterpolator(new LinearInterpolator());
    animator.addUpdateListener(a -> frame((float) a.getAnimatedValue()));
    animator.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator a) {
            complete(commit);
          }
        });
    animator.start();
  }

  private void complete(boolean commit) {
    int pending = queued;
    queued = 0;
    ReaderApp.Location next = target;
    int dir = direction;
    stopAnimator();
    active = false;
    interactive = false;
    if (upper != null) upper.finishTurn();
    if (lower != null) lower.finishTurn();
    upper = null;
    lower = null;
    if (commit) app.commitTurn(dir, next);
    if (pending != 0) app.main.post(() -> app.turn(pending));
  }

  private void stopAnimator() {
    if (animator != null) {
      animator.removeAllListeners();
      animator.removeAllUpdateListeners();
      animator.cancel();
      animator = null;
    }
  }

  public void cancel() {
    queued = 0;
    if (active) complete(false);
    else stopAnimator();
  }
}
