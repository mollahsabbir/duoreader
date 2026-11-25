package io.duoreader;

import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Staged by test-documents.py, with a real process force-stop between stages. */
public final class DocumentCheckpoint extends FeatureTest {
  private Bundle arguments;
  private static final String ORIGINAL = "Original file.\n";
  private static final String EDITED = ORIGINAL + "Unsaved draft after restart 😀\nSecond line";

  @Override
  public void onCreate(Bundle args) {
    arguments = args;
    super.onCreate(args);
  }

  private NumberedText source(View view) {
    if (view instanceof NumberedText) return (NumberedText) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        NumberedText found = source(((ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  void exercise() throws Exception {
    String stage = arguments.getString("stage", "");
    if (stage.equals("prepareText")) {
      File file = new File(app.getCacheDir(), "checkpoint.txt");
      TextDocument.writeAtomic(file, ORIGINAL.getBytes(StandardCharsets.UTF_8));
      String uri = Uri.fromFile(file).toString();
      new android.util.AtomicFile(new File(app.getFilesDir(), "draft-" + Library.id(uri) + ".json"))
          .delete();
      ui(
          () -> {
            app.rotation = 270;
            app.appearance();
            app.open(new Library.Entry(uri, file.getName(), false));
          });
      until(
          () -> app.mode.equals("editing") && !app.busy && source(app.top.pageHost) != null,
          "text editor");
      ui(
          () -> {
            NumberedText field = source(app.top.pageHost);
            field.setSelection(field.length());
            field.insert(EDITED.substring(ORIGINAL.length()));
            app.save();
          });
      app.editor.awaitWrites();
      check(
          "Draft is durable before process termination",
          new File(app.getFilesDir(), "draft-" + Library.id(uri) + ".json").isFile());
      check(
          "Unsaved draft leaves the original file untouched",
          TextDocument.load(app, uri).text.equals(ORIGINAL));
    } else if (stage.equals("verifyText")) {
      until(
          () -> app.mode.equals("editing") && source(app.top.pageHost) != null,
          "cold-restored text");
      check(
          "Cold start restores the same text document", app.current.name.equals("checkpoint.txt"));
      check(
          "Cold start restores unsaved multiline Unicode text",
          app.editor.content().equals(EDITED));
      check("Cold start restores the editing cursor", app.editor.cursor() == EDITED.length());
      check(
          "Cold start keeps the original file untouched",
          TextDocument.load(app, app.current.uri).text.equals(ORIGINAL));
    } else if (stage.equals("prepareComic")) {
      File file = TestComics.zip(app);
      String uri = Uri.fromFile(file).toString();
      ui(
          () -> {
            app.prefs.edit().putInt(app.key(uri) + "comicPage", 0).commit();
            app.open(new Library.Entry(uri, file.getName(), false));
          });
      until(() -> app.mode.equals("comic") && !app.busy, "comic open");
      ui(
          () -> {
            app.comics.turn(1);
            app.save();
          });
      check(
          "Comic checkpoint records its next spread",
          app.prefs.getInt(app.bookKey() + "comicPage", -1) == 2);
    } else if (stage.equals("verifyComic")) {
      until(
          () ->
              app.mode.equals("comic")
                  && app.comics.page(false) != null
                  && app.comics.page(true) != null,
          "cold-restored comic");
      check("Cold start restores the same comic", app.current.name.equals("Sample.cbz"));
      check("Cold start restores the comic spread", app.comics.index() == 2);
      check("Cold start decodes both comic pages", app.comics.page(false) != app.comics.page(true));
    } else
      throw new IllegalArgumentException(
          "Use prepareText, verifyText, prepareComic, or verifyComic");
    check(
        "Internal orientation persists through process restarts",
        app.rotation == 270
            && app.top.pageHost.getRotation() == 270
            && app.controls.root.getRotation() == 270);
    check(
        "Android windows remain landscape after recovery",
        app.top.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            && app.bottom.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
  }
}
