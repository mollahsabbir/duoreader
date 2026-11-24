package io.duoreader;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.text.*;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.*;

/** A bounded, read-only composition. No scroll view or hidden overflow on the left panel. */
public final class LibraryPreview {
  private final Canvas c;
  private final ReaderApp app;
  private final LibraryTheme t;
  private final float w, h;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

  private LibraryPreview(View view, Canvas canvas, ReaderApp app) {
    this.c = canvas;
    this.app = app;
    t = app.libraryTheme();
    float d = view.getResources().getDisplayMetrics().density;
    w = view.getWidth() / d;
    h = view.getHeight() / d;
    canvas.scale(d, d);
  }

  public static void draw(View view, Canvas canvas, ReaderApp app) {
    canvas.save();
    new LibraryPreview(view, canvas, app).draw();
    canvas.restore();
  }

  private void rect(float x, float y, float width, float height, int color) {
    paint.setColor(color);
    c.drawRoundRect(x, y, x + width, y + height, 7, 7, paint);
  }

  private float text(
      String s, float x, float y, float width, float size, int color, int max, boolean serif) {
    if (width < 1 || max < 1) return 0;
    TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(color);
    p.setTextSize(size);
    p.setTypeface(Typeface.create(serif ? "serif" : "sans-serif", 0));
    StaticLayout l =
        StaticLayout.Builder.obtain(s, 0, s.length(), p, Math.max(1, (int) width))
            .setIncludePad(false)
            .setMaxLines(max)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(4, 1)
            .build();
    c.save();
    c.translate(x, y);
    c.clipRect(0, 0, width, l.getHeight());
    l.draw(c);
    c.restore();
    return l.getHeight();
  }

  private void icon(String id, float x, float y, float size) {
    Drawable d = CollectionIcons.icon(id, t, (int) size);
    d.setBounds((int) x, (int) y, (int) (x + size), (int) (y + size));
    d.draw(c);
  }

  private void draw() {
    c.drawColor(t.shell);
    icon("book", 22, 19, 23);
    text("DUO READER", 53, 23, w - 155, 12, t.ink, 1, false);
    text(
        new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()),
        w - 65,
        23,
        48,
        12,
        t.muted,
        1,
        false);
    rect(18, 61, w - 36, h - 109, t.surface);
    float x = 34, width = w - 68, y = 80;
    Library.Entry entry = app.selected;
    if (entry == null) {
      icon("books", (w - 100) / 2, Math.min(h * .27f, 220), 100);
      text(
          app.loadingFolder ? "Opening your collection…" : "Your next chapter.",
          x,
          h * .48f,
          width,
          26,
          t.ink,
          2,
          true);
      text(
          app.loadingFolder
              ? "Reading books and folders from your library."
              : "Choose a book or collection on the smaller screen. Empty folders stay available"
                  + " for your next books.",
          x,
          h * .60f,
          width,
          14,
          t.muted,
          4,
          false);
      footer();
      return;
    }
    if (entry.folder) {
      icon(app.collectionIcon(entry.uri), (w - 92) / 2, y, 92);
      y += 109;
      y += text(entry.title, x, y, width, 25, t.ink, 2, true) + 14;
      Library.Preview p = app.selectedPreview;
      if (app.previewLoading || p == null) {
        text("Reading collection contents…", x, y, width, 14, t.muted, 2, false);
        footer();
        return;
      }
      if (!p.error.isEmpty()) {
        text(p.error, x, y, width, 14, t.muted, 4, false);
        footer();
        return;
      }
      y +=
          text(
                  p.books
                      + (p.books == 1 ? " book" : " books")
                      + "  ·  "
                      + p.collections
                      + (p.collections == 1 ? " collection" : " collections"),
                  x,
                  y,
                  width,
                  13,
                  t.muted,
                  1,
                  false)
              + 24;
      y += text("IN THIS COLLECTION", x, y, width, 10, t.accent, 1, false) + 15;
      int rows = Math.min(8, Math.max(0, (int) ((h - 105 - y) / 43))),
          shown = Math.min(rows, p.children.size());
      for (int i = 0; i < shown; i++) {
        Library.Entry child = p.children.get(i);
        rect(x, y, width, 36, i % 2 == 0 ? t.soft : t.surface);
        icon(child.folder ? app.collectionIcon(child.uri) : "book", x + 7, y + 6, 24);
        text(child.title, x + 40, y + 9, width - 51, 13, t.ink, 1, false);
        y += 43;
      }
      if (p.children.isEmpty())
        text(
            "This collection is empty. Add EPUBs or subcollections to it, then refresh.",
            x,
            y,
            width,
            14,
            t.muted,
            3,
            false);
      else if (p.children.size() > shown)
        text(
            "+" + (p.children.size() - shown) + " more · open collection to browse",
            x,
            y + 3,
            width,
            12,
            t.muted,
            1,
            false);
    } else {
      Bitmap artwork = entry.heroCover != null ? entry.heroCover : entry.cover;
      float maxH = Math.min(h * .265f, 215), maxW = width - 42, bw = maxH * .67f, bh = maxH;
      if (artwork != null) {
        float fit = Math.min(maxW / artwork.getWidth(), maxH / artwork.getHeight());
        bw = artwork.getWidth() * fit;
        bh = artwork.getHeight() * fit;
      }
      float bx = (w - bw) / 2;
      rect(bx + 3, y + 5, bw, bh, t.deep);
      if (artwork != null) c.drawBitmap(artwork, null, new RectF(bx, y, bx + bw, y + bh), paint);
      else {
        rect(bx, y, bw, bh, t.soft);
        icon("book", (w - 70) / 2, y + Math.max(8, (bh - 70) / 2), 70);
      }
      y += bh + 23;
      y += text(entry.title, x, y, width, 23, t.ink, 2, true) + 10;
      y +=
          text(
                  entry.author.isEmpty() ? "EPUB · Local library" : entry.author,
                  x,
                  y,
                  width,
                  12,
                  t.muted,
                  1,
                  false)
              + 18;
      int progress = app.prefs.getInt(app.key(entry.uri) + "percent", 0);
      rect(x, y, width, 4, t.line);
      if (progress > 0) rect(x, y, width * Math.min(100, progress) / 100f, 4, t.stick);
      y += 14;
      y +=
          text(
                  progress > 0
                      ? "Continue reading · " + progress + "%"
                      : "A new story, ready when you are.",
                  x,
                  y,
                  width,
                  11,
                  t.muted,
                  1,
                  false)
              + 24;
      Library.Preview p = app.selectedPreview;
      String label =
          p == null ? "BOOK PREVIEW" : p.error.isEmpty() ? p.label : "PREVIEW UNAVAILABLE";
      y += text(label, x, y, width, 10, t.accent, 1, false) + 12;
      String excerpt =
          app.previewLoading || p == null
              ? "Preparing a preview…"
              : !p.error.isEmpty() ? p.error : p.text;
      int lines = Math.min(9, Math.max(1, (int) ((h - 82 - y) / 20)));
      text(excerpt, x, y, width, 14, t.muted, lines, false);
    }
    footer();
  }

  private void footer() {
    text(
        t.name.toUpperCase(Locale.ROOT) + "  ·  LOCAL LIBRARY",
        24,
        h - 29,
        w - 142,
        10,
        t.muted,
        1,
        false);
    float x = w - 106;
    for (int color : new int[] {t.a, t.b, t.x, t.y, t.stick}) {
      paint.setColor(color);
      c.drawCircle(x, h - 22, 4, paint);
      x += 16;
    }
  }
}
