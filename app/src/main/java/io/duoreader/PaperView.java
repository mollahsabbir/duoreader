package io.duoreader;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.TextView;
import java.util.*;

/** Read-only upper page; the lower page also accepts stylus swipes and selection. */
public final class PaperView extends TextView {
  final ReaderApp app;
  final PassageSelection passageSelection = new PassageSelection(this);
  final boolean lower;
  final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  final float scale;
  float left, bodyY, contentHeight;
  Bitmap oldPage, newPage;
  private final PageTurnBuffers turnBuffers = new PageTurnBuffers(this);
  private ReaderApp.Page noteHitPage;
  float turn = 1f;
  int direction = 1;
  public float curlOriginY = .5f, curlTilt;
  final ArrayList<NoteHit> noteHits = new ArrayList<>();

  static final class NoteHit {
    RectF rect;
    int index;

    NoteHit(RectF rect, int index) {
      this.rect = rect;
      this.index = index;
    }
  }

  float touchX, touchY;
  long touchTime;
  VelocityTracker velocity;
  boolean dragging, gestureAttempted, touchBlocked;
  MotionEvent pendingDown;
  final Runnable longPress = this::startNativeSelection;
  int dragDirection;
  ReaderApp.Page boundPage;
  public boolean selectionActive;
  public ActionMode selectionMode;
  public static final int COLOR_ACTION = 0x6d000,
      NOTE_ACTION = 0x6d010,
      COPY_ACTION = 0x6d011,
      DEFINE_ACTION = 0x6d012;

  public PaperView(Context c, boolean bottom) {
    super(c);
    app = (ReaderApp) c.getApplicationContext();
    lower = bottom;
    scale = getResources().getDisplayMetrics().density;
    setFocusable(bottom);
    setFocusableInTouchMode(bottom);
    setDefaultFocusHighlightEnabled(false);
    setContentDescription(
        bottom
            ? "Second page. Swipe to turn; hold a passage to annotate."
            : "First page. Reading display.");
    setTextIsSelectable(bottom);
    setIncludeFontPadding(false);
    setGravity(Gravity.TOP | Gravity.START);
    setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
    setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
    setScrollContainer(false);
    if (bottom) {
      setOnClickListener(
          v -> {
            if (!selectionActive && !app.turns.active && app.controls != null) {
              if (touchX > getWidth() - dp(44) && touchY < dp(43)) {
                app.bookmarkSmallPage();
                return;
              }
              int annotation = annotationAt(touchX, touchY);
              if (annotation >= 0) app.controls.annotation(annotation);
              else app.controls.toggleControls();
            }
          });
    }
  }

  float dp(float n) {
    return n * scale;
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    metrics();
  }

  void metrics() {
    left = dp(app.margin);
    bodyY = dp(49);
    contentHeight = getHeight() - bodyY - dp(30);
    setPadding(Math.round(left), Math.round(bodyY), Math.round(left), Math.round(dp(30)));
    int width = Math.max(40, (int) (getWidth() - 2 * left)),
        height = Math.max(40, (int) contentHeight);
    app.main.post(() -> app.dimensions(lower, width, height));
  }

  public void appearance() {
    metrics();
    if (lower) {
      ReaderApp.Page page = app.bottomPage;
      if (page != boundPage) {
        endSelection();
        boundPage = page;
        setText(page == null ? "" : page.text, BufferType.SPANNABLE);
      }
      setTypeface(app.typeface());
      setTextSize(TypedValue.COMPLEX_UNIT_PX, app.textPaint(true).getTextSize());
      setTextColor(app.ink());
      setHighlightColor(0x668ba6cf);
      setLineSpacing(0, app.spacing);
      setGravity(Gravity.TOP | (app.alignment == 2 ? Gravity.END : Gravity.START));
      setTextAlignment(app.alignment == 2 ? TEXT_ALIGNMENT_VIEW_END : TEXT_ALIGNMENT_VIEW_START);
      setJustificationMode(
          app.alignment == 1
              ? Layout.JUSTIFICATION_MODE_INTER_WORD
              : Layout.JUSTIFICATION_MODE_NONE);
    }
    invalidate();
  }

  void label(Canvas c, String text, float x, float y, float size, int color, Typeface face) {
    paint.setColor(color);
    paint.setTextSize(dp(size));
    paint.setTypeface(face);
    c.drawText(text, x, y, paint);
  }

  String ellipsis(String text, float width, float size) {
    paint.setTextSize(dp(size));
    paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    return TextUtils.ellipsize(
            text, new TextPaint(paint), Math.max(1, width), TextUtils.TruncateAt.END)
        .toString();
  }

  @Override
  protected void onDraw(Canvas c) {
    if (oldPage == null || newPage == null) {
      drawPage(c);
      if (lower && selectionActive) {
        super.onDraw(c);
        passageSelection.draw(c);
      }
      return;
    }
    if (turn <= 0) {
      c.drawBitmap(oldPage, 0, 0, null);
      return;
    }
    c.drawBitmap(newPage, 0, 0, null);
    if (turn >= 1) return;
    float w = getWidth(),
        h = getHeight(),
        edge = w * (1 - turn),
        fold = foldWidth(turn),
        slope = (float) Math.tan(curlTilt * Math.sin(Math.PI * turn));
    float top = edge - h * curlOriginY * slope, bottom = edge + h * (1 - curlOriginY) * slope;
    Path front = new Path();
    front.moveTo(0, 0);
    front.lineTo(top, 0);
    front.lineTo(bottom, h);
    front.lineTo(0, h);
    front.close();
    orientPath(front, w);
    c.save();
    c.clipPath(front);
    c.drawBitmap(oldPage, 0, 0, null);
    c.restore();
    Path shadow = new Path();
    shadow.moveTo(top + fold * .70f, 0);
    shadow.quadTo(edge + fold * 1.1f, h * .5f, bottom + fold * .70f, h);
    shadow.lineTo(bottom + fold + dp(22), h);
    shadow.quadTo(edge + fold + dp(35), h * .5f, top + fold + dp(22), 0);
    shadow.close();
    orientPath(shadow, w);
    float x = direction > 0 ? edge + fold : w - edge - fold;
    paint.setAlpha(255);
    paint.setStyle(Paint.Style.FILL);
    paint.setShader(
        new LinearGradient(
            x - direction * dp(12),
            0,
            x + direction * dp(30),
            0,
            0x55000000,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP));
    c.drawPath(shadow, paint);
    paint.setShader(null);
    Path back = new Path();
    back.moveTo(top, 0);
    back.lineTo(top + fold * .72f, 0);
    back.quadTo(edge + fold * 1.12f, h * .5f, bottom + fold * .72f, h);
    back.lineTo(bottom, h);
    back.close();
    orientPath(back, w);
    c.save();
    c.clipPath(back);
    paint.setColor(app.pageColor());
    c.drawPath(back, paint);
    float m = direction > 0 ? slope : -slope,
        nx = 1 / (float) Math.sqrt(1 + m * m),
        ny = -m * nx,
        px = direction > 0 ? edge : w - edge,
        py = h * curlOriginY,
        d = nx * px + ny * py;
    Matrix reflection = new Matrix();
    reflection.setValues(
        new float[] {
          1 - 2 * nx * nx,
          -2 * nx * ny,
          2 * d * nx,
          -2 * nx * ny,
          1 - 2 * ny * ny,
          2 * d * ny,
          0,
          0,
          1
        });
    paint.setAlpha(38);
    c.drawBitmap(oldPage, reflection, paint);
    paint.setAlpha(255);
    x = direction > 0 ? edge : w - edge;
    paint.setShader(
        new LinearGradient(
            x,
            0,
            x + direction * Math.max(1, fold),
            0,
            new int[] {0x66000000, 0x12ffffff, 0x55ffffff, 0x33000000},
            new float[] {0, .18f, .65f, 1},
            Shader.TileMode.CLAMP));
    c.drawPath(back, paint);
    paint.setShader(null);
    c.restore();
  }

  public float foldWidth(float progress) {
    return Math.max(
        0,
        Math.min(
            getWidth() * progress * .9f, getWidth() * .36f * (float) Math.sin(Math.PI * progress)));
  }

  void orientPath(Path path, float width) {
    if (direction < 0) {
      Matrix mirror = new Matrix();
      mirror.setValues(new float[] {-1, 0, width, 0, 1, 0, 0, 0, 1});
      path.transform(mirror);
    }
  }

  private void drawPage(Canvas c) {
    c.drawColor(app.pageColor());
    if (!lower && (!app.mode.equals("reading") || app.book == null)) {
      drawPreview(c);
      return;
    }
    ReaderApp.Page page = lower ? app.bottomPage : app.topPage;
    drawReadingPage(
        c,
        page,
        app.progressPercent(),
        page != null && app.bookmarkedAt(page.start),
        selectionActive);
  }

  private void drawReadingPage(
      Canvas c, ReaderApp.Page page, int progress, boolean marked, boolean nativeSelection) {
    c.drawColor(app.pageColor());
    int muted = app.paper == 2 ? 0xff9a9e94 : 0xff7a7d70;
    label(
        c,
        ellipsis(app.book == null ? "Duo Reader" : app.book.title, getWidth() - dp(84), 11),
        dp(22),
        dp(24),
        11,
        muted,
        Typeface.create("sans-serif", 0));
    if (marked || lower) {
      paint.setColor(marked ? (app.controls == null ? 0xff71538a : app.controls.accent()) : muted);
      paint.setStyle(marked ? Paint.Style.FILL : Paint.Style.STROKE);
      paint.setStrokeWidth(dp(1));
      Path p = new Path();
      p.moveTo(getWidth() - dp(35), dp(7));
      p.lineTo(getWidth() - dp(23), dp(7));
      p.lineTo(getWidth() - dp(23), dp(30));
      p.lineTo(getWidth() - dp(29), dp(25));
      p.lineTo(getWidth() - dp(35), dp(30));
      p.close();
      c.drawPath(p, paint);
      paint.setStyle(Paint.Style.FILL);
    }
    paint.setColor(app.paper == 2 ? 0xff383d38 : 0xffd9d5c5);
    paint.setStrokeWidth(dp(.6f));
    c.drawLine(dp(22), dp(35), getWidth() - dp(22), dp(35), paint);
    if (page != null && !nativeSelection) {
      c.save();
      c.translate(left, bodyY);
      c.clipRect(0, 0, getWidth() - 2 * left, contentHeight);
      page.layout.draw(c);
      c.restore();
    } else if (page == null)
      label(
          c,
          app.book != null ? "End of book" : "A story awaits.",
          dp(25),
          getHeight() / 2f,
          20,
          muted,
          Typeface.create("serif", 0));
    drawNoteMarkers(c, page);
    String foot = page == null ? "" : page.chapterTitle;
    label(
        c,
        ellipsis(foot, getWidth() - dp(110), 10),
        dp(22),
        getHeight() - dp(12),
        10,
        muted,
        Typeface.create("sans-serif", 0));
    label(
        c,
        (lower ? "II" : "I") + "  ·  " + progress + "%",
        getWidth() - dp(76),
        getHeight() - dp(12),
        10,
        muted,
        Typeface.create("sans-serif", 0));
  }

  void drawNoteMarkers(Canvas canvas, ReaderApp.Page page) {
    noteHits.clear();
    noteHitPage = page;
    if (page == null) return;
    for (int i = 0; i < app.marks.length(); i++) {
      org.json.JSONObject mark = app.marks.optJSONObject(i);
      if (mark == null
          || mark.optInt("chapter") != page.start.chapter
          || mark.optString("note").isEmpty()
          || mark.optInt("end") <= page.start.offset
          || mark.optInt("start") >= page.start.offset + page.text.length()) continue;
      int offset = Math.max(0, mark.optInt("start") - page.start.offset),
          line = page.layout.getLineForOffset(Math.min(offset, page.text.length()));
      float y = bodyY + page.layout.getLineTop(line) + dp(3), x = getWidth() - dp(19);
      android.graphics.drawable.Drawable icon =
          ReaderIcons.icon(
              "note", app.controls == null ? app.ink() : app.controls.accent(), (int) dp(13));
      icon.setBounds((int) x, (int) y, (int) (x + dp(13)), (int) (y + dp(13)));
      icon.draw(canvas);
      noteHits.add(new NoteHit(new RectF(x - dp(9), y - dp(7), x + dp(20), y + dp(22)), i));
    }
  }

  public int annotationAt(float x, float y) {
    if (noteHitPage == app.bottomPage)
      for (NoteHit hit : noteHits) if (hit.rect.contains(x, y)) return hit.index;
    ReaderApp.Page page = app.bottomPage;
    if (page == null
        || x < left
        || x > getWidth() - left
        || y < bodyY
        || y > bodyY + page.layout.getHeight()) return -1;
    int line = page.layout.getLineForVertical((int) (y - bodyY));
    if (x - left > page.layout.getLineRight(line)) return -1;
    int offset = page.layout.getOffsetForHorizontal(line, x - left);
    return app.markAt(new ReaderApp.Location(page.start.chapter, page.start.offset + offset));
  }

  private void drawPreview(Canvas c) {
    LibraryPreview.draw(this, c, app);
  }

  private void drawWrapped(
      Canvas c,
      String text,
      float x,
      float y,
      float width,
      float size,
      int color,
      int lines,
      boolean serif) {
    TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    p.setTextSize(dp(size));
    p.setColor(color);
    p.setTypeface(Typeface.create(serif ? "serif" : "sans-serif", 0));
    StaticLayout l =
        StaticLayout.Builder.obtain(text, 0, text.length(), p, (int) width)
            .setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(dp(4), 1)
            .build();
    c.save();
    c.translate(x, y);
    l.draw(c);
    c.restore();
  }

  public void prepareTurn(ReaderApp.Page target, int progress, boolean marked, int dir) {
    endSelection();
    finishTurn();
    if (getWidth() == 0 || getHeight() == 0) return;
    direction = dir;
    turn = 0;
    turnBuffers.acquire(getWidth(), getHeight());
    oldPage = turnBuffers.before();
    drawPage(new Canvas(oldPage));
    newPage = turnBuffers.after();
    drawReadingPage(new Canvas(newPage), target, progress, marked, false);
  }

  public void turnFrame(float progress) {
    turnFrame(progress, .5f, 0);
  }

  public void turnFrame(float progress, float origin, float angle) {
    if (turn == progress && curlOriginY == origin && curlTilt == angle) return;
    turn = progress;
    curlOriginY = origin;
    curlTilt = angle;
    postInvalidateOnAnimation();
  }

  public void finishTurn() {
    oldPage = null;
    newPage = null;
    turn = 1;
    turnBuffers.idle();
    invalidate();
  }

  public void releaseTurnBuffers() {
    turnBuffers.release();
  }

  @Override
  protected void onDetachedFromWindow() {
    app.turns.cancel();
    endSelection();
    finishTurn();
    turnBuffers.release();
    clearPointer();
    super.onDetachedFromWindow();
  }

  void selectionMenu(Menu menu) {
    menu.clear();
    for (int i = 0; i < ReaderApp.HIGHLIGHT_COLORS.length; i++) {
      int color = ReaderApp.HIGHLIGHT_COLORS[i];
      String name = ReaderApp.HIGHLIGHT_NAMES[i] + " highlight";
      SpannableString swatch = new SpannableString("●");
      swatch.setSpan(
          new android.text.style.ForegroundColorSpan(color),
          0,
          1,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      swatch.setSpan(
          new android.text.style.RelativeSizeSpan(1.8f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      GradientDrawable chip = new GradientDrawable();
      chip.setShape(GradientDrawable.OVAL);
      chip.setColor(color);
      chip.setStroke(Math.max(1, (int) dp(1)), 0xff66645b);
      chip.setSize((int) dp(24), (int) dp(24));
      chip.setBounds(0, 0, (int) dp(24), (int) dp(24));
      MenuItem item = menu.add(0, COLOR_ACTION + i, i, swatch);
      item.setContentDescription(name);
      item.setTooltipText(name);
      item.setIcon(chip);
      item.setIconTintList(null);
      item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }
    menu.add(0, NOTE_ACTION, 10, ReaderIcons.title(getContext(), "note", "Note", 0xffe4e8df))
        .setContentDescription("Add note")
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
    menu.add(0, COPY_ACTION, 11, ReaderIcons.title(getContext(), "copy", "Copy", 0xffe4e8df))
        .setContentDescription("Copy selection")
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
    menu.add(
            0,
            DEFINE_ACTION,
            12,
            ReaderIcons.title(getContext(), "dictionary", "Define", 0xffe4e8df))
        .setContentDescription("English dictionary")
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
  }

  boolean selectionAction(ActionMode mode, int id) {
    ReaderApp.Page page = boundPage;
    if (page == null) return false;
    int a = Math.max(0, Math.min(getSelectionStart(), getSelectionEnd())),
        b = Math.min(getText().length(), Math.max(getSelectionStart(), getSelectionEnd()));
    if (b <= a) return false;
    ReaderApp.Location start = new ReaderApp.Location(page.start.chapter, page.start.offset + a);
    int end = page.start.offset + b;
    String quote = getText().subSequence(a, b).toString();
    mode.finish();
    if (id >= COLOR_ACTION && id < COLOR_ACTION + 4) {
      app.addMark(
          "highlight", start, end, "", quote, ReaderApp.HIGHLIGHT_COLORS[id - COLOR_ACTION]);
      if (app.controls != null) app.controls.toast("Passage highlighted");
      return true;
    }
    if (id == NOTE_ACTION && app.controls != null) {
      app.controls.noteSelection(start, end, quote);
      return true;
    }
    if (id == COPY_ACTION) {
      ((ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE))
          .setPrimaryClip(ClipData.newPlainText("Book passage", quote));
      return true;
    }
    if (id == DEFINE_ACTION && app.controls != null) {
      app.controls.dictionary(quote);
      return true;
    }
    return false;
  }

  public void endSelection() {
    passageSelection.finish();
  }

  void recycleVelocity() {
    if (velocity != null) {
      velocity.recycle();
      velocity = null;
    }
  }

  void clearPointer() {
    removeCallbacks(longPress);
    if (pendingDown != null) {
      pendingDown.recycle();
      pendingDown = null;
    }
    recycleVelocity();
  }

  void startNativeSelection() {
    if (pendingDown == null || dragging || gestureAttempted || touchBlocked || app.turns.active)
      return;
    passageSelection.begin(pendingDown.getX(), pendingDown.getY());
    cancelLongPress();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!lower) return true;
    int action = event.getActionMasked();
    if (selectionActive) {
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) clearPointer();
      return passageSelection.touch(event);
    }
    if (action == MotionEvent.ACTION_DOWN) {
      clearPointer();
      touchBlocked = app.turns.active;
      dragging = false;
      gestureAttempted = false;
      touchX = event.getX();
      touchY = event.getY();
      touchTime = event.getEventTime();
      if (touchBlocked) return true;
      velocity = VelocityTracker.obtain();
      velocity.addMovement(event);
      pendingDown = MotionEvent.obtain(event);
      postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
      return true;
    }
    if (touchBlocked) {
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
        touchBlocked = false;
      return true;
    }
    if (velocity != null) velocity.addMovement(event);
    float dx = event.getX() - touchX, dy = event.getY() - touchY;
    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    if (action == MotionEvent.ACTION_MOVE) {
      if (Math.abs(dx) > slop || Math.abs(dy) > slop) removeCallbacks(longPress);
      if (!gestureAttempted && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.15f) {
        gestureAttempted = true;
        removeCallbacks(longPress);
        dragDirection = dx < 0 ? 1 : -1;
        dragging = app.turns.beginDrag(dragDirection);
      }
      if (dragging) {
        float distance = Math.max(0, dragDirection > 0 ? -dx : dx);
        float angle =
            Math.max(
                -.16f, Math.min(.16f, (float) Math.atan2(dy, Math.max(1, Math.abs(dx))) * .28f));
        app.turns.drag(
            distance / Math.max(1, getWidth()), touchY / Math.max(1, getHeight()), angle);
        return true;
      }
    }
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      if (dragging) {
        float speed = 0;
        if (velocity != null) {
          velocity.computeCurrentVelocity(1000);
          speed = velocity.getXVelocity() * (dragDirection > 0 ? -1 : 1);
        }
        float distance = Math.max(0, dragDirection > 0 ? -dx : dx);
        boolean commit =
            action != MotionEvent.ACTION_CANCEL
                && speed > -dp(800)
                && (distance >= getWidth() * .26f || (speed > dp(800) && distance > slop * 2));
        app.turns.release(commit, speed);
        dragging = false;
        clearPointer();
        return true;
      }
      clearPointer();
      if (!gestureAttempted
          && action == MotionEvent.ACTION_UP
          && Math.abs(dx) <= slop
          && Math.abs(dy) <= slop) performClick();
      return true;
    }
    return true;
  }
}
