package io.duoreader;

import android.graphics.*;
import android.text.*;
import android.view.Gravity;
import android.widget.EditText;

/** Wrapped, vertically scrolling text with logical line numbers and no system keyboard. */
public final class NumberedText extends EditText {
  private final LineIndex lines = new LineIndex();
  private final Paint gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final ReaderUi ui;
  private int gutter;

  public NumberedText(ReaderUi ui, boolean editable) {
    super(ui.activity);
    this.ui = ui;
    setTextSize(17);
    setTextColor(ui.ink());
    setTypeface(Typeface.MONOSPACE);
    setGravity(Gravity.TOP | Gravity.START);
    setSingleLine(false);
    setHorizontallyScrolling(false);
    setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    setShowSoftInputOnFocus(false);
    setVerticalScrollBarEnabled(true);
    setBackground(ui.shape(ui.surface(), ui.line(), 1));
    if (!editable) {
      setKeyListener(null);
      setFocusable(false);
      setCursorVisible(false);
    }
    addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

          public void onTextChanged(CharSequence text, int start, int before, int count) {
            lines.changed(text, start, before, count);
            updateGutter();
            invalidate();
          }

          public void afterTextChanged(Editable text) {}
        });
    updateGutter();
  }

  private void updateGutter() {
    gutterPaint.setTextSize(getTextSize() * .72f);
    gutterPaint.setTypeface(Typeface.MONOSPACE);
    int width =
        (int) Math.ceil(gutterPaint.measureText(Integer.toString(lines.count()))) + ui.dp(20);
    if (gutter != width) {
      gutter = width;
      setPadding(gutter + ui.dp(8), ui.dp(8), ui.dp(10), ui.dp(8));
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    Layout layout = getLayout();
    if (layout == null) return;
    int top = getScrollY(), bottom = top + getHeight();
    gutterPaint.setColor(ui.surface());
    canvas.drawRect(getScrollX(), top, getScrollX() + gutter, bottom, gutterPaint);
    gutterPaint.setColor(ui.line());
    canvas.drawLine(gutter, top, gutter, bottom, gutterPaint);
    gutterPaint.setColor(ui.muted());
    gutterPaint.setTextAlign(Paint.Align.RIGHT);
    int first = layout.getLineForVertical(Math.max(0, top - getTotalPaddingTop()));
    int last = layout.getLineForVertical(Math.max(0, bottom - getTotalPaddingTop()));
    for (int line = first; line <= last; line++) {
      int offset = layout.getLineStart(line), logical = lines.line(offset);
      if (offset == lines.start(logical))
        canvas.drawText(
            Integer.toString(logical + 1),
            gutter - ui.dp(7),
            getTotalPaddingTop() + layout.getLineBaseline(line),
            gutterPaint);
    }
  }

  public void insert(String text) {
    int a = Math.max(0, getSelectionStart()), b = Math.max(a, getSelectionEnd());
    getText().replace(a, b, text);
    setSelection(Math.min(getText().length(), a + text.length()));
  }

  public void erase() {
    int a = Math.max(0, getSelectionStart()), b = Math.max(a, getSelectionEnd());
    if (a == b && a > 0) a = Character.offsetByCodePoints(getText(), a, -1);
    getText().delete(a, b);
    setSelection(a);
  }

  public void move(int direction) {
    int offset = Math.max(0, getSelectionStart());
    if (direction == -1 && offset > 0) offset = Character.offsetByCodePoints(getText(), offset, -1);
    if (direction == 1 && offset < length())
      offset = Character.offsetByCodePoints(getText(), offset, 1);
    if (Math.abs(direction) == 2) {
      int logical = lines.line(offset), column = offset - lines.start(logical);
      int target = Math.max(0, Math.min(lines.count() - 1, logical + direction / 2));
      offset =
          Math.min(
              lines.start(target) + column,
              target + 1 < lines.count() ? lines.start(target + 1) - 1 : length());
      if (offset > 0 && offset < length() && Character.isLowSurrogate(getText().charAt(offset)))
        offset--;
    }
    setSelection(offset);
    bringPointIntoView(offset);
  }
}
