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
