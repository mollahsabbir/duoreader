package io.duoreader;

import android.graphics.Rect;
import android.view.*;
import android.widget.*;

/** Native editing and selection handles, with a toolbar inside the rotated document surface. */
final class EditorSelection {
  private final ReaderUi ui;
  private final NumberedText source;
  private final FrameLayout host;
  private Mode active;
  private final Runnable refresh =
      () -> {
        if (active != null) active.render();
      };

  EditorSelection(ReaderUi ui, NumberedText source, FrameLayout host) {
    this.ui = ui;
    this.source = source;
    this.host = host;
    host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> update());
  }

  ActionMode start(ActionMode.Callback callback) {
    finish();
    Mode mode = new Mode(callback);
    active = mode;
    if (!callback.onCreateActionMode(mode, mode.menu)) {
      active = null;
      source.removeCallbacks(refresh);
      return null;
    }
    mode.invalidate();
    return mode;
  }

  void update() {
    if (active == null) return;
    source.removeCallbacks(refresh);
    source.post(refresh);
  }

  void finish() {
    source.removeCallbacks(refresh);
    if (active != null) active.finish();
  }

  private final class Mode extends ActionMode {
    private final ActionMode.Callback callback;
    private final Menu menu;
    private final LinearLayout toolbar;
    private final Button[] buttons = new Button[3];
    private final int[] ids = {android.R.id.copy, android.R.id.paste, android.R.id.selectAll};
    private final Rect anchor = new Rect();

    Mode(ActionMode.Callback callback) {
      this.callback = callback;
      menu = new PopupMenu(ui.activity, source).getMenu();
      toolbar = ui.row();
      toolbar.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
      toolbar.setElevation(ui.dp(8));
      toolbar.setBackground(ui.shape(ui.surface(), ui.accent(), 1));
      String[] labels = {"Copy", "Paste", "Select all"};
      String[] icons = {"copy", "paste", "select-all"};
      for (int i = 0; i < ids.length; i++) {
        final int id = ids[i];
        Button button = ui.button(labels[i], () -> perform(id));
        button.setContentDescription(labels[i]);
        button.setText(ReaderIcons.title(ui.activity, icons[i], labels[i], ui.ink()));
        button.setFocusable(false);
        buttons[i] = button;
        ui.addWeighted(toolbar, button);
      }
    }

    private void perform(int id) {
      MenuItem item = menu.findItem(id);
      if (active != this || item == null || !item.isEnabled() || !item.isVisible()) return;
      boolean handled =
          id == android.R.id.paste
              ? source.onTextContextMenuItem(android.R.id.pasteAsPlainText)
              : callback.onActionItemClicked(this, item);
      if (handled) {
        if (id == android.R.id.selectAll) invalidate();
        else finish();
      }
    }

    private void render() {
      if (active != this || !source.isShown() || host.getWidth() == 0) return;
      int count = 0;
      for (int i = 0; i < ids.length; i++) {
        MenuItem item = menu.findItem(ids[i]);
        boolean visible = item != null && item.isVisible();
        buttons[i].setVisibility(visible ? View.VISIBLE : View.GONE);
        buttons[i].setEnabled(visible && item.isEnabled());
        if (visible) count++;
      }
      if (count == 0) {
        if (toolbar.getParent() == host) host.removeView(toolbar);
        return;
      }
      if (callback instanceof ActionMode.Callback2)
        ((ActionMode.Callback2) callback).onGetContentRect(this, source, anchor);
      else source.getLocalVisibleRect(anchor);
      int gap = ui.dp(8), height = ui.dp(58);
      int width = Math.max(1, Math.min(host.getWidth() - gap * 2, ui.dp(count * 112 + 8)));
      int above = anchor.top - height - gap;
      int top = above >= gap ? above : anchor.bottom + gap;
      FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
      params.leftMargin =
          Math.max(gap, Math.min(host.getWidth() - width - gap, anchor.centerX() - width / 2));
      params.topMargin = Math.max(gap, Math.min(host.getHeight() - height - gap, top));
      if (toolbar.getParent() == null) host.addView(toolbar, params);
      else {
        FrameLayout.LayoutParams previous = (FrameLayout.LayoutParams) toolbar.getLayoutParams();
        if (previous.width != width
            || previous.height != height
            || previous.leftMargin != params.leftMargin
            || previous.topMargin != params.topMargin) toolbar.setLayoutParams(params);
      }
    }

    @Override
    public void finish() {
      if (active != this) return;
      active = null;
      source.removeCallbacks(refresh);
      if (toolbar.getParent() == host) host.removeView(toolbar);
      callback.onDestroyActionMode(this);
    }

    @Override
    public void invalidate() {
      if (active != this) return;
      callback.onPrepareActionMode(this, menu);
      update();
    }

    @Override
    public void invalidateContentRect() {
      update();
    }

    @Override
    public int getType() {
      return ActionMode.TYPE_FLOATING;
    }

    @Override
    public Menu getMenu() {
      return menu;
    }

    @Override
    public MenuInflater getMenuInflater() {
      return new MenuInflater(ui.activity);
    }

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
      return "Text selection";
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
