package io.duoreader;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.view.*;
import android.widget.*;
import java.text.BreakIterator;
import java.util.Locale;

/** Selection controls belong to the rotated reading surface, never an OS-oriented popup window. */
final class PassageSelection {
  private final PaperView paper;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private LinearLayout toolbar;
  private SelectionMode mode;
  private int dragging = -1;
  private float gripX, gripY;

  PassageSelection(PaperView paper) {
    this.paper = paper;
  }

  void begin(float x, float y) {
    if (paper.boundPage == null
        || paper.getText().length() == 0
        || paper.getLayout() == null
        || y < paper.bodyY
        || y > paper.bodyY + paper.getLayout().getHeight()) return;
    paper.requestFocus();
    String text = paper.getText().toString();
    int offset = Math.max(0, Math.min(text.length() - 1, paper.getOffsetForPosition(x, y)));
    BreakIterator words = BreakIterator.getWordInstance(Locale.ENGLISH);
    words.setText(text);
    int end = words.following(offset);
    if (end == BreakIterator.DONE) end = text.length();
    int start = words.preceding(end);
    if (start == BreakIterator.DONE) start = 0;
    Selection.setSelection((Spannable) paper.getText(), start, end);
    mode = new SelectionMode();
    paper.selectionMode = mode;
    paper.selectionActive = true;
    if (paper.app.controls != null) paper.app.controls.hideControls();
    showToolbar();
    paper.invalidate();
  }

  void finish() {
    removeToolbar();
    dragging = -1;
    mode = null;
    paper.selectionMode = null;
    paper.selectionActive = false;
    if (paper.getText() instanceof Spannable)
      Selection.removeSelection((Spannable) paper.getText());
    paper.invalidate();
  }

  private float handleX(int offset, boolean end) {
    return paper.getPaddingLeft()
        + paper.getLayout().getPrimaryHorizontal(offset)
        + paper.dp(end ? 7 : -7);
  }

  private float handleY(int offset) {
    return paper.getPaddingTop()
        + paper.getLayout().getLineBottom(paper.getLayout().getLineForOffset(offset))
        + paper.dp(7);
  }

  void draw(Canvas canvas) {
    Layout layout = paper.getLayout();
    if (!paper.selectionActive || layout == null) return;
    paint.setColor(0xff6286ae);
    int[] offsets = {paper.getSelectionStart(), paper.getSelectionEnd()};
    for (int i = 0; i < offsets.length; i++) {
      int offset = offsets[i];
      if (offset < 0 || offset > paper.getText().length()) continue;
      float x = handleX(offset, i == 1), y = handleY(offset);
      canvas.drawCircle(x, y, paper.dp(8), paint);
      paint.setStrokeWidth(paper.dp(2));
      float boundary = paper.getPaddingLeft() + layout.getPrimaryHorizontal(offset);
      canvas.drawLine(boundary, y - paper.dp(7), x, y, paint);
    }
  }

  boolean touch(MotionEvent event) {
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) {
      int start = paper.getSelectionStart(), end = paper.getSelectionEnd();
      float a = distance(event, start, false), b = distance(event, end, true);
      if (Math.min(a, b) > paper.dp(28)) {
        finish();
        return true;
      }
      dragging = a < b ? 0 : 1;
      int offset = dragging == 0 ? start : end;
      Layout layout = paper.getLayout();
      int line = layout.getLineForOffset(offset);
      gripX = event.getX() - paper.getPaddingLeft() - layout.getPrimaryHorizontal(offset);
      gripY =
          event.getY()
              - paper.getPaddingTop()
              - (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f;
      removeToolbar();
    } else if (action == MotionEvent.ACTION_MOVE && dragging >= 0) {
      Layout layout = paper.getLayout();
      int line =
          layout.getLineForVertical(Math.round(event.getY() - paper.getPaddingTop() - gripY));
      int offset =
          layout.getOffsetForHorizontal(line, event.getX() - paper.getPaddingLeft() - gripX);
      int start = paper.getSelectionStart(), end = paper.getSelectionEnd();
      if (dragging == 0) start = Math.max(0, Math.min(end - 1, offset));
      else end = Math.min(paper.getText().length(), Math.max(start + 1, offset));
      Selection.setSelection((Spannable) paper.getText(), start, end);
      paper.invalidate();
    } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
        && dragging >= 0) {
      dragging = -1;
      showToolbar();
    }
    return true;
  }

  private float distance(MotionEvent event, int offset, boolean end) {
    return (float) Math.hypot(event.getX() - handleX(offset, end), event.getY() - handleY(offset));
  }

  private void removeToolbar() {
    if (toolbar != null && toolbar.getParent() instanceof android.view.ViewGroup)
      ((android.view.ViewGroup) toolbar.getParent()).removeView(toolbar);
    toolbar = null;
  }

  private void showToolbar() {
    removeToolbar();
    ReaderUi ui = paper.app.controls;
    if (ui == null || mode == null) return;
    SelectionMode active = mode;
    toolbar = ui.column();
    toolbar.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
    toolbar.setElevation(ui.dp(8));
    toolbar.setBackground(ui.shape(ui.translucent(paper.app.pageColor(), 242), ui.accent(), 1));
    LinearLayout colors = ui.row();
    for (int i = 0; i < ReaderApp.HIGHLIGHT_COLORS.length; i++) {
      final int id = PaperView.COLOR_ACTION + i;
      Button chip = ui.button("●", () -> active.getMenu().performIdentifierAction(id, 0));
      chip.setTextSize(26);
      chip.setTextColor(ReaderApp.HIGHLIGHT_COLORS[i]);
      chip.setContentDescription(ReaderApp.HIGHLIGHT_NAMES[i] + " highlight");
      ui.addWeighted(colors, chip);
    }
    toolbar.addView(colors);
    LinearLayout actions = ui.row();
    String[] labels = {"Note", "Copy", "Define"}, icons = {"note", "copy", "dictionary"};
    int[] ids = {PaperView.NOTE_ACTION, PaperView.COPY_ACTION, PaperView.DEFINE_ACTION};
    for (int i = 0; i < ids.length; i++) {
      final int id = ids[i];
      Button button = ui.button(labels[i], () -> active.getMenu().performIdentifierAction(id, 0));
      button.setText(ReaderIcons.title(ui.activity, icons[i], labels[i], paper.app.ink()));
      ui.addWeighted(actions, button);
    }
    toolbar.addView(actions);
    int width = Math.min(ui.root.getWidth() - ui.dp(16), ui.dp(330)), height = ui.dp(108);
    int line = paper.getLayout().getLineForOffset(paper.getSelectionStart());
    int above = paper.getPaddingTop() + paper.getLayout().getLineTop(line) - height - ui.dp(20);
    int below = Math.round(handleY(paper.getSelectionEnd())) + ui.dp(25);
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
    params.leftMargin = Math.max(ui.dp(8), (ui.root.getWidth() - width) / 2);
    params.topMargin =
        Math.max(
            ui.dp(4),
            Math.min(ui.root.getHeight() - height - ui.dp(4), above >= ui.dp(4) ? above : below));
    ui.root.addView(toolbar, params);
  }

  private final class SelectionMode extends ActionMode {
    private final Menu menu;

    SelectionMode() {
      menu = new PopupMenu(paper.getContext(), paper).getMenu();
      paper.selectionMenu(menu);
      for (int i = 0; i < menu.size(); i++)
        menu.getItem(i)
            .setOnMenuItemClickListener(item -> paper.selectionAction(this, item.getItemId()));
    }

    @Override
    public void finish() {
      if (mode == this) PassageSelection.this.finish();
    }

    @Override
    public Menu getMenu() {
      return menu;
    }

    @Override
    public MenuInflater getMenuInflater() {
      return new MenuInflater(paper.getContext());
    }

    @Override
    public void invalidate() {}

    @Override
    public void setTitle(CharSequence title) {}

    @Override
    public void setTitle(int title) {}

    @Override
    public void setSubtitle(CharSequence subtitle) {}

    @Override
    public void setSubtitle(int subtitle) {}

    @Override
    public void setCustomView(View view) {}

    @Override
    public CharSequence getTitle() {
      return "Selected passage";
    }

    @Override
    public CharSequence getSubtitle() {
      return "";
    }

    @Override
    public View getCustomView() {
      return toolbar;
    }
  }
}
