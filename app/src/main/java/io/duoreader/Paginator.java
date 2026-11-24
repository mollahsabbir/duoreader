package io.duoreader;

import android.text.*;
import android.text.style.BackgroundColorSpan;
import android.text.style.UnderlineSpan;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

/** Bounded page layout, reusable layouts, and checkpoints for reverse navigation. */
public final class Paginator {
  private static final int MAX_WINDOW = 65536;
  private final ReaderApp app;
  private final android.util.LruCache<String, ReaderApp.Page> pages =
      new android.util.LruCache<String, ReaderApp.Page>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, ReaderApp.Page page) {
          return Math.max(1, Epub.textBytes((Spanned) page.text) + page.layout.getLineCount() * 96);
        }
      };
  private final TreeSet<ReaderApp.Location> checkpoints =
      new TreeSet<>(
          (a, b) ->
              a.chapter != b.chapter
                  ? Integer.compare(a.chapter, b.chapter)
                  : Integer.compare(a.offset, b.offset));
  private Epub document;
  private String typography = "", decorations = "";
  private JSONArray marks;
  private Pattern query;
  private long markRevision;

  public Paginator(ReaderApp app) {
    this.app = app;
  }

  public void invalidateAnnotations() {
    markRevision++;
  }

  private void configure() {
    String style =
        app.font
            + ":"
            + app.fontSize
            + ":"
            + app.spacing
            + ":"
            + app.getResources().getDisplayMetrics().scaledDensity
            + ":"
            + app.topWidth
            + ":"
            + app.topHeight
            + ":"
            + app.bottomWidth
            + ":"
            + app.bottomHeight;
    if (document != app.book || !typography.equals(style)) {
      document = app.book;
      typography = style;
      pages.evictAll();
      checkpoints.clear();
      decorations = "";
    }
    String decoration =
        app.paper + ":" + app.searchTerm + ":" + markRevision + ":" + app.marks.length();
    if (marks != app.marks || !decorations.equals(decoration)) {
      marks = app.marks;
      decorations = decoration;
      pages.evictAll();
      query =
          app.searchTerm.isEmpty()
              ? null
              : Pattern.compile(
                  Pattern.quote(app.searchTerm), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
  }

  public ReaderApp.Page page(ReaderApp.Location requested, int width, int height)
      throws IOException {
    if (app.book == null) return null;
    configure();
    width = Math.max(40, width);
    height = Math.max(40, height);
    int chapter = Math.max(0, Math.min(app.book.chapters.size() - 1, requested.chapter));
    Spanned source =
        app.book.text(chapter, width, Math.max(60, Math.min(app.topHeight, app.bottomHeight) - 20));
    int start = Math.max(0, Math.min(requested.offset, source.length()));
    if (start > 0
        && start < source.length()
        && Character.isLowSurrogate(source.charAt(start))
        && Character.isHighSurrogate(source.charAt(start - 1))) start--;
    String key = chapter + ":" + start + ":" + width + ":" + height;
    ReaderApp.Page cached = pages.get(key);
    if (cached != null) return cached;
    TextPaint paint = app.textPaint();
    int window =
        Math.min(
            MAX_WINDOW,
            Math.max(
                512,
                (int) Math.ceil(height / Math.max(1, paint.getTextSize()) * 1.3f)
                    * Math.max(
                        8, (int) Math.ceil(width / Math.max(1, paint.getTextSize() * .32f)))));
    SpannableStringBuilder text;
    StaticLayout layout;
    int count;
    while (true) {
      int end = Math.min(source.length(), start + window);
      if (end < source.length()) {
        int boundary = TextUtils.indexOf(source, '\n', end, Math.min(source.length(), end + 1024));
        if (boundary >= 0 && boundary - start < MAX_WINDOW) end = boundary + 1;
        if (end > start && Character.isHighSurrogate(source.charAt(end - 1))) end--;
      }
      text = new SpannableStringBuilder(source, start, end);
      decorate(text, chapter, start);
      layout = layout(text, paint, width);
      int fitting = fittingLines(layout, height);
      count =
          fitting >= layout.getLineCount()
              ? text.length()
              : layout.getLineStart(Math.max(1, fitting));
      if (fitting >= layout.getLineCount() && end < source.length() && window < MAX_WINDOW) {
        window = Math.min(MAX_WINDOW, window * 2);
        continue;
      }
      break;
    }
    if (count <= 0 && text.length() > 0)
      count = Math.min(text.length(), Character.charCount(Character.codePointAt(text, 0)));
    CharSequence visible = text.subSequence(0, count);
    StaticLayout finalLayout = layout(visible, paint, width);
    // A paragraph can reflow slightly when its final page slice ends. Fit that actual slice too.
    while (finalLayout.getHeight() > height && finalLayout.getLineCount() > 1) {
      int fitting = Math.max(1, fittingLines(finalLayout, height));
      int shorter = finalLayout.getLineStart(Math.min(fitting, finalLayout.getLineCount() - 1));
      if (shorter == count) {
        // A retained paragraph newline can create one extra empty line. Keep the newline
        // in the saved character range, but do not allocate a clipped line below the page.
        finalLayout = layout(visible, paint, width, fitting);
        break;
      }
      if (shorter <= 0 || shorter > count) break;
      count = shorter;
      visible = text.subSequence(0, count);
      finalLayout = layout(visible, paint, width);
    }
    ReaderApp.Page result = new ReaderApp.Page();
    result.start = new ReaderApp.Location(chapter, start);
    result.text = visible;
    result.layout = finalLayout;
    result.chapterTitle = app.book.chapters.get(chapter).title;
    int end = start + count;
    result.next =
        end >= source.length() && chapter + 1 < app.book.chapters.size()
            ? new ReaderApp.Location(chapter + 1, 0)
            : new ReaderApp.Location(chapter, end);
    pages.put(key, result);
    return result;
  }

  private StaticLayout layout(CharSequence text, TextPaint paint, int width) {
    return layout(text, paint, width, Integer.MAX_VALUE);
  }

  private StaticLayout layout(CharSequence text, TextPaint paint, int width, int maxLines) {
    return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
        .setMaxLines(maxLines)
        .setIncludePad(false)
        .setLineSpacing(0, app.spacing)
        .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
        .build();
  }

  private static int fittingLines(StaticLayout layout, int height) {
    int low = 0, high = layout.getLineCount();
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (layout.getLineBottom(middle) <= height) low = middle + 1;
      else high = middle;
    }
    return low;
  }

  private void decorate(SpannableStringBuilder text, int chapter, int start) {
    for (int i = 0; i < app.marks.length(); i++) {
      JSONObject mark = app.marks.optJSONObject(i);
      if (mark == null
          || mark.optInt("chapter") != chapter
          || mark.optString("kind").equals("bookmark")) continue;
      int left = Math.max(0, mark.optInt("start") - start);
      int right = Math.min(text.length(), mark.optInt("end") - start);
      if (left >= right) continue;
      text.setSpan(
          new BackgroundColorSpan(
              app.highlightColor(mark.optInt("color", ReaderApp.HIGHLIGHT_COLORS[0]))),
          left,
          right,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      if (!mark.optString("note").isEmpty() || mark.optString("kind").equals("note"))
        text.setSpan(new UnderlineSpan(), left, right, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    if (query != null) {
      java.util.regex.Matcher matches = query.matcher(text);
      while (matches.find())
        text.setSpan(
            new BackgroundColorSpan(app.highlightColor(0xffffd65a)),
            matches.start(),
            matches.end(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
  }

  public void rememberSpread(ReaderApp.Location location) {
    configure();
    checkpoints.add(location);
    if (checkpoints.size() > 2048) checkpoints.pollFirst();
  }

  public ReaderApp.Location previous(ReaderApp.Location anchor) {
    configure();
    ReaderApp.Location cursor = checkpoints.lower(anchor);
    if (cursor == null)
      cursor =
          new ReaderApp.Location(
              anchor.offset > 0 ? anchor.chapter : Math.max(0, anchor.chapter - 1), 0);
    ReaderApp.Location previous = cursor;
    try {
      for (int i = 0; i < 20000; i++) {
        if (cursor.chapter > anchor.chapter
            || cursor.chapter == anchor.chapter && cursor.offset >= anchor.offset) break;
        previous = cursor;
        rememberSpread(cursor);
        ReaderApp.Page first = page(cursor, app.topWidth, app.topHeight);
        if (app.end(first.next)) break;
        ReaderApp.Page second = page(first.next, app.bottomWidth, app.bottomHeight);
        ReaderApp.Location next = second.next;
        if (next.equals(cursor)) break;
        cursor = next;
      }
    } catch (IOException ignored) {
    }
    return previous;
  }

  public void trim() {
    pages.evictAll();
  }
}
