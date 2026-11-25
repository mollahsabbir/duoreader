package io.duoreader;

import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class StorageTests extends FeatureTest {
  void configure(boolean readOnly, boolean corrupt) {
    Bundle options = new Bundle();
    options.putBoolean("readOnly", readOnly);
    options.putBoolean("corrupt", corrupt);
    app.getContentResolver().call(TestDocumentsProvider.AUTHORITY, "configure", null, options);
  }

  void verify(String root) throws Exception {
    LibraryStorage storage = new LibraryStorage(app, root);
    String suffix = Long.toString(System.nanoTime());
    Library.Entry first = storage.create(root, "First-" + suffix, DocumentFormat.TEXT, true);
    Library.Entry second = storage.create(root, "Second-" + suffix, DocumentFormat.TEXT, true);
    Library.Entry text =
        storage.create(first.uri, "Original-" + suffix, DocumentFormat.TEXT, false);
    try (OutputStream output =
        text.uri.startsWith("file:")
            ? new FileOutputStream(Uri.parse(text.uri).getPath())
            : app.getContentResolver().openOutputStream(Uri.parse(text.uri), "rwt")) {
      output.write("Original text\n".getBytes(StandardCharsets.UTF_8));
    }
    boolean duplicate = false, escape = false, rootDelete = false;
    try {
      storage.create(first.uri, "Original-" + suffix, DocumentFormat.TEXT, false);
    } catch (IOException expected) {
      duplicate = true;
    }
    try {
      storage.create(first.uri, "../outside", DocumentFormat.TEXT, false);
    } catch (IOException expected) {
      escape = true;
    }
    try {
      storage.delete(root, new Library.Entry(root, "Root", true));
    } catch (IOException expected) {
      rootDelete = true;
    }
    check(
        root.startsWith("file:")
            ? "File mutations protect duplicates, paths, and root"
            : "Provider mutations protect duplicates, paths, and root",
        duplicate && escape && rootDelete);
    String oldUri = text.uri;
    app.prefs
        .edit()
        .putInt(app.key(oldUri) + "offset", 42)
        .putString(app.key(oldUri) + "marks", "[{\"note\":\"test\"}]")
        .commit();
    Library.Entry moved = storage.move(first.uri, text, second.uri);
    app.stateStore.relocate(oldUri, moved.uri);
    check(
        "Moved file retains exact content",
        TextDocument.load(app, moved.uri).text.equals("Original text\n"));
    check("Move removes the old file after verification", Library.list(app, first.uri).isEmpty());
    check(
        "Reading data migrates to the new URI",
        app.prefs.getInt(app.key(moved.uri) + "offset", 0) == 42
            && app.prefs.getString(app.key(moved.uri) + "marks", "").contains("test"));
    Library.Entry outer = storage.move(second.uri, moved, root);
    check(
        "Move out of collection works",
        TextDocument.load(app, outer.uri).text.equals("Original text\n"));
    storage.delete(root, outer);
    storage.delete(root, first);
    storage.delete(root, second);
    check(
        "Delete removes created collections",
        Library.list(app, root).stream()
            .noneMatch(e -> e.uri.equals(first.uri) || e.uri.equals(second.uri)));
  }

  void exercise() throws Exception {
    File directory = new File(app.getCacheDir(), "storage-fixtures");
    directory.mkdirs();
    verify(Uri.fromFile(directory).toString());
    configure(false, false);
    String root =
        DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root").toString();
    verify(root);
    LibraryStorage storage = new LibraryStorage(app, root);
    Library.Entry document =
        storage.create(root, "Provider-save-" + System.nanoTime(), DocumentFormat.MARKDOWN, false);
    TextDocument loaded = TextDocument.load(app, document.uri);
    loaded.save(app, "# Saved\n");
    check(
        "Provider editor writes and verifies saves",
        TextDocument.load(app, document.uri).text.equals("# Saved\n"));
    configure(false, true);
    boolean failure = false;
    try {
      loaded.save(app, "Replacement");
    } catch (IOException expected) {
      failure = true;
    }
    check(
        "Failed provider writes retain the original and recovery backup",
        failure
            && TextDocument.load(app, document.uri).text.equals("# Saved\n")
            && new File(app.getFilesDir(), "document-backup-" + Library.id(document.uri)).isFile());
    configure(true, false);
    boolean readOnly = false;
    try {
      storage.create(root, "Read-only", DocumentFormat.TEXT, false);
    } catch (IOException expected) {
      readOnly = true;
    }
    check("Read-only providers reject mutations before creation", readOnly);
    configure(false, false);
    storage.delete(root, document);
  }
}
