package io.duoreader;

import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Recycles row views while retaining a stable, adapter-owned dataset snapshot. */
final class BookListAdapter extends BaseAdapter {
  private final ReaderUi ui;
  private List<Library.Entry> items;

  BookListAdapter(ReaderUi ui) {
    this.ui = ui;
    items = new ArrayList<>(ui.app.entries);
  }

  void setEntries(List<Library.Entry> entries) {
    items = new ArrayList<>(entries);
  }

  void refreshVisibleRows(ListView list) {
    if (list == null) return;
    // Covers, titles, progress and selection do not change the dataset. Updating
    // their bound views preserves ListView's pending pointer click and focus.
    int first = list.getFirstVisiblePosition();
    for (int i = 0; i < list.getChildCount(); i++) {
      View child = list.getChildAt(i);
      if (entry(first + i) != null && child.getTag(R.id.book_row_holder) != null)
        getView(first + i, child, list);
    }
  }

  Library.Entry entry(int position) {
    return position >= 0 && position < items.size() ? items.get(position) : null;
  }

  @Override
  public int getCount() {
    return items.size();
  }

  @Override
  public Object getItem(int position) {
    return entry(position);
  }

  @Override
  public long getItemId(int position) {
    Library.Entry entry = entry(position);
    return entry == null ? AdapterView.INVALID_ROW_ID : entry.stableId;
  }

  @Override
  public boolean hasStableIds() {
    return true;
  }

  @Override
  public View getView(int position, View recycled, ViewGroup parent) {
    Library.Entry entry = entry(position);
    if (entry == null) {
      View empty = new View(ui.activity);
      empty.setEnabled(false);
      empty.setLayoutParams(new AbsListView.LayoutParams(-1, 0));
      return empty;
    }
    Holder holder = recycled == null ? null : (Holder) recycled.getTag(R.id.book_row_holder);
    if (holder == null) holder = new Holder();
    ui.app.requestCover(entry);
    holder.root.setTag(entry);
    holder.root.setActivated(ui.app.selected != null && ui.app.selected.uri.equals(entry.uri));
    holder.title.setText(entry.title);
    holder.author.setText(
        entry.folder
            ? "Collection"
            : !entry.error.isEmpty()
                ? "Unreadable EPUB · open for details"
                : entry.author.isEmpty() ? "EPUB" : entry.author);
    holder.progress.setVisibility(entry.folder ? View.GONE : View.VISIBLE);
    if (!entry.folder) {
      int percent = ui.app.prefs.getInt(ui.app.key(entry.uri) + "percent", 0);
      holder.progress.setText(percent == 0 ? "New book" : percent + "% read");
    }
    String symbol =
        entry.folder ? ui.app.collectionIcon(entry.uri) : entry.cover == null ? "book" : "";
    if (holder.bitmap != entry.cover || !holder.symbol.equals(symbol)) {
      holder.bitmap = entry.cover;
      holder.symbol = symbol;
      if (symbol.isEmpty()) holder.art.setImageBitmap(entry.cover);
      else
        holder.art.setImageDrawable(CollectionIcons.icon(symbol, ui.app.libraryTheme(), ui.dp(43)));
    }
    holder.art.setContentDescription(
        entry.folder
            ? CollectionIcons.NAMES[CollectionIcons.index(symbol)] + " collection icon"
            : "Book cover");
    return holder.root;
  }

  private final class Holder {
    final LinearLayout root = ui.row();
    final ImageView art = new ImageView(ui.activity);
    final TextView title = ui.text("", 15, ui.ink());
    final TextView author = ui.text("", 11, ui.muted());
    final TextView progress = ui.text("", 10, ui.accent());
    Bitmap bitmap;
    String symbol = "?";

    Holder() {
      root.setPadding(ui.dp(9), ui.dp(9), ui.dp(12), ui.dp(9));
      root.setMinimumHeight(ui.dp(93));
      root.setBackground(ui.controlBackground());
      art.setScaleType(ImageView.ScaleType.FIT_CENTER);
      art.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
      root.addView(art, new LinearLayout.LayoutParams(ui.dp(49), ui.dp(72)));
      LinearLayout labels = ui.column();
      labels.setPadding(ui.dp(13), 0, ui.dp(8), 0);
      title.setMaxLines(2);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
      author.setMaxLines(1);
      author.setEllipsize(android.text.TextUtils.TruncateAt.END);
      author.setPadding(0, ui.dp(5), 0, 0);
      progress.setPadding(0, ui.dp(5), 0, 0);
      labels.addView(title);
      labels.addView(author);
      labels.addView(progress);
      root.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      root.addView(ui.text("›", 23, ui.muted()), new LinearLayout.LayoutParams(ui.dp(15), -2));
      root.setTag(R.id.book_row_holder, this);
    }
  }
}
