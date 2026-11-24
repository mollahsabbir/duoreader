package io.duoreader;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Brief feedback stays inside the portrait content, including a foreground dialog. */
final class ReaderFeedback {
  private final ReaderUi ui;
  private final Runnable expire = this::dismiss;
  TextView message;

  ReaderFeedback(ReaderUi ui) {
    this.ui = ui;
  }

  void show(String text, FrameLayout host, boolean inDialog) {
    dismiss();
    if (text == null || text.isEmpty() || ui.app.closing || ui.activity.isFinishing()) return;
    message = ui.text(text, 14, ui.ink());
    message.setGravity(Gravity.CENTER);
    message.setMaxLines(3);
    message.setEllipsize(TextUtils.TruncateAt.END);
    message.setPadding(ui.dp(18), ui.dp(11), ui.dp(18), ui.dp(11));
    message.setBackground(ui.shape(ui.translucent(ui.surface(), 249), ui.accent(), 1));
    message.setElevation(ui.dp(9));
    message.setFocusable(false);
    message.setClickable(false);
    int width = host.getWidth() > 0 ? host.getWidth() : ui.root.getWidth();
    message.setMaxWidth(Math.max(ui.dp(120), width - ui.dp(40)));
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    params.leftMargin = params.rightMargin = ui.dp(20);
    params.bottomMargin =
        inDialog
            ? ui.dp(65)
            : ui.controlsVisible && ui.controlsOverlay != null
                ? Math.max(ui.dp(30), ui.controlsOverlay.getHeight() + ui.dp(8))
                : ui.dp(30);
    host.addView(message, params);
    message.setAlpha(0);
    message.animate().alpha(1).setDuration(120).start();
    ui.app.main.postDelayed(expire, Math.min(4000, Math.max(2200, text.length() * 45L)));
  }

  void dismiss() {
    ui.app.main.removeCallbacks(expire);
    if (message == null) return;
    message.animate().cancel();
    if (message.getParent() instanceof ViewGroup)
      ((ViewGroup) message.getParent()).removeView(message);
    message = null;
  }
}
