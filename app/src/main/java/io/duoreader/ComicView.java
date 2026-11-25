package io.duoreader;

import android.graphics.*;
import android.text.*;
import android.view.*;

/** Fit-to-screen scan with smaller-screen taps/swipes and a matching background. */
final class ComicView extends View {
  private final ReaderApp app;
  private final boolean lower;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private float downX, downY;

  ComicView(android.content.Context context, ReaderApp app, boolean lower) {
    super(context);
    this.app = app;
    this.lower = lower;
  }

  @Override
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    app.comics.dimensions(lower, width, height);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    canvas.drawColor(app.pageColor());
    Bitmap bitmap = app.comics.page(lower);
    if (bitmap != null && !bitmap.isRecycled()) {
      float scale =
          Math.min(
              getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
      float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
      canvas.drawBitmap(
          bitmap,
          null,
          new RectF(
              (getWidth() - width) / 2,
              (getHeight() - height) / 2,
              (getWidth() + width) / 2,
              (getHeight() + height) / 2),
          paint);
    } else {
      paint.setColor(app.ink());
      paint.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
      TextPaint text = new TextPaint(paint);
      text.setTextAlign(Paint.Align.LEFT);
      String message = app.comics.message(lower);
      int width = Math.max(1, getWidth() - 48);
      StaticLayout layout =
          StaticLayout.Builder.obtain(message, 0, message.length(), text, width)
              .setAlignment(Layout.Alignment.ALIGN_CENTER)
              .setMaxLines(5)
              .setEllipsize(TextUtils.TruncateAt.END)
              .build();
      canvas.save();
      canvas.translate(24, (getHeight() - layout.getHeight()) / 2f);
      layout.draw(canvas);
      canvas.restore();
    }
    paint.setColor(app.ink());
    paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
    paint.setTextAlign(Paint.Align.RIGHT);
    canvas.drawText(app.comics.label(lower), getWidth() - 12, getHeight() - 12, paint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!lower) return true;
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      downX = event.getX();
      downY = event.getY();
      return true;
    }
    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
      float delta = event.getX() - downX, dy = event.getY() - downY;
      if (Math.abs(delta) > getWidth() * .12f && Math.abs(delta) > Math.abs(dy))
        app.comics.turn(delta < 0 ? 1 : -1);
      else if (Math.abs(delta) < 20 && Math.abs(dy) < 20 && app.controls != null)
        app.comics.toggleControls();
      return true;
    }
    return true;
  }
}
