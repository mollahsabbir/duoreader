package io.duoreader;

import android.graphics.*;
import android.text.*;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

/** Wrapped, vertically scrolling text with logical line numbers and no system keyboard. */
public final class NumberedText extends EditText {
  private final LineIndex lines = new LineIndex();
  private final Paint gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final ReaderUi ui;
  private int gutter;
  private EditorSelection selection;

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
    if (getKeyListener() != null && !hasFocus() && getSelectionStart() >= 0) {
      int caret = Math.min(length(), getSelectionStart()), line = layout.getLineForOffset(caret);
      float x = getTotalPaddingLeft() + layout.getPrimaryHorizontal(caret);
      gutterPaint.setColor(ui.accent());
      gutterPaint.setStrokeWidth(Math.max(2, ui.dp(1)));
      canvas.drawLine(
          x,
          getTotalPaddingTop() + layout.getLineTop(line),
          x,
          getTotalPaddingTop() + layout.getLineBottom(line),
          gutterPaint);
    }
  }

  @Override
  protected void onSelectionChanged(int start, int end) {
    super.onSelectionChanged(start, end);
    if (selection != null) selection.update();
    invalidate();
  }

  void enableEditingSelection(ReaderUi ui, FrameLayout host) {
    selection = new EditorSelection(ui, this, host);
    setHighlightColor(ui.translucent(ui.accent(), 95));
    setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP);
  }

  @Override
  public ActionMode startActionMode(ActionMode.Callback callback, int type) {
    return selection == null ? super.startActionMode(callback, type) : selection.start(callback);
  }

  @Override
  protected void onScrollChanged(int x, int y, int oldX, int oldY) {
    super.onScrollChanged(x, y, oldX, oldY);
    if (selection != null) selection.update();
  }

  @Override
  protected void onVisibilityChanged(View changed, int visibility) {
    super.onVisibilityChanged(changed, visibility);
    if (selection != null && visibility != View.VISIBLE) selection.finish();
  }

  @Override
  protected void onDetachedFromWindow() {
    if (selection != null) selection.finish();
    super.onDetachedFromWindow();
  }

  void limitLines(int maximum, Runnable rejected) {
    InputFilter[] filters = java.util.Arrays.copyOf(getFilters(), getFilters().length + 1);
    filters[filters.length - 1] =
        (source, start, end, target, from, to) -> {
          int count = lines.count();
          for (int i = start; i < end; i++) if (source.charAt(i) == '\n') count++;
          for (int i = from; i < to; i++) if (target.charAt(i) == '\n') count--;
          if (count <= maximum) return null;
          rejected.run();
          return "";
        };
    setFilters(filters);
  }

  public void insert(String text) {
    int start = Math.max(0, getSelectionStart()), end = Math.max(0, getSelectionEnd());
    int a = Math.min(start, end), b = Math.max(start, end), previous = length();
    getText().replace(a, b, text);
    setSelection(a + Math.max(0, length() - (previous - (b - a))));
  }

  public void erase() {
    int start = Math.max(0, getSelectionStart()), end = Math.max(0, getSelectionEnd());
    int a = Math.min(start, end), b = Math.max(start, end);
    if (a == b && a > 0) a = Character.offsetByCodePoints(getText(), a, -1);
    getText().delete(a, b);
    setSelection(a);
  }

  public void move(int direction) {
    Layout layout = getLayout();
    if (layout != null) {
      Editable text = getText();
      if (direction == -1) Selection.moveLeft(text, layout);
      else if (direction == 1) Selection.moveRight(text, layout);
      else if (direction == -2) Selection.moveUp(text, layout);
      else if (direction == 2) Selection.moveDown(text, layout);
      bringPointIntoView(Math.max(0, getSelectionEnd()));
      return;
    }
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
