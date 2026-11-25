package io.duoreader;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class EditorSelectionTests extends FeatureTest {
  private int onlyAngle = -1;
  private NumberedText input;
  private final String originalText = "Alpha Bravo Charlie.\nSecond line with words to select.\n";

  @Override
  public void onCreate(android.os.Bundle args) {
    onlyAngle = Integer.parseInt(args.getString("angle", "-1"));
    super.onCreate(args);
  }

  private void nativeScreenshot(String name) throws Exception {
    Bitmap bitmap = getUiAutomation().takeScreenshot();
    if (bitmap == null) return;
    try (OutputStream output = new FileOutputStream(new File(app.getFilesDir(), name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
    } finally {
      bitmap.recycle();
    }
  }

  private View find(View root, String title) {
    if (title.contentEquals(
            root.getContentDescription() == null ? "" : root.getContentDescription())
        || root instanceof TextView && title.contentEquals(((TextView) root).getText()))
      return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View found = find(((ViewGroup) root).getChildAt(i), title);
        if (found != null) return found;
      }
    return null;
  }

  private NumberedText field(View root) {
    if (root instanceof NumberedText) return (NumberedText) root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        NumberedText found = field(((ViewGroup) root).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private void shell(int display, String command) throws Exception {
    try (ParcelFileDescriptor fd =
            getUiAutomation().executeShellCommand("input -d " + display + " " + command);
        InputStream stream = new FileInputStream(fd.getFileDescriptor())) {
      byte[] buffer = new byte[256];
      while (stream.read(buffer) != -1) {}
    }
    waitForIdleSync();
  }

  private void tap(View root, String title, int display) throws Exception {
    until(
        () -> {
          View button = find(root, title);
          return button != null
              && button.isShown()
              && button.getWidth() > 0
              && button.getHeight() > 0
              && !button.isLayoutRequested();
        },
        title + " layout");
    int[] point = new int[2];
    ui(
        () -> {
          View button = find(root, title);
          if (button == null) throw new AssertionError("Missing " + title);
          int[] screen =
              ScreenCoordinates.point(button, button.getWidth() / 2f, button.getHeight() / 2f);
          point[0] = screen[0];
          point[1] = screen[1];
        });
    shell(display, "tap " + point[0] + " " + point[1]);
  }

  private void setClipboard(ClipboardManager clipboard, String value) throws Exception {
    setClipboard(clipboard, ClipData.newPlainText("Fixture", value));
  }

  private void setClipboard(ClipboardManager clipboard, ClipData value) throws Exception {
    ui(() -> clipboard.setPrimaryClip(value));
    // Android's clipboard preview is a system window above the app. Let it expire
    // before testing the actual popup hit targets in every orientation.
    android.os.SystemClock.sleep(6500);
  }

  private void hold(int offset) throws Exception {
    int[] point = new int[2];
    ui(
        () -> {
          input.bringPointIntoView(offset);
          Layout layout = input.getLayout();
          int line = layout.getLineForOffset(offset);
          int[] screen =
              ScreenCoordinates.point(
                  input,
                  input.length() == 0
                      ? input.getWidth() / 2f
                      : input.getTotalPaddingLeft()
                          + layout.getPrimaryHorizontal(offset)
                          - input.getScrollX(),
                  input.length() == 0
                      ? input.getHeight() / 2f
                      : input.getTotalPaddingTop()
                          + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
                          - input.getScrollY());
          point[0] = screen[0];
          point[1] = screen[1];
        });
    shell(0, "swipe " + point[0] + " " + point[1] + " " + point[0] + " " + point[1] + " 700");
  }

  private void screenshot() {
    View root = app.top.pageHost;
    Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.RGB_565);
    try (OutputStream output =
        new FileOutputStream(new File(app.getFilesDir(), "editor-selection.png"))) {
      root.draw(new Canvas(bitmap));
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
    } catch (IOException error) {
      throw new AssertionError(error);
    } finally {
      bitmap.recycle();
    }
  }

  void exercise() throws Exception {
    ClipboardManager clipboard = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
    ClipData previousClip = clipboard.getPrimaryClip();
    Library.Entry previous = app.current;
    ReaderApp.Location position = app.position;
    String mode = app.mode;
    int rotation = app.rotation;
    File directory = new File(app.getCacheDir(), "selection-fixtures");
    directory.mkdirs();
    try {
      for (int angle : new int[] {0, 90, 180, 270}) {
        if (onlyAngle >= 0 && angle != onlyAngle) continue;
        File fixture =
            new File(directory, "selection-" + angle + (angle % 180 == 0 ? ".txt" : ".md"));
        TextDocument.writeAtomic(fixture, originalText.getBytes(StandardCharsets.UTF_8));
        new android.util.AtomicFile(
                new File(
                    app.getFilesDir(),
                    "draft-" + Library.id(Uri.fromFile(fixture).toString()) + ".json"))
            .delete();
        ui(
            () -> {
              app.rotation = angle;
              app.appearance();
              app.open(
                  new Library.Entry(Uri.fromFile(fixture).toString(), fixture.getName(), false));
            });
        until(
            () -> !app.busy && app.mode.equals("editing") && field(app.top.pageHost) != null,
            "editor");
        input = field(app.top.pageHost);
        until(
            () -> input.getLayout() != null && app.top.pageHost.getRotation() == angle,
            "source layout");
        setClipboard(clipboard, "Pasted\r\n😀");
        hold(8);
        until(() -> find(app.top.pageHost, "Copy") != null, "copy popup at " + angle);
        int a = Math.min(input.getSelectionStart(), input.getSelectionEnd());
        int b = Math.max(input.getSelectionStart(), input.getSelectionEnd());
        check(
            "Larger-screen long press selects a word at " + angle,
            input.getText().subSequence(a, b).toString().equals("Bravo"));
        View toolbar = (View) find(app.top.pageHost, "Copy").getParent();
        check(
            "Popup stays inside the rotated source at " + angle,
            toolbar.getTop() >= 0
                && toolbar.getBottom() <= input.getHeight()
                && find(app.controls.root, "Copy") == null
                && !input.getShowSoftInputOnFocus());
        if (angle == 90) {
          ui(this::screenshot);
          nativeScreenshot("editor-native-selection");
        }
        tap(app.top.pageHost, "Copy", 0);
        check(
            "Copy preserves text and puts selection on clipboard at " + angle,
            input.getText().toString().equals(originalText)
                && clipboard.getPrimaryClip().getItemAt(0).getText().toString().equals("Bravo"));
        setClipboard(clipboard, "Pasted\r\n😀");
        hold(8);
        until(() -> find(app.top.pageHost, "Paste") != null, "paste popup");
        tap(app.top.pageHost, "Paste", 0);
        check(
            "Paste normalizes Windows newlines and replaces Unicode selection at " + angle,
            input.getText().toString().equals(originalText.replace("Bravo", "Pasted\n😀")));
        tap(app.controls.root, "Undo", 2);
        check(
            "Paste participates in undo history at " + angle,
            input.getText().toString().equals(originalText));
        hold(8);
        until(() -> find(app.top.pageHost, "Select all") != null, "select all popup");
        tap(app.top.pageHost, "Select all", 0);
        check(
            "Select all covers the document at " + angle,
            Math.min(input.getSelectionStart(), input.getSelectionEnd()) == 0
                && Math.max(input.getSelectionStart(), input.getSelectionEnd()) == input.length());
        ui(() -> input.setSelection(input.length(), 0));
        tap(app.controls.root, "q", 2);
        check(
            "Smaller keyboard replaces a reversed selection at " + angle,
            input.getText().toString().equals("q"));
        tap(app.controls.root, "Undo", 2);
        check(
            "Undo restores selection replacement at " + angle,
            input.getText().toString().equals(originalText));
        hold(8);
        until(() -> find(app.top.pageHost, "Copy") != null, "selection before library");
        ui(() -> app.library());
        check(
            "Leaving the editor dismisses its popup at " + angle,
            find(app.top.pageHost, "Copy") == null);
      }
      File empty = new File(directory, "empty.txt");
      TextDocument.writeAtomic(empty, new byte[0]);
      new android.util.AtomicFile(
              new File(
                  app.getFilesDir(),
                  "draft-" + Library.id(Uri.fromFile(empty).toString()) + ".json"))
          .delete();
      ui(() -> app.open(new Library.Entry(Uri.fromFile(empty).toString(), empty.getName(), false)));
      until(() -> !app.busy && app.current.name.equals(empty.getName()), "empty editor");
      input = field(app.top.pageHost);
      until(() -> input.getLayout() != null, "empty layout");
      setClipboard(clipboard, "First\nSecond");
      hold(0);
      until(() -> find(app.top.pageHost, "Paste") != null, "paste into empty source");
      tap(app.top.pageHost, "Paste", 0);
      check(
          "An empty document offers paste at the caret",
          input.getText().toString().equals("First\nSecond"));
      ui(() -> input.setSelection(input.length(), 0));
      tap(app.controls.root, "Backspace", 2);
      check("Backspace removes a reversed selection", input.length() == 0);
      tap(app.controls.root, "Undo", 2);
      check(
          "Deleting a selection can be undone", input.getText().toString().equals("First\nSecond"));
      tap(app.controls.root, "Undo", 2);
      ui(clipboard::clearPrimaryClip);
      hold(0);
      check(
          "An empty clipboard does not offer paste",
          input.length() == 0 && find(app.top.pageHost, "Paste") == null);
      setClipboard(clipboard, ClipData.newHtmlText("Fixture", "Bold", "<b>Bold</b>"));
      hold(0);
      until(() -> find(app.top.pageHost, "Paste") != null, "styled clipboard");
      tap(app.top.pageHost, "Paste", 0);
      check(
          "TXT and Markdown source paste plain text without rich formatting",
          input.getText().toString().equals("Bold")
              && input
                      .getText()
                      .getSpans(0, input.length(), android.text.style.StyleSpan.class)
                      .length
                  == 0);
    } finally {
      ui(
          () -> {
            if (previousClip == null) clipboard.clearPrimaryClip();
            else clipboard.setPrimaryClip(previousClip);
            app.library();
            app.rotation = rotation;
            app.appearance();
          });
      if (previous != null) {
        ui(() -> app.open(previous));
        until(() -> !app.busy, "restore previous document");
        ui(
            () -> {
              if (app.book != null) app.jump(position);
              if (mode.equals("library")) app.library();
            });
      }
    }
  }
}
