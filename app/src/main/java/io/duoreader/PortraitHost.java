package io.duoreader;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Android stays in landscape. Only this app-owned content rotates into the chosen reading grip. */
public final class PortraitHost extends ViewGroup {
  private final ReaderApp app;
  private final int horizontalInset;
  private final int verticalInset;

  public PortraitHost(Context context, View content) {
    this(context, content, 0, 0);
  }

  public PortraitHost(Context context, View content, int horizontalInset, int verticalInset) {
    super(context);
    app = (ReaderApp) context.getApplicationContext();
    this.horizontalInset = horizontalInset;
    this.verticalInset = verticalInset;
    addView(content);
  }

  public void updateGrip() {
    requestLayout();
    invalidate();
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int width = MeasureSpec.getSize(widthSpec);
    int height = MeasureSpec.getSize(heightSpec);
    setMeasuredDimension(width, height);
    if (getChildCount() == 0) return;
    getChildAt(0)
        .measure(
            MeasureSpec.makeMeasureSpec(
                Math.max(1, height - horizontalInset * 2), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(
                Math.max(1, width - verticalInset * 2), MeasureSpec.EXACTLY));
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    if (getChildCount() == 0) return;
    View content = getChildAt(0);
    int width = content.getMeasuredWidth();
    int height = content.getMeasuredHeight();
    int x = (getWidth() - width) / 2;
    int y = (getHeight() - height) / 2;
    content.layout(x, y, x + width, y + height);
    content.setPivotX(width / 2f);
    content.setPivotY(height / 2f);
    content.setRotation(app.dpadBottom ? 90 : -90);
  }
}
