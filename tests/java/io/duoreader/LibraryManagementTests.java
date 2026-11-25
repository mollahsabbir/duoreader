package io.duoreader;

import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

public final class LibraryManagementTests extends FeatureTest {
  View find(View root, String text) {
    if (root instanceof TextView && ((TextView) root).getText().toString().equals(text))
      return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View found = find(((ViewGroup) root).getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  NumberedText field(View root) {
    if (root instanceof NumberedText) return (NumberedText) root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        NumberedText found = field(((ViewGroup) root).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  void click(String text) {
    ui(
        () -> {
          View root = app.controls.dialog == null ? app.controls.root : app.controls.dialogSurface;
          View button = find(root, text);
          if (button == null) throw new AssertionError("Missing " + text);
          button.performClick();
        });
  }

  void create(String kind, String name) throws Exception {
    ui(() -> app.libraryManager.createMenu());
    click(kind);
    ui(
        () -> {
          NumberedText field = field(app.controls.dialogSurface);
          field.setText(name);
          field.setSelection(field.length());
        });
    click("Create");
    until(
        () ->
            !app.busy
                && !app.loadingFolder
                && app.entries.stream().anyMatch(e -> e.name.startsWith(name)),
        "created " + name);
  }

  Library.Entry entry(String name) {
    for (Library.Entry item : app.entries) if (item.name.equals(name)) return item;
    throw new AssertionError("Missing item " + name);
  }

  void exercise() throws Exception {
    String root = app.root, folder = app.folder;
    ArrayDeque<String> parents = new ArrayDeque<>(app.folders);
    String mode = app.mode;
    File fixtures = new File(app.getCacheDir(), "management-fixtures-" + System.nanoTime());
    fixtures.mkdirs();
    try {
      ui(() -> app.setRoot(Uri.fromFile(fixtures).toString()));
      until(() -> !app.loadingFolder, "fixture library");
      check("Library exposes a creation button", find(app.controls.root, "+") != null);
      create("New collection", "Collection");
      create("New TXT file", "Created");
      check("Creation uses the correct TXT extension", entry("Created.txt") != null);
      Library.Entry text = entry("Created.txt");
      ui(() -> app.open(text));
      until(() -> !app.busy && app.mode.equals("editing"), "created text editor");
      ui(
          () -> {
            NumberedText source = field(app.top.pageHost);
            source.insert("Unsaved text that follows a move");
            app.library();
          });
      ui(() -> app.libraryManager.manage(text));
      click("Move to collection…");
      until(
          () ->
              app.controls.dialog != null
                  && find(app.controls.dialogSurface, "Collection  ›") != null,
          "destinations");
      click("Collection  ›");
      until(
          () ->
              app.controls.dialog != null
                  && find(app.controls.dialogSurface, "Parent collection") != null,
          "collection destination");
      ui(
          () -> {
            find(app.controls.dialogSurface, "Move here").performClick();
            app.close();
          });
      until(
          () -> app.top == null && app.bottom == null && !app.libraryManager.isWorking(),
          "closed move completed");
      String movedUri = Uri.fromFile(new File(fixtures, "Collection/Created.txt")).toString();
      check(
          "Closing during a move preserves the new resume location",
          app.prefs.getString("lastUri", "").equals(movedUri));
      getTargetContext()
          .startActivity(
              new android.content.Intent(getTargetContext(), MainActivity.class)
                  .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
      until(() -> app.top != null && app.bottom != null && !app.loadingFolder, "reopen after move");
      ui(() -> app.controls.resumeButton.performClick());
      until(() -> app.mode.equals("editing") && !app.busy, "moved document resume");
      check(
          "Moved documents recover their private drafts",
          app.editor.content().equals("Unsaved text that follows a move"));
      ui(() -> app.library());
      Library.Entry collection = entry("Collection");
      check(
          "Move control moves a file into its collection",
          new File(fixtures, "Collection/Created.txt").isFile());
      ui(() -> app.libraryManager.manage(collection));
      click("Delete collection…");
      check(
          "Deleting a collection requires explicit confirmation",
          new File(fixtures, "Collection/Created.txt").isFile()
              && find(app.controls.dialogSurface, "Delete Collection and everything inside")
                  != null);
      click("Delete Collection and everything inside");
      until(() -> !app.busy && !app.loadingFolder, "delete completed");
      check(
          "Confirmed deletion removes contents and refreshes library",
          app.entries.isEmpty() && !new File(fixtures, "Collection").exists());
      create("New Markdown file", "Markdown");
      check("Creation uses the correct Markdown extension", entry("Markdown.md") != null);
    } finally {
      ui(
          () -> {
            if (app.controls.dialog != null) app.controls.dialog.dismiss();
            app.setRoot(root);
            app.folders.clear();
            app.folders.addAll(parents);
            app.loadFolder(folder);
            if (!mode.equals("library")) app.resume();
          });
      until(() -> !app.loadingFolder, "restore library");
    }
  }
}
