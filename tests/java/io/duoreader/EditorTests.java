package io.duoreader;

import android.net.Uri;
import android.text.*;
import android.text.style.StyleSpan;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class EditorTests extends FeatureTest {
  View find(View root, String title) {
    if (root instanceof TextView && ((TextView) root).getText().toString().equals(title))
      return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View found = find(((ViewGroup) root).getChildAt(i), title);
        if (found != null) return found;
      }
    return null;
  }

  NumberedText source(View root) {
    if (root instanceof NumberedText) return (NumberedText) root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        NumberedText found = source(((ViewGroup) root).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  AppKeyboard keyboard(View root) {
    if (root instanceof AppKeyboard) return (AppKeyboard) root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        AppKeyboard found = keyboard(((ViewGroup) root).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  void click(String title) {
    ui(
        () -> {
          View button = find(app.controls.root, title);
          if (button == null) throw new AssertionError("Missing " + title);
          button.performClick();
        });
  }

  void open(File file) throws Exception {
    ui(
        () -> {
          app.open(new Library.Entry(Uri.fromFile(file).toString(), file.getName(), false));
        });
    until(
        () -> !app.busy && app.mode.equals("editing") && app.current.name.equals(file.getName()),
        "editor open");
  }

  void key(int key, boolean upper) {
    ui(
        () -> {
          android.app.Activity target = upper ? app.top : app.bottom;
          target.dispatchKeyEvent(new ReaderInput.ScreenKey(KeyEvent.ACTION_DOWN, key, 0));
          target.dispatchKeyEvent(new ReaderInput.ScreenKey(KeyEvent.ACTION_UP, key, 0));
        });
  }

  void controllerKey(int key) {
    ui(
        () -> {
          for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP})
            app.top.dispatchKeyEvent(
                new KeyEvent(0, 0, action, key, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD));
        });
  }

  void captureKeyboard() {
    View root = app.controls.root;
    android.graphics.Bitmap bitmap =
        android.graphics.Bitmap.createBitmap(
            root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.RGB_565);
    try (OutputStream output =
        new FileOutputStream(new File(app.getFilesDir(), "editor-keyboard.png"))) {
      root.draw(new android.graphics.Canvas(bitmap));
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
    } catch (IOException error) {
      throw new AssertionError(error);
    } finally {
      bitmap.recycle();
    }
  }

  void cursorControls(File directory) throws Exception {
    File fixture = new File(directory, "cursor-navigation.txt");
    StringBuilder text = new StringBuilder("A😀BC\n");
    for (int i = 0; i < 90; i++)
      text.append("A long line that wraps across the display. ")
          .append(i)
          .append(' ')
          .append("Keep moving through the same logical line. ")
          .append('\n');
    TextDocument.writeAtomic(fixture, text.toString().getBytes(StandardCharsets.UTF_8));
    new android.util.AtomicFile(
            new File(
                app.getFilesDir(),
                "draft-" + Library.id(Uri.fromFile(fixture).toString()) + ".json"))
        .delete();
    open(fixture);
    NumberedText input = source(app.top.pageHost);
    until(() -> input.getLayout() != null, "cursor layout");
    check("D-pad defaults to cursor movement", find(app.controls.root, "D-pad: Cursor") != null);
    ui(() -> input.setSelection(1));
    key(KeyEvent.KEYCODE_DPAD_RIGHT, false);
    check(
        "D-pad right skips a whole emoji and key-up does not move twice",
        input.getSelectionStart() == 3);
    key(KeyEvent.KEYCODE_DPAD_LEFT, true);
    check("D-pad events from the larger display move the cursor", input.getSelectionStart() == 1);
    ui(
        () -> {
          app.controls.routeControllerKey(
              new ReaderInput.ScreenKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0));
          app.controls.routeControllerKey(
              new ReaderInput.ScreenKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1));
          app.controls.routeControllerKey(
              new ReaderInput.ScreenKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0));
        });
    check("Held D-pad repeats cursor movement", input.getSelectionStart() == 4);
    ui(() -> input.setSelection(0));
    key(KeyEvent.KEYCODE_DPAD_LEFT, false);
    key(KeyEvent.KEYCODE_DPAD_UP, false);
    check("Cursor stays within the start of the document", input.getSelectionStart() == 0);
    ui(() -> input.setSelection(input.length()));
    key(KeyEvent.KEYCODE_DPAD_RIGHT, false);
    key(KeyEvent.KEYCODE_DPAD_DOWN, false);
    check(
        "Cursor stays within the end of the document", input.getSelectionStart() == input.length());
    int start = text.indexOf("A long") + 5;
    ui(() -> input.setSelection(start));
    int line = input.getLayout().getLineForOffset(start);
    key(KeyEvent.KEYCODE_DPAD_DOWN, false);
    check(
        "Down follows a wrapped visual line",
        input.getLayout().getLineForOffset(input.getSelectionStart()) == line + 1);
    key(KeyEvent.KEYCODE_DPAD_UP, false);
    check("Up returns to the preceding visual line", input.getSelectionStart() == start);
    ui(
        () -> {
          input.setSelection(0);
          for (int i = 0; i < 100; i++)
            app.controls.routeControllerKey(
                new ReaderInput.ScreenKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, i));
          app.controls.routeControllerKey(
              new ReaderInput.ScreenKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0));
        });
    until(() -> input.getScrollY() > 0, "scroll to caret");
    check("Cursor movement scrolls the larger display to the caret", input.getScrollY() > 0);
    int caret = input.getSelectionStart();
    key(KeyEvent.KEYCODE_BUTTON_SELECT, false);
    View focus = app.bottom.getCurrentFocus();
    check(
        "Select enables button-only keyboard navigation",
        find(app.controls.root, "D-pad: Keys") != null && focus instanceof Button);
    key(KeyEvent.KEYCODE_DPAD_RIGHT, true);
    check(
        "Keyboard navigation moves focus without moving the cursor",
        app.bottom.getCurrentFocus() != focus && input.getSelectionStart() == caret);
    int length = input.length();
    key(KeyEvent.KEYCODE_BUTTON_A, true);
    check("A types the focused key", input.length() == length + 1);
    key(KeyEvent.KEYCODE_BUTTON_SELECT, true);
    caret = input.getSelectionStart();
    key(KeyEvent.KEYCODE_DPAD_LEFT, false);
    check(
        "Select returns to cursor movement",
        find(app.controls.root, "D-pad: Cursor") != null && input.getSelectionStart() == caret - 1);
    click("q");
    caret = input.getSelectionStart();
    key(KeyEvent.KEYCODE_DPAD_RIGHT, false);
    check(
        "Touch typing keeps D-pad cursor movement available",
        input.getSelectionStart() == caret + 1);
  }

  void exercise() throws Exception {
    File directory = new File(getTargetContext().getCacheDir(), "editor-fixtures");
    directory.mkdirs();
    File markdown = new File(directory, "sample.md"), plain = new File(directory, "sample.txt");
    TextDocument.writeAtomic(
        markdown, "# Heading\n\nA **bold** test.\n".getBytes(StandardCharsets.UTF_8));
    TextDocument.writeAtomic(plain, "First\r\nSecond\r\n".getBytes(StandardCharsets.UTF_8));
    TextDocument loaded = TextDocument.load(app, Uri.fromFile(plain).toString());
    check("CRLF input normalizes to logical newlines", loaded.text.equals("First\nSecond\n"));
    loaded.save(app, "Edited\nSecond\n");
    check(
        "Saving preserves CRLF",
        Arrays.equals(
            TextDocument.read(app, loaded.uri),
            "Edited\r\nSecond\r\n".getBytes(StandardCharsets.UTF_8)));
    File sidecar = new File(plain.getPath() + ".bak");
    try (OutputStream output = new FileOutputStream(sidecar)) {
      output.write("User backup".getBytes(StandardCharsets.UTF_8));
    }
    loaded.save(app, "Another edit\n");
    check(
        "User-owned backup files are preserved",
        new String(TextDocument.read(app, Uri.fromFile(sidecar).toString()), StandardCharsets.UTF_8)
            .equals("User backup"));
    TextDocument.writeAtomic(plain, "External change".getBytes(StandardCharsets.UTF_8));
    boolean conflict = false;
    try {
      loaded.save(app, "overwrite");
    } catch (IOException expected) {
      conflict = true;
    }
    check(
        "External changes are protected",
        conflict && TextDocument.load(app, loaded.uri).text.equals("External change"));
    TextDocument recovered = TextDocument.load(app, loaded.uri);
    recovered.recoverAgainst(loaded.fingerprint());
    boolean recoveredConflict = false;
    try {
      recovered.save(app, "Recovered draft");
    } catch (IOException expected) {
      recoveredConflict = true;
    }
    check("Recovered drafts cannot overwrite externally changed originals", recoveredConflict);
    byte[] utf16 = "Hi\r\n😀".getBytes(StandardCharsets.UTF_16LE), bom = new byte[utf16.length + 2];
    bom[0] = (byte) 0xff;
    bom[1] = (byte) 0xfe;
    System.arraycopy(utf16, 0, bom, 2, utf16.length);
    TextDocument.writeAtomic(plain, bom);
    loaded = TextDocument.load(app, loaded.uri);
    loaded.save(app, "New\n😀");
    check(
        "UTF-16 BOM survives saving",
        TextDocument.read(app, loaded.uri)[0] == (byte) 0xff
            && TextDocument.load(app, loaded.uri).text.equals("New\n😀"));
    Spanned rendered =
        MarkdownPreview.render("# Heading\n\n**Bold** and *italic*\n<script>alert(1)</script>");
    check(
        "CommonMark preview preserves styles and treats raw HTML as text",
        rendered.toString().contains("Heading")
            && rendered.toString().contains("<script>")
            && rendered.getSpans(0, rendered.length(), StyleSpan.class).length >= 2);
    Spanned nested =
        MarkdownPreview.render(new String(new char[2000]).replace('\0', '>') + " deeply nested");
    check(
        "Pathological Markdown nesting is bounded",
        nested.toString().contains("deeply nested")
            && nested.getSpans(0, nested.length(), android.text.style.QuoteSpan.class).length
                <= 128);
    File manyLines = new File(directory, "TooManyLines.txt");
    TextDocument.writeAtomic(
        manyLines,
        new String(new char[TextDocument.MAX_LINES])
            .replace('\0', '\n')
            .getBytes(StandardCharsets.UTF_8));
    boolean lineLimit = false;
    try {
      TextDocument.load(app, Uri.fromFile(manyLines).toString());
    } catch (IOException expected) {
      lineLimit = true;
    }
    check("Pathological text line counts are rejected before layout", lineLimit);
    app.editor.awaitWrites();
    for (File fixture : new File[] {markdown, plain}) {
      new android.util.AtomicFile(
              new File(
                  app.getFilesDir(),
                  "draft-" + Library.id(Uri.fromFile(fixture).toString()) + ".json"))
          .delete();
    }
    int rotation = app.rotation;
    Library.Entry original = app.current;
    ReaderApp.Location position = app.position;
    String mode = app.mode;
    try {
      open(markdown);
      check(
          "Large screen owns numbered source",
          source(app.top.pageHost) != null && source(app.controls.root) == null);
      NumberedText input = source(app.top.pageHost);
      ui(() -> input.setSelection(input.length()));
      click("Enter");
      click("q");
      check("Shared keyboard edits the larger display", input.getText().toString().endsWith("\nq"));
      click("Undo");
      check("Undo restores the preceding edit", input.getText().toString().endsWith("\n"));
      click("Redo");
      check("Redo reapplies the edit", input.getText().toString().endsWith("\nq"));
      click("Source / Preview");
      until(() -> input.getVisibility() == View.GONE, "preview");
      check(
          "Markdown preview replaces source on larger display", input.getVisibility() == View.GONE);
      click("Source / Preview");
      click("Save");
      until(
          () ->
              find(app.controls.root, "Saved · sample.md · " + input.length() + " characters")
                  != null,
          "save");
      check(
          "Explicit save updates original Markdown file",
          TextDocument.load(app, Uri.fromFile(markdown).toString()).text.endsWith("\nq"));
      ui(() -> input.insert(" unsaved"));
      ui(() -> app.editor.flush());
      File draft =
          new File(
              app.getFilesDir(),
              "draft-" + Library.id(Uri.fromFile(markdown).toString()) + ".json");
      until(draft::isFile, "draft persisted");
      ui(() -> app.library());
      open(plain);
      open(markdown);
      check(
          "Reopening recovers unsaved text",
          source(app.top.pageHost).getText().toString().endsWith(" unsaved"));
      cursorControls(directory);
      for (int angle : new int[] {0, 90, 180, 270}) {
        ui(
            () -> {
              app.rotation = angle;
              app.appearance();
            });
        until(() -> app.controls.root.getRotation() == angle, "rotation");
        check(
            "Editor keyboard fits rotation " + angle,
            find(app.controls.root, "Enter").getHeight() >= app.controls.dp(20));
        AppKeyboard keys = keyboard(app.controls.root);
        check(
            "Editor leaves space above a compact keyboard at rotation " + angle,
            keys.getHeight() < app.controls.root.getHeight() * .6f);
        check(
            "Editor keyboard stays at the bottom at rotation " + angle,
            Math.abs(keys.getBottom() - app.controls.root.getHeight()) <= app.controls.dp(8));
        NumberedText caret = source(app.top.pageHost);
        ui(() -> caret.setSelection(0));
        int[] directions = {
          KeyEvent.KEYCODE_DPAD_UP,
          KeyEvent.KEYCODE_DPAD_RIGHT,
          KeyEvent.KEYCODE_DPAD_DOWN,
          KeyEvent.KEYCODE_DPAD_LEFT
        };
        int physicalRight = directions[(1 + angle / 90) % 4];
        controllerKey(physicalRight);
        check("D-pad cursor follows rotation " + angle, caret.getSelectionStart() == 1);
        key(KeyEvent.KEYCODE_BUTTON_SELECT, false);
        controllerKey(physicalRight);
        check(
            "Keyboard focus follows rotation " + angle,
            app.bottom.getCurrentFocus() instanceof Button
                && ((Button) app.bottom.getCurrentFocus()).getText().toString().equals("2")
                && caret.getSelectionStart() == 1);
        key(KeyEvent.KEYCODE_BUTTON_SELECT, false);
        if (angle == 90) ui(this::captureKeyboard);
      }
    } finally {
      ui(
          () -> {
            if (app.controls.dialog != null) app.controls.dialog.dismiss();
            app.library();
            app.rotation = rotation;
            app.appearance();
          });
      if (original != null) {
        ui(() -> app.open(original));
        until(() -> !app.busy, "restore original book");
        ui(
            () -> {
              if (app.book != null) app.jump(position);
              if (mode.equals("library")) app.library();
            });
      }
    }
  }
}
