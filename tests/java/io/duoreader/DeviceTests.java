package io.duoreader;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import org.json.*;

/** Runs on the actual two-display device; no emulator, test library, or network required. */
public final class DeviceTests extends Instrumentation {
  ReaderApp app;
  JSONArray checks = new JSONArray();
  JSONObject report = new JSONObject();
  File testLibrary;

  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  void check(String name, boolean condition) throws Exception {
    JSONObject result = new JSONObject().put("check", name).put("passed", condition);
    checks.put(result);
    Bundle status = new Bundle();
    status.putString("stream", (condition ? "PASS " : "FAIL ") + name + "\n");
    sendStatus(condition ? 0 : -1, status);
    if (!condition) throw new AssertionError(name);
  }

  void waitUntil(BooleanSupplier predicate, String description) throws Exception {
    long until = SystemClock.uptimeMillis() + 20000;
    while (SystemClock.uptimeMillis() < until) {
      AtomicReference<Boolean> result = new AtomicReference<>(false);
      runOnMainSync(() -> result.set(predicate.getAsBoolean()));
      if (result.get()) return;
      Thread.sleep(100);
    }
    throw new AssertionError("Timeout: " + description);
  }

  void ui(Runnable r) {
    runOnMainSync(r);
    waitForIdleSync();
  }

  void press(int key) {
    try (android.os.ParcelFileDescriptor command =
            getUiAutomation()
                .executeShellCommand(
                    "input -d " + app.bottom.getDisplay().getDisplayId() + " keyevent " + key);
        FileInputStream output = new FileInputStream(command.getFileDescriptor())) {
      byte[] buffer = new byte[256];
      while (output.read(buffer) != -1) {}
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    waitForIdleSync();
    SystemClock.sleep(90);
    settled();
  }

  void settled() {
    long until = SystemClock.uptimeMillis() + 5000;
    while (app.turns.active && SystemClock.uptimeMillis() < until) SystemClock.sleep(25);
    waitForIdleSync();
    if (app.turns.active) throw new AssertionError("Page turn did not settle");
  }

  View find(View root, String text) {
    if (root instanceof TextView && ((TextView) root).getText().toString().equals(text))
      return root;
    if (root instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) root;
      for (int i = 0; i < g.getChildCount(); i++) {
        View found = find(g.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  void click(String text) {
    ui(
        () -> {
          if (app.controls.dialog == null && app.controls.paper != null)
            app.controls.showControls();
          View root =
              app.controls.dialog != null
                  ? app.controls.dialog.getWindow().getDecorView()
                  : app.controls.root;
          View v = find(root, text);
          if (v == null) throw new AssertionError("Missing UI control: " + text);
          v.performClick();
        });
    settled();
  }

  static int compare(ReaderApp.Location a, ReaderApp.Location b) {
    return a.chapter != b.chapter
        ? Integer.compare(a.chapter, b.chapter)
        : Integer.compare(a.offset, b.offset);
  }

  @Override
  public void onStart() {
    try {
      app = (ReaderApp) getTargetContext().getApplicationContext();
      getTargetContext()
          .startActivity(
              new Intent(getTargetContext(), MainActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitUntil(
          () -> app.ready && app.top != null && app.bottom != null && !app.restoring,
          "both displays");
      String originalRoot = app.root;
      final Library.Entry originalBook = app.current;
      final ReaderApp.Location originalPosition = app.position;
      final boolean originalMatch = app.matchScreens;
      if (originalRoot.startsWith("content:")) {
        ArrayList<Library.Entry> publicItems = Library.list(app, originalRoot);
        check(
            "Selected Android folder retains its read grant",
            !publicItems.isEmpty()
                && app.getContentResolver().getPersistedUriPermissions().size() > 0);
        Library.Entry first = publicItems.get(0);
        if (first.folder) {
          publicItems = Library.list(app, first.uri);
          check("Android document provider exposes nested collections", !publicItems.isEmpty());
        }
        for (Library.Entry e : publicItems)
          if (!e.folder) {
            Library.metadata(app, e);
            check(
                "EPUB cover and metadata load through Android's document provider",
                e.loaded && e.error.isEmpty() && !e.title.isEmpty());
            break;
          }
      }
      testLibrary = TestBooks.prepare(app);
      ui(() -> app.setRoot(Uri.fromFile(testLibrary).toString()));
      waitUntil(() -> !app.entries.isEmpty() && app.entries.get(0).loaded, "test library metadata");
      Library.Entry sample = null;
      for (Library.Entry e : app.entries)
        if (!e.folder && e.name.equals("Fixture.epub")) sample = e;
      check("Generated EPUB appears in the test folder", sample != null);
      final Library.Entry entry = sample;
      waitUntil(() -> entry.loaded, "fixture metadata");
      check(
          "EPUB metadata and cover are extracted",
          entry.loaded
              && entry.error.isEmpty()
              && entry.title.equals("Reader Fixture")
              && entry.cover != null);
      ui(() -> app.open(entry));
      waitUntil(() -> app.book != null && !app.busy && app.controls.paper != null, "open sample");
      waitForIdleSync();
      check("Upper activity is on display 0", app.top.getDisplay().getDisplayId() == 0);
      check(
          "Lower controls are on a separate display", app.bottom.getDisplay().getDisplayId() != 0);
      check(
          "Both screens render in portrait",
          app.top.paper.getHeight() > app.top.paper.getWidth()
              && app.controls.root.getHeight() > app.controls.root.getWidth());
      check(
          "Both Android displays stay landscape while the content follows the bottom D-pad grip",
          app.rotation == 90
              && app.top.getDisplay().getRotation() == Surface.ROTATION_90
              && app.bottom.getDisplay().getRotation() == Surface.ROTATION_0);
      check(
          "EPUB 3 navigation follows more than 100 spine sections",
          app.book.chapters.size() > 100 && app.book.toc.size() > 100);
      check("Book author is loaded", app.book.author.equals("Duo Reader"));
      Epub.Toc chapter = null;
      for (Epub.Toc t : app.book.toc)
        if (t.label.equals("Opening")) {
          chapter = t;
          break;
        }
      check("Table of contents includes the opening chapter", chapter != null);
      final Epub.Toc opening = chapter;
      ui(() -> app.jump(new ReaderApp.Location(opening.chapter, app.book.anchor(opening))));
      waitUntil(() -> app.topPage != null && app.bottomPage != null, "both pages laid out");
      check(
          "Two screens contain consecutive text without a gap",
          app.topPage.next.equals(app.bottomPage.start));
      ReaderApp.Location start = app.position, next = app.spreadEnd();
      press(KeyEvent.KEYCODE_DPAD_RIGHT);
      check("D-pad Right advances by exactly the two displayed pages", app.position.equals(next));
      press(KeyEvent.KEYCODE_DPAD_LEFT);
      check("D-pad backwards restores the exact spread", app.position.equals(start));
      ui(
          () -> {
            long now = SystemClock.uptimeMillis();
            app.top.dispatchKeyEvent(
                new KeyEvent(
                    now,
                    now,
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    0,
                    0,
                    -1,
                    0,
                    0,
                    InputDevice.SOURCE_GAMEPAD));
          });
      settled();
      check(
          "Physical controller right in book orientation advances a spread",
          app.position.equals(next));
      ui(
          () -> {
            long now = SystemClock.uptimeMillis();
            app.top.dispatchKeyEvent(
                new KeyEvent(
                    now,
                    now,
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_UP,
                    0,
                    0,
                    -1,
                    0,
                    0,
                    InputDevice.SOURCE_GAMEPAD));
          });
      settled();
      check(
          "Physical controller left in book orientation restores a spread",
          app.position.equals(start));
      press(KeyEvent.KEYCODE_BUTTON_R1);
      check("Right shoulder turns a spread", compare(app.position, start) > 0);
      press(KeyEvent.KEYCODE_BUTTON_L1);
      check("Left shoulder restores a spread", app.position.equals(start));
      final ReaderApp.Location beforeTop = app.position;
      ui(
          () -> {
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 200, 200, 0),
                up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, 200, 200, 0);
            app.top.dispatchTouchEvent(down);
            app.top.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
          });
      check(
          "Touch on the upper screen does not navigate or open controls",
          app.position.equals(beforeTop) && app.controls.dialog == null);
      click("Next ›");
      check("Lower-screen touch button turns pages", compare(app.position, start) > 0);
      click("‹ Previous");
      ArrayList<ReaderApp.Match> matches = app.search("Meet me outside");
      check("Full-text search finds the fixture's opening line", matches.size() > 0);
      ReaderApp.Match match = matches.get(0);
      ui(() -> app.jump(match.location));
      check(
          "Search result opens at the exact matching text",
          app.topPage.text.toString().startsWith("Meet me outside"));
      int count = app.marks.length();
      press(KeyEvent.KEYCODE_BUTTON_Y);
      check(
          "Y bookmarks the smaller screen's page",
          app.marks.length() == count + 1 && app.bookmarkedAt(app.bottomPage.start));
      press(KeyEvent.KEYCODE_BUTTON_Y);
      check("Y removes the same bookmark without duplicates", app.marks.length() == count);
      String originalMarks = app.marks.toString();
      ui(
          () ->
              app.addMark(
                  "highlight", match.location, match.location.offset + 15, "", "Meet me outside."));
      check(
          "Highlight is painted over the selected text",
          ((Spanned) app.topPage.text).getSpans(0, 15, BackgroundColorSpan.class).length > 0);
      ui(
          () ->
              app.addMark(
                  "note",
                  match.location,
                  match.location.offset + 15,
                  "A note saved entirely on the device.",
                  "Meet me outside."));
      check(
          "Notes are saved alongside their quotation and location",
          app.marks.length() == count + 2
              && app.marks
                  .optJSONObject(app.marks.length() - 1)
                  .optString("note")
                  .contains("entirely"));
      final int noteCount = app.marks.length();
      final ReaderApp.Location saved = app.position;
      ui(
          () -> {
            app.library();
            app.open(entry);
          });
      waitUntil(() -> !app.busy && app.mode.equals("reading"), "reopen with state");
      check("Opening a book resumes its exact chapter and character", app.position.equals(saved));
      check("Highlights and notes survive reopening", app.marks.length() == noteCount);
      ui(
          () -> {
            try {
              app.marks = new JSONArray(originalMarks);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
            app.paginate();
            app.save();
            app.changed();
          });
      // Exercise actual dialog focus and the D-pad keyboard without invoking the Android IME.
      press(KeyEvent.KEYCODE_BUTTON_X);
      check("X opens the reading menu", app.controls.dialog != null);
      press(KeyEvent.KEYCODE_BUTTON_B);
      check("B closes the reading menu", app.controls.dialog == null);
      AtomicReference<String> typed = new AtomicReference<>();
      ui(() -> app.controls.keyboard("Button input check", "", "Accept", typed::set));
      press(KeyEvent.KEYCODE_DPAD_DOWN);
      ui(() -> find(app.controls.dialog.getWindow().getDecorView(), "q").requestFocus());
      press(KeyEvent.KEYCODE_BUTTON_A);
      press(KeyEvent.KEYCODE_DPAD_RIGHT);
      press(KeyEvent.KEYCODE_BUTTON_A);
      press(KeyEvent.KEYCODE_BUTTON_Y);
      press(KeyEvent.KEYCODE_BUTTON_L1);
      click("Accept");
      check(
          "D-pad and A type text; Y inserts and L1 removes a space (" + typed.get() + ")",
          "qw".equals(typed.get()));
      int oldFont = app.font,
          oldSize = app.fontSize,
          oldMargin = app.margin,
          oldTheme = app.theme,
          oldPaper = app.paper;
      float oldSpacing = app.spacing;
      boolean reflow = true;
      for (int family = 0; family < 3; family++)
        for (int size : new int[] {12, 18, 28}) {
          final int f = family, s = size;
          ui(
              () -> {
                app.font = f;
                app.fontSize = s;
                app.margin = s == 28 ? 40 : 12;
                app.spacing = 1.6f;
                app.appearance();
              });
          waitForIdleSync();
          reflow &=
              app.position.equals(saved)
                  && app.topPage.next.equals(app.bottomPage.start)
                  && app.topPage.layout.getHeight() <= app.topHeight + 2
                  && app.bottomPage.layout.getHeight() <= app.bottomHeight + 2;
        }
      check(
          "Font, size, spacing and margin changes preserve the anchor and fit both screens",
          reflow);
      Set<Integer> colors = new HashSet<>();
      ui(() -> app.library());
      for (int theme = 0; theme < 5; theme++) {
        final int t = theme;
        ui(
            () -> {
              app.theme = t;
              app.appearance();
            });
        colors.add(app.controls.accent());
      }
      ui(() -> app.resume());
      check("All five Duo colors have distinct library themes", colors.size() == 5);
      for (int paper = 0; paper < 3; paper++) {
        final int p = paper;
        ui(
            () -> {
              app.paper = p;
              app.appearance();
            });
        check("Page lighting " + paper + " preserves reading position", app.position.equals(saved));
      }
      ui(
          () -> {
            app.font = oldFont;
            app.fontSize = oldSize;
            app.margin = oldMargin;
            app.spacing = oldSpacing;
            app.theme = oldTheme;
            app.paper = oldPaper;
            app.appearance();
          });
      ui(
          () -> {
            app.matchScreens = false;
            app.appearance();
          });
      int fullWidth = app.top.paper.getWidth(), fullHeight = app.top.paper.getHeight();
      ui(
          () -> {
            app.matchScreens = true;
            app.appearance();
          });
      waitForIdleSync();
      check(
          "Matched viewport fills one dimension and leaves borders on only one pair of sides",
          (app.top.paper.getWidth() == fullWidth
                  && app.top.paper.getHeight() < fullHeight
                  && app.top.paper.getLeft() == 0
                  && app.top.paper.getTop() > 0)
              || (app.top.paper.getHeight() == fullHeight
                  && app.top.paper.getWidth() < fullWidth
                  && app.top.paper.getTop() == 0
                  && app.top.paper.getLeft() > 0));
      check(
          "Matching screen sizes preserves the exact reading position",
          app.position.equals(saved) && app.topPage.next.equals(app.bottomPage.start));
      ui(
          () -> {
            app.matchScreens = false;
            app.appearance();
          });
      waitForIdleSync();
      check(
          "Turning matching off restores the full larger screen",
          app.top.paper.getWidth() == fullWidth && app.top.paper.getHeight() == fullHeight);
      testReaderInteractions();
      testBindings();
      testRevision();
      testFolders(entry);
      testEpub2();
      ui(
          () -> {
            app.setRoot(originalRoot);
            app.matchScreens = originalMatch;
            app.appearance();
            if (originalBook != null) app.open(originalBook);
          });
      if (originalBook != null)
        waitUntil(
            () -> !app.busy && app.current.uri.equals(originalBook.uri), "restore original book");
      ui(
          () -> {
            app.jump(originalBook == null ? saved : originalPosition);
            app.mode = "reading";
            app.save();
            app.changed();
          });
      report
          .put("device", Build.MODEL)
          .put("android", Build.VERSION.RELEASE)
          .put("topDisplay", app.top.getDisplay().getDisplayId())
          .put("bottomDisplay", app.bottom.getDisplay().getDisplayId())
          .put("resumeChapter", app.position.chapter)
          .put("resumeOffset", app.position.offset)
          .put("passed", true);
      check(
          "App has no network permission",
          getTargetContext().checkSelfPermission("android.permission.INTERNET")
              != android.content.pm.PackageManager.PERMISSION_GRANTED);
      writeReport();
      Bundle result = new Bundle();
      result.putString(
          "stream",
          "All " + checks.length() + " device checks passed. Report: files/verification.json\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      try {
        report.put("passed", false).put("failure", e.toString());
        writeReport();
      } catch (Exception ignored) {
      }
      Bundle failure = new Bundle();
      StringWriter trace = new StringWriter();
      e.printStackTrace(new PrintWriter(trace));
      failure.putString("stream", trace.toString());
      finish(Activity.RESULT_CANCELED, failure);
    }
  }

  void shellInput(String args) throws Exception {
    try (ParcelFileDescriptor command =
            getUiAutomation()
                .executeShellCommand(
                    "input -d "
                        + app.bottom.getDisplay().getDisplayId()
                        + " "
                        + ScreenCoordinates.gesture(app.controls.paper, args));
        FileInputStream output = new FileInputStream(command.getFileDescriptor())) {
      byte[] b = new byte[256];
      while (output.read(b) != -1) {}
    }
    waitForIdleSync();
  }

  void finger(int action, float x, float y, long down) {
    ui(
        () -> {
          long now = SystemClock.uptimeMillis();
          MotionEvent e = MotionEvent.obtain(down, now, action, x, y, 0);
          e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
          app.controls.paper.dispatchTouchEvent(e);
          e.recycle();
        });
  }

  Bitmap pageImage(PaperView page) {
    AtomicReference<Bitmap> result = new AtomicReference<>();
    ui(
        () -> {
          Bitmap b = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.RGB_565);
          page.draw(new Canvas(b));
          result.set(b);
        });
    return result.get();
  }

  void testReaderInteractions() throws Exception {
    final ReaderApp.Location place = app.position;
    final String initialMarks = app.marks.toString();
    final boolean animation = app.animate;
    ui(
        () -> {
          app.animate = true;
          app.controls.hideControls();
        });
    int width = app.controls.paper.getWidth(),
        height = app.controls.paper.getHeight(),
        bodyHeight = app.bottomHeight;
    ReaderApp.Location end = app.bottomPage.next;
    shellInput("tap " + (width / 2) + " " + (height / 3));
    check(
        "Tap reveals translucent controls over a full-height page",
        app.controls.controlsVisible
            && app.controls.paper.getHeight() == height
            && app.bottomHeight == bodyHeight
            && app.bottomPage.next.equals(end));
    GradientDrawable buttonBg =
        (GradientDrawable) find(app.controls.root, "Next ›").getBackground().getCurrent();
    check(
        "Reading controls have transparent backgrounds",
        Color.alpha(buttonBg.getColor().getDefaultColor()) < 255);
    shellInput("tap " + (width / 2) + " " + (height / 3));
    check(
        "Second tap hides controls without repaginating",
        !app.controls.controlsVisible && app.bottomPage.next.equals(end));
    ui(() -> app.controls.showControls());
    waitUntil(() -> !app.controls.controlsVisible, "automatic control hiding");
    check(
        "Reading controls auto-hide after inactivity",
        app.controls.controlsOverlay.getVisibility() == View.GONE
            && app.bottomHeight == bodyHeight);
    final ReaderApp.Location next = app.spreadEnd();
    Bitmap originalUpper = pageImage(app.top.paper);
    ui(() -> app.turn(1));
    waitUntil(
        () -> app.turns.active && app.turns.lowerProgress > .05f && app.turns.lowerProgress < .95f,
        "first forward page");
    Bitmap heldUpper = pageImage(app.top.paper);
    check(
        "Forward turn starts on the smaller screen while the larger page remains unchanged",
        app.turns.upperProgress == 0 && originalUpper.sameAs(heldUpper));
    originalUpper.recycle();
    heldUpper.recycle();
    waitUntil(() -> app.turns.active && app.turns.upperProgress > .02f, "second forward page");
    check(
        "Larger screen starts only after the smaller screen finishes",
        app.turns.lowerProgress == 1 && app.position.equals(place));
    settled();
    check("Sequenced forward turn commits exactly one spread", app.position.equals(next));
    ui(() -> app.turn(-1));
    waitUntil(
        () -> app.turns.active && app.turns.upperProgress > .05f && app.turns.upperProgress < .95f,
        "first backward page");
    check("Backward turn reverses the screen order", app.turns.lowerProgress == 0);
    settled();
    check("Sequenced backward turn restores the original spread", app.position.equals(place));
    float x = width * .82f, y = height * .37f;
    long down = SystemClock.uptimeMillis();
    finger(MotionEvent.ACTION_DOWN, x, y, down);
    SystemClock.sleep(80);
    finger(MotionEvent.ACTION_MOVE, x - width * .28f, y, down);
    check(
        "A held drag previews a page turn without moving the reading position",
        app.turns.active
            && app.turns.interactive
            && app.turns.lowerProgress > 0
            && app.turns.upperProgress == 0
            && app.position.equals(place));
    float progress = app.turns.progress;
    SystemClock.sleep(250);
    check(
        "The page stays under a stationary finger",
        Math.abs(app.turns.progress - progress) < .001f);
    finger(MotionEvent.ACTION_MOVE, x - width * .53f, y, down);
    check(
        "Dragging farther moves the page farther",
        app.turns.progress > progress && app.turns.interactive);
    finger(MotionEvent.ACTION_UP, x - width * .53f, y, down);
    settled();
    check(
        "Releasing a long drag finishes both pages and saves the new position",
        app.position.equals(next)
            && app.prefs.getInt(app.bookKey() + "offset", -1) == app.position.offset);
    final ReaderApp.Location afterDrag = app.position;
    int historySize = app.back.size();
    long cancelDown = SystemClock.uptimeMillis();
    finger(MotionEvent.ACTION_DOWN, x, y, cancelDown);
    SystemClock.sleep(70);
    finger(MotionEvent.ACTION_MOVE, x - width * .1f, y, cancelDown);
    SystemClock.sleep(100);
    finger(MotionEvent.ACTION_CANCEL, x - width * .1f, y, cancelDown);
    settled();
    check(
        "A cancelled drag restores the page and leaves navigation history intact",
        app.position.equals(afterDrag) && app.back.size() == historySize);
    ReaderApp.Location flickTarget = app.spreadEnd();
    shellInput(
        "swipe "
            + Math.round(x)
            + " "
            + Math.round(y)
            + " "
            + Math.round(x - width * .18f)
            + " "
            + Math.round(y)
            + " 45");
    settled();
    check("A short quick flick completes the turn", app.position.equals(flickTarget));
    ui(
        () -> {
          app.jump(place);
          app.controls.hideControls();
          app.controls.root.setFocusableInTouchMode(true);
          app.controls.root.requestFocus();
        });
    PaperView paper = app.controls.paper;
    int aX = paper.getPaddingLeft() + Math.round(paper.dp(25)),
        aY = paper.getPaddingTop() + paper.getLayout().getLineBaseline(0) - Math.round(paper.dp(5));
    shellInput("swipe " + aX + " " + aY + " " + aX + " " + aY + " 850");
    waitUntil(() -> app.controls.paper.selectionMode != null, "portrait selection toolbar");
    paper = app.controls.paper;
    int a = Math.min(paper.getSelectionStart(), paper.getSelectionEnd()),
        b = Math.max(paper.getSelectionStart(), paper.getSelectionEnd());
    check(
        "Long press selects text with a portrait floating toolbar",
        paper.selectionActive && b > a && app.controls.dialog == null);
    check(
        "Selection popup offers four highlight colors and Note",
        paper.selectionMode.getMenu().findItem(PaperView.COLOR_ACTION + 3) != null
            && paper.selectionMode.getMenu().findItem(PaperView.NOTE_ACTION) != null);
    CharSequence swatch =
        paper.selectionMode.getMenu().findItem(PaperView.COLOR_ACTION + 2).getTitle();
    check(
        "Color choices are visible swatches rather than uncolored labels",
        swatch instanceof Spanned
            && ((Spanned) swatch)
                    .getSpans(0, swatch.length(), android.text.style.ForegroundColorSpan.class)
                    .length
                > 0);
    int endLine = paper.getLayout().getLineForOffset(b),
        handleX =
            paper.getPaddingLeft()
                + Math.round(paper.getLayout().getPrimaryHorizontal(b) + paper.dp(10)),
        handleY =
            paper.getPaddingTop()
                + paper.getLayout().getLineBottom(endLine)
                + Math.round(paper.dp(4));
    int targetOffset = Math.min(paper.getText().length(), b + 12),
        targetX =
            paper.getPaddingLeft()
                + Math.round(paper.getLayout().getPrimaryHorizontal(targetOffset) + paper.dp(10)),
        targetY =
            paper.getPaddingTop()
                + paper.getLayout().getLineBottom(paper.getLayout().getLineForOffset(targetOffset))
                + Math.round(paper.dp(4));
    shellInput("swipe " + handleX + " " + handleY + " " + targetX + " " + targetY + " 450");
    check(
        "Dragging the portrait selection handle extends the passage without turning a page",
        paper.getSelectionEnd() > b && !app.turns.active && app.position.equals(place));
    a = Math.min(paper.getSelectionStart(), paper.getSelectionEnd());
    b = Math.max(paper.getSelectionStart(), paper.getSelectionEnd());
    String quote = paper.getText().subSequence(a, b).toString();
    int chapter = app.bottomPage.start.chapter,
        start = app.bottomPage.start.offset + a,
        finish = app.bottomPage.start.offset + b;
    ui(
        () ->
            app.controls
                .paper
                .selectionMode
                .getMenu()
                .performIdentifierAction(PaperView.COLOR_ACTION + 2, 0));
    JSONObject mark = app.marks.optJSONObject(app.marks.length() - 1);
    check(
        "Choosing blue highlights exactly the selected characters",
        mark != null
            && mark.optInt("color") == ReaderApp.HIGHLIGHT_COLORS[2]
            && mark.optString("quote").equals(quote)
            && mark.optInt("chapter") == chapter
            && mark.optInt("start") == start
            && mark.optInt("end") == finish);
    check(
        "Highlight color is persisted with the annotation",
        app.prefs
            .getString(app.bookKey() + "marks", "")
            .contains(Integer.toString(ReaderApp.HIGHLIGHT_COLORS[2])));
    ui(
        () -> {
          try {
            app.marks = new JSONArray(initialMarks);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          app.controls.root.setFocusableInTouchMode(false);
          app.controls.paper.requestFocus();
          app.animate = animation;
          app.jump(place);
          app.save();
        });
  }

  ArrayList<Button> buttons(View root) {
    ArrayList<Button> result = new ArrayList<>();
    if (root instanceof Button) result.add((Button) root);
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++) result.addAll(buttons(group.getChildAt(i)));
    }
    return result;
  }

  void testBindings() throws Exception {
    final ReaderApp.Location anchor = app.position;
    final int oldSize = app.fontSize, oldPaper = app.paper;
    final Map<Integer, ReadingBindings.Action> original = new HashMap<>();
    for (int key : ReadingBindings.KEYS) original.put(key, app.bindings.get(key));
    ui(
        () -> {
          app.bindings.reset();
          app.fontSize = 18;
          app.appearance();
          app.controls.hideControls();
        });
    press(KeyEvent.KEYCODE_DPAD_UP);
    check(
        "Reading D-pad Up enlarges text without moving the saved anchor",
        app.fontSize == 19 && app.position.equals(anchor) && app.prefs.getInt("size", 0) == 19);
    press(KeyEvent.KEYCODE_DPAD_DOWN);
    check(
        "Reading D-pad Down reduces text without turning a page",
        app.fontSize == 18 && app.position.equals(anchor));
    hat(1, 0);
    hat(0, 0);
    check(
        "D-pad Up in the portrait grip increases font size through the controller hat",
        app.fontSize == 19 && app.position.equals(anchor));
    hat(-1, 0);
    hat(0, 0);
    check(
        "D-pad Down in the portrait grip decreases font size through the controller hat",
        app.fontSize == 18 && app.position.equals(anchor));
    hat(1, 0);
    SystemClock.sleep(650);
    hat(0, 0);
    check(
        "Holding portrait Up repeats font changes",
        app.fontSize >= 21 && app.position.equals(anchor));
    ui(
        () -> {
          app.fontSize = 32;
          app.appearance();
        });
    press(KeyEvent.KEYCODE_DPAD_UP);
    check("Font-size shortcuts stop at the largest supported size", app.fontSize == 32);
    ui(
        () -> {
          app.fontSize = 12;
          app.appearance();
        });
    press(KeyEvent.KEYCODE_DPAD_DOWN);
    check("Font-size shortcuts stop at the smallest supported size", app.fontSize == 12);
    press(KeyEvent.KEYCODE_BUTTON_START);
    click("Keybindings");
    check(
        "Reading menu exposes a keybinding editor",
        find(app.controls.dialog.getWindow().getDecorView(), "A  ·  Show reading controls")
            != null);
    click("A  ·  Show reading controls");
    click("Cycle page colors");
    check(
        "Choosing a shortcut updates its row and saves it durably",
        find(app.controls.dialog.getWindow().getDecorView(), "A  ·  Cycle page colors") != null
            && new ReadingBindings(app.getSharedPreferences("reader", 0))
                    .get(KeyEvent.KEYCODE_BUTTON_A)
                == ReadingBindings.Action.COLORS
            && app.prefs.getString("binding." + KeyEvent.KEYCODE_BUTTON_A, "").equals("colors"));
    press(KeyEvent.KEYCODE_BUTTON_B);
    press(KeyEvent.KEYCODE_BUTTON_A);
    check(
        "Remapped A cycles page colors and keeps the reading location",
        app.paper == (oldPaper + 1) % 3
            && app.position.equals(anchor)
            && !app.controls.controlsFocus);
    final int color = app.paper;
    ui(
        () ->
            app.controls.routeControllerKey(
                new ReaderInput.ScreenKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 1)));
    check("Holding a color shortcut does not cycle repeatedly", app.paper == color);
    ui(
        () -> {
          app.bindings.set(KeyEvent.KEYCODE_BUTTON_B, ReadingBindings.Action.NEXT);
          app.bindings.set(KeyEvent.KEYCODE_BUTTON_X, ReadingBindings.Action.FONT_UP);
        });
    press(KeyEvent.KEYCODE_BUTTON_START);
    check("Start always opens the menu even after ABX are remapped", app.controls.dialog != null);
    press(KeyEvent.KEYCODE_BUTTON_B);
    check(
        "B still closes dialogs when its reading shortcut is changed",
        app.controls.dialog == null && app.mode.equals("reading"));
    press(KeyEvent.KEYCODE_BUTTON_X);
    check("Remapped X changes font size", app.fontSize == 13 && app.position.equals(anchor));
    ReaderApp.Location next = app.spreadEnd();
    press(KeyEvent.KEYCODE_BUTTON_B);
    check("Remapped B turns exactly one spread", app.position.equals(next));
    press(KeyEvent.KEYCODE_DPAD_CENTER);
    check("D-pad Center remains a fixed way to reach the controls", app.controls.controlsFocus);
    final int controlSize = app.fontSize;
    press(KeyEvent.KEYCODE_DPAD_UP);
    check(
        "D-pad navigates focused controls without changing text size", app.fontSize == controlSize);
    press(KeyEvent.KEYCODE_BUTTON_B);
    check(
        "B still exits control focus with a changed reading binding",
        !app.controls.controlsFocus && app.mode.equals("reading"));
    press(KeyEvent.KEYCODE_BUTTON_START);
    click("Keybindings");
    click("Restore default keybindings");
    check(
        "Restore defaults resets only reading shortcuts",
        app.bindings.get(KeyEvent.KEYCODE_BUTTON_A) == ReadingBindings.Action.CONTROLS
            && app.bindings.get(KeyEvent.KEYCODE_BUTTON_B) == ReadingBindings.Action.BACK
            && app.bindings.get(KeyEvent.KEYCODE_BUTTON_X) == ReadingBindings.Action.MENU
            && app.paper == color);
    ui(
        () -> {
          app.controls.dialog.dismiss();
          for (Map.Entry<Integer, ReadingBindings.Action> e : original.entrySet())
            app.bindings.set(e.getKey(), e.getValue());
          app.fontSize = oldSize;
          app.paper = oldPaper;
          app.appearance();
          app.jump(anchor);
          app.controls.hideControls();
        });
  }

  void savePreview(String name, PaperView view) throws Exception {
    Bitmap bitmap = pageImage(view);
    try (OutputStream out =
        new FileOutputStream(new File(getTargetContext().getFilesDir(), name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    bitmap.recycle();
  }

  void hat(float x, float y) {
    ui(
        () -> {
          MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
          p.id = 0;
          p.toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
          MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
          c.setAxisValue(MotionEvent.AXIS_HAT_X, x);
          c.setAxisValue(MotionEvent.AXIS_HAT_Y, y);
          long now = SystemClock.uptimeMillis();
          MotionEvent event =
              MotionEvent.obtain(
                  now,
                  now,
                  MotionEvent.ACTION_MOVE,
                  1,
                  new MotionEvent.PointerProperties[] {p},
                  new MotionEvent.PointerCoords[] {c},
                  0,
                  0,
                  1,
                  1,
                  13,
                  0,
                  InputDevice.SOURCE_JOYSTICK,
                  0);
          app.bottom.dispatchGenericMotionEvent(event);
          event.recycle();
        });
  }

  void testRevision() throws Exception {
    final ReaderApp.Location original = app.position;
    final String originalMarks = app.marks.toString();
    check(
        "Reading changes finish a durable save before returning",
        app.lastSaveSuccessful
            && app.prefs.getInt(app.bookKey() + "chapter", -1) == app.position.chapter
            && app.prefs.getInt(app.bookKey() + "offset", -1) == app.position.offset);
    String lastBook = app.current.uri;
    ui(
        () -> {
          app.current = null;
          app.resumePending = true;
          app.mode = "library";
          app.open(
              new Library.Entry(
                  "content://io.duoreader.missing.provider/book.epub",
                  "Unavailable book.epub",
                  false));
        });
    waitUntil(
        () -> app.current != null && app.current.uri.equals(lastBook) && !app.busy,
        "retry unavailable restore provider");
    check(
        "A failed restore preserves the saved pointer and retries the last book",
        app.position.equals(original)
            && app.prefs.getBoolean("reading", false)
            && app.current.uri.equals(lastBook));
    ui(() -> app.controls.readerMenu());
    Set<String> labels = new HashSet<>();
    for (Button b : buttons(app.controls.dialog.getWindow().getDecorView()))
      labels.add(b.getText().toString());
    check(
        "Reading menu has no duplicate contents, search, bookmark or appearance controls",
        !labels.contains("Table of contents")
            && !labels.contains("Search this book")
            && !labels.contains("Bookmark this page")
            && !labels.contains("Appearance")
            && !labels.contains("Highlight or add a note"));
    ui(() -> app.controls.dialog.dismiss());
    check(
        "Bottom toolbar has Contents, Search and Menu without Annotate",
        find(app.controls.controlsOverlay, "Contents") != null
            && find(app.controls.controlsOverlay, "Search") != null
            && find(app.controls.controlsOverlay, "Annotate") == null);
    ui(() -> app.controls.showControls());
    check(
        "Appearance is a top overlay and does not shrink the book page",
        app.controls.appearanceOverlay.getVisibility() == View.VISIBLE
            && ((android.widget.FrameLayout.LayoutParams)
                        app.controls.appearanceOverlay.getLayoutParams())
                    .gravity
                == Gravity.TOP
            && app.controls.paper.getHeight() == app.controls.root.getHeight());
    ui(() -> app.controls.fontPicker());
    ArrayList<Button> fontOptions = buttons(app.controls.dialog.getWindow().getDecorView());
    boolean previews = true;
    for (int i = 0; i < 3; i++)
      previews &= fontOptions.get(i).getTypeface().equals(app.fontTypeface(i));
    check("Typeface choices preview their actual fonts", previews);
    ui(() -> app.controls.dialog.dismiss());
    ui(() -> app.controls.showControls());
    ArrayList<Button> themeChips = buttons(app.controls.appearanceOverlay);
    boolean pageThemes = true;
    int found = 0;
    for (Button b : themeChips)
      for (int i = 0; i < ReaderApp.PAPER_NAMES.length; i++)
        if (b.getText().toString().equals(ReaderApp.PAPER_NAMES[i])) {
          GradientDrawable bg = (GradientDrawable) b.getBackground();
          pageThemes &=
              bg.getColor().getDefaultColor() == ReaderApp.PAPER_COLORS[i]
                  && b.getCurrentTextColor() == ReaderApp.PAPER_INKS[i];
          found++;
        }
    check("Page themes preview both background and foreground colors", pageThemes && found == 3);
    ui(() -> app.controls.hideControls());
    ReaderApp.Location lower = app.bottomPage.start;
    int oldCount = app.marks.length();
    shellInput("tap " + (app.controls.root.getWidth() - 30) + " 30");
    check(
        "Upper-right corner bookmarks the smaller page, not the larger page",
        app.bookmarkedAt(lower) && app.marks.length() == oldCount + 1);
    shellInput("tap " + (app.controls.root.getWidth() - 30) + " 30");
    check("Corner tap also removes that bookmark", app.marks.length() == oldCount);
    press(KeyEvent.KEYCODE_BUTTON_A);
    check(
        "A exposes button-navigation for quick reading controls",
        app.controls.controlsFocus && app.controls.controlsVisible);
    press(KeyEvent.KEYCODE_BUTTON_B);
    check(
        "B leaves control focus without leaving the book",
        app.mode.equals("reading") && !app.controls.controlsFocus);
    String whale = app.dictionary.lookup("whale"),
        children = app.dictionary.lookup("children"),
        running = app.dictionary.lookup("running");
    check(
        "Offline dictionary supplies English definitions",
        whale.startsWith("whale\n") && whale.contains("Noun") && whale.length() > 50);
    check(
        "Dictionary resolves irregular plurals and inflected verbs",
        children.startsWith("child\n") && running.contains("Verb"));
    ui(() -> app.controls.dictionary("whale"));
    waitUntil(
        () ->
            app.controls.dialog != null
                && find(app.controls.dialog.getWindow().getDecorView(), whale) != null,
        "dictionary card");
    check("Dictionary definitions appear inside the reader", app.controls.dialog != null);
    ui(() -> app.controls.dialog.dismiss());
    ArrayList<ReaderApp.Match> results = app.search("Meet me outside");
    ui(() -> app.controls.searchResults("Meet me outside", results));
    boolean marked = false;
    for (Button b : buttons(app.controls.dialog.getWindow().getDecorView()))
      if (b.getText() instanceof Spanned) {
        Spanned text = (Spanned) b.getText();
        for (BackgroundColorSpan span : text.getSpans(0, text.length(), BackgroundColorSpan.class))
          if (text.subSequence(text.getSpanStart(span), text.getSpanEnd(span))
              .toString()
              .equalsIgnoreCase("Meet me outside")) marked = true;
      }
    check("Search result cards highlight the matching phrase", marked);
    ui(() -> app.controls.dialog.dismiss());
    ReaderApp.Location noteStart = app.bottomPage.start;
    String quote =
        app.bottomPage.text.subSequence(0, Math.min(20, app.bottomPage.text.length())).toString();
    ui(
        () -> {
          app.searchTerm = "";
          app.addMark(
              "note",
              noteStart,
              noteStart.offset + quote.length(),
              "A visibly distinct note for tapping.",
              quote);
        });
    check(
        "Notes underline their passage in addition to its color",
        ((Spanned) app.bottomPage.text)
                .getSpans(0, quote.length(), android.text.style.UnderlineSpan.class)
                .length
            > 0);
    savePreview("note-preview", app.controls.paper);
    check("Notes have visible margin icons", !app.controls.paper.noteHits.isEmpty());
    PaperView.NoteHit hit = app.controls.paper.noteHits.get(0);
    shellInput("tap " + Math.round(hit.rect.centerX()) + " " + Math.round(hit.rect.centerY()));
    check(
        "Tapping a note icon opens its saved note",
        app.controls.dialog != null
            && find(
                    app.controls.dialog.getWindow().getDecorView(),
                    "A visibly distinct note for tapping.")
                != null);
    ui(() -> app.controls.dialog.dismiss());
    ui(
        () -> {
          try {
            app.marks = new JSONArray(originalMarks);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          app.jump(original);
          app.turns.beginDrag(1);
          app.turns.drag(.5f, .22f, .13f);
        });
    savePreview("curl-diagonal", app.controls.paper);
    check(
        "A diagonal drag carries its angle and origin into the curl",
        app.controls.paper.curlTilt > .1f
            && Math.abs(app.controls.paper.curlOriginY - .22f) < .01f
            && Math.abs(app.turns.lowerProgress - .25f) < .01f);
    ui(() -> app.turns.release(false));
    settled();
    ui(
        () -> {
          app.turns.beginDrag(1);
          app.turns.drag(.3f);
          app.turns.release(true, 150);
        });
    long slow = app.turns.lastSettleDuration;
    settled();
    ui(
        () -> {
          app.jump(original);
          app.turns.beginDrag(1);
          app.turns.drag(.3f);
          app.turns.release(true, 4500);
        });
    long fast = app.turns.lastSettleDuration;
    settled();
    check("Fast flicks finish faster than slow releases", fast < slow / 2);
    ui(
        () -> {
          app.searchTerm = "";
          app.jump(original);
          app.save();
        });
    ArrayList<Library.Entry> samples = Library.list(app, Uri.fromFile(testLibrary).toString());
    int readable = 0;
    for (Library.Entry e : samples)
      if (!e.folder) {
        Library.metadata(app, e);
        if (e.error.isEmpty()) readable++;
      }
    check("Four generated EPUB fixtures open successfully", readable >= 4);
  }

  void testFolders(Library.Entry sample) throws Exception {
    File root = new File(getTargetContext().getCacheDir(), "folder-check"),
        child = new File(root, "Classics");
    child.mkdirs();
    File epub = new File(child, "Example.epub"),
        second = new File(root, "Second.epub"),
        ignore = new File(root, "Ignore.pdf");
    new File(root, "Ignore.txt").delete();
    try (InputStream in =
            new FileInputStream(Library.cached(app, sample.uri, sample.modified, sample.size));
        OutputStream out = new FileOutputStream(epub)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }
    new FileOutputStream(ignore).close();
    try (InputStream in = new FileInputStream(epub);
        OutputStream out = new FileOutputStream(second)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }
    String oldRoot = app.root;
    ArrayList<Library.Entry> items = Library.list(app, Uri.fromFile(root).toString());
    check(
        "Folder browser shows collections and filters unsupported files",
        items.size() == 2 && items.get(0).folder && !items.get(1).folder);
    ui(() -> app.setRoot(Uri.fromFile(root).toString()));
    waitUntil(() -> app.entries.size() == 2 && app.entries.get(0).folder, "test root");
    press(KeyEvent.KEYCODE_DPAD_DOWN);
    ui(
        () -> {
          app.controls.list.requestFocus();
          app.controls.list.setSelection(0);
        });
    ui(
        () -> {
          long now = SystemClock.uptimeMillis();
          app.top.dispatchKeyEvent(
              new KeyEvent(
                  now,
                  now,
                  KeyEvent.ACTION_DOWN,
                  KeyEvent.KEYCODE_DPAD_LEFT,
                  0,
                  0,
                  -1,
                  0,
                  0,
                  InputDevice.SOURCE_GAMEPAD));
        });
    check(
        "Controller events received by the upper screen navigate the lower library",
        app.controls.list.getSelectedItemPosition() == 1);
    ui(
        () -> {
          app.controls.list.requestFocusFromTouch();
          app.controls.list.setSelection(0);
        });
    hat(-1, 0);
    hat(0, 0);
    check(
        "The actual joystick D-pad hat follows portrait direction in the library",
        app.controls.list.getSelectedItemPosition() == 1);
    ui(
        () -> {
          app.controls.list.requestFocusFromTouch();
          app.controls.list.setSelection(0);
        });
    waitUntil(() -> app.controls.list.getSelectedItemPosition() == 0, "library selection reset");
    ui(
        () -> {
          int device = -1;
          for (int id : InputDevice.getDeviceIds())
            if (InputDevice.getDevice(id).getName().contains("Retroid Pocket Controller"))
              device = id;
          long now = SystemClock.uptimeMillis();
          app.bottom.dispatchKeyEvent(
              new KeyEvent(
                  now,
                  now,
                  KeyEvent.ACTION_DOWN,
                  KeyEvent.KEYCODE_DPAD_LEFT,
                  0,
                  0,
                  device,
                  0,
                  0,
                  InputDevice.SOURCE_DPAD));
        });
    check(
        "Controller keys reported as DPAD are also rotated once",
        app.controls.list.getSelectedItemPosition() == 1);
    ui(() -> app.enter(app.entries.get(0)));
    waitUntil(() -> app.entries.size() == 1 && !app.entries.get(0).folder, "nested EPUB");
    check("Nested collections open and show their EPUBs", app.folder.contains("Classics"));
    ui(() -> app.parent());
    waitUntil(() -> app.folder.equals(Uri.fromFile(root).toString()), "parent navigation");
    check("Back from a collection restores the parent", app.entries.get(0).folder);
    ui(() -> app.setRoot(oldRoot));
    waitUntil(() -> app.folder.equals(oldRoot), "restore library");
    epub.delete();
    second.delete();
    ignore.delete();
    child.delete();
    root.delete();
  }

  static void entry(ZipOutputStream zip, String path, String value) throws IOException {
    zip.putNextEntry(new ZipEntry(path));
    zip.write(value.getBytes("UTF-8"));
    zip.closeEntry();
  }

  void testEpub2() throws Exception {
    File fixture = new File(getTargetContext().getCacheDir(), "epub2-check.epub");
    try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(fixture))) {
      entry(z, "mimetype", "application/epub+zip");
      entry(
          z,
          "META-INF/container.xml",
          "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile"
              + " full-path='OPS/book.opf'/></rootfiles></container>");
      entry(
          z,
          "OPS/book.opf",
          "<package xmlns='http://www.idpf.org/2007/opf' version='2.0'><metadata"
              + " xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>EPUB Two"
              + " Check</dc:title><dc:creator>Device Tests</dc:creator></metadata><manifest><item"
              + " id='text' href='chapter.xhtml' media-type='application/xhtml+xml'/><item id='nav'"
              + " href='toc.ncx' media-type='application/x-dtbncx+xml'/></manifest><spine"
              + " toc='nav'><itemref idref='text'/></spine></package>");
      entry(
          z,
          "OPS/toc.ncx",
          "<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap><navPoint><navLabel><text>Opening</text></navLabel><content"
              + " src='chapter.xhtml#start'/></navPoint></navMap></ncx>");
      entry(
          z,
          "OPS/chapter.xhtml",
          "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN'"
              + " 'http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd'><html"
              + " xmlns='http://www.w3.org/1999/xhtml'><body><h1 id='start'>Opening</h1><p>A"
              + " paragraph with <em>emphasis</em> and <strong>bold</strong>.</p></body></html>");
    }
    try (Epub book = new Epub(fixture)) {
      check(
          "EPUB 2 with NCX navigation is supported",
          book.title.equals("EPUB Two Check")
              && book.toc.size() == 1
              && book.toc.get(0).label.equals("Opening"));
      check(
          "XHTML doctype and inline formatting read offline",
          book.plainText(0).contains("emphasis and bold"));
    }
    fixture.delete();
    File bad = new File(getTargetContext().getCacheDir(), "bad-epub-check.epub");
    try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(bad))) {
      entry(
          z,
          "META-INF/container.xml",
          "<!DOCTYPE container [<!ENTITY local SYSTEM"
              + " 'file:///etc/hosts'>]><container>&local;</container>");
    }
    boolean blocked = false;
    try (Epub ignored = new Epub(bad)) {
    } catch (Exception e) {
      blocked = true;
    }
    check("EPUB entity declarations cannot read local files", blocked);
    bad.delete();
  }

  void writeReport() throws Exception {
    report.put("checks", checks);
    try (Writer out =
        new OutputStreamWriter(
            new FileOutputStream(new File(getTargetContext().getFilesDir(), "verification.json")),
            "UTF-8")) {
      out.write(report.toString(2));
    }
  }
}
