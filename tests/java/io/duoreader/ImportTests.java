package io.duoreader;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.View;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ImportTests extends FeatureTest {
  LibraryImporter.Result copy(String root, String parent, Uri... sources) throws Exception {
    return LibraryImporter.copy(app, root, parent, Arrays.asList(sources), text -> {});
  }

  void exercise() throws Exception {
    File fixture = new File(app.getCacheDir(), "import-fixtures-" + System.nanoTime());
    fixture.mkdirs();
    File outside = new File(fixture, "Outside"), inside = new File(fixture, "Library");
    outside.mkdir();
    inside.mkdir();
    File notes = new File(outside, "Notes.txt");
    TextDocument.writeAtomic(notes, "Imported\nUnicode 😀".getBytes(StandardCharsets.UTF_8));
    File existing = new File(inside, "Notes.txt");
    TextDocument.writeAtomic(existing, "Keep existing".getBytes(StandardCharsets.UTF_8));
    String root = Uri.fromFile(inside).toString();
    Uri source = Uri.fromFile(notes);
    LibraryImporter.Result result = copy(root, root, source, source);
    check(
        "Import copies from outside the managed folder",
        result.imported == 2 && result.failed == 0);
    check(
        "Duplicate names receive unique suffixes",
        new File(inside, "Notes (1).txt").isFile() && new File(inside, "Notes (2).txt").isFile());
    check(
        "Existing files and source content stay untouched",
        TextDocument.load(app, Uri.fromFile(existing).toString()).text.equals("Keep existing")
            && TextDocument.load(app, source.toString()).text.equals("Imported\nUnicode 😀"));
    check(
        "Copied bytes survive verification",
        TextDocument.load(app, result.last.uri).text.equals("Imported\nUnicode 😀"));
    File markdown = new File(outside, "Readme.md");
    TextDocument.writeAtomic(markdown, "# Markdown\n".getBytes(StandardCharsets.UTF_8));
    for (File file :
        new File[] {
          TestBooks.create(app),
          markdown,
          TestComics.zip(app),
          TestComics.rar4(app),
          TestComics.rar5(app)
        }) {
      result = copy(root, root, Uri.fromFile(file));
      check("Import accepts " + file.getName(), result.imported == 1 && result.failed == 0);
    }
    File unknown = new File(outside, "Unsupported.pdf"),
        tooLarge = new File(outside, "TooLarge.txt");
    TextDocument.writeAtomic(unknown, new byte[] {1, 2});
    try (RandomAccessFile file = new RandomAccessFile(tooLarge, "rw")) {
      file.setLength(TextDocument.MAX_BYTES + 1L);
    }
    result = copy(root, root, Uri.fromFile(unknown), Uri.fromFile(tooLarge));
    check(
        "Unsupported and oversized files are reported without partial copies",
        result.failed == 2
            && result.imported == 0
            && !new File(inside, "Unsupported.pdf").exists()
            && !new File(inside, "TooLarge.txt").exists());
    File partial = new File(inside, "Interrupted.epub");
    TextDocument.writeAtomic(partial, new byte[] {1, 2, 3});
    LibraryImporter.record(
        app,
        root,
        root,
        new Library.Entry(Uri.fromFile(partial).toString(), partial.getName(), false));
    LibraryImporter.recover(app);
    check(
        "Interrupted imports remove only their own incomplete destination",
        !partial.exists() && notes.exists() && existing.exists());
    Intent selected = new Intent().setData(source);
    ClipData clip = ClipData.newRawUri("Files", source);
    clip.addItem(new ClipData.Item(Uri.fromFile(markdown)));
    selected.setClipData(clip);
    check(
        "Picker results deduplicate single and multiple selections",
        LibraryImporter.selected(selected).size() == 2);
    for (int i = 0; i < 64; i++) clip.addItem(new ClipData.Item(source));
    boolean bounded = false;
    try {
      LibraryImporter.selected(selected);
    } catch (IOException expected) {
      bounded = true;
    }
    check("Import selection count is bounded", bounded);
    Bundle options = new Bundle();
    app.getContentResolver().call(TestDocumentsProvider.AUTHORITY, "configure", null, options);
    String providerRoot =
        DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root").toString();
    LibraryStorage provider = new LibraryStorage(app, providerRoot);
    Library.Entry collection =
        provider.create(providerRoot, "Import-" + System.nanoTime(), DocumentFormat.TEXT, true);
    result = copy(providerRoot, collection.uri, source);
    check(
        "Import writes through Android document providers",
        result.imported == 1 && TextDocument.load(app, result.last.uri).text.contains("Unicode"));
    result = copy(root, root, Uri.parse(result.last.uri));
    check("Import reads from Android document providers", result.imported == 1 && notes.isFile());
    options.putBoolean("corrupt", true);
    app.getContentResolver().call(TestDocumentsProvider.AUTHORITY, "configure", null, options);
    int before = Library.list(app, collection.uri).size();
    result = copy(providerRoot, collection.uri, source);
    check(
        "Failed provider writes leave no partial destination",
        result.failed == 1 && Library.list(app, collection.uri).size() == before && notes.exists());
    options.putBoolean("corrupt", false);
    app.getContentResolver().call(TestDocumentsProvider.AUTHORITY, "configure", null, options);
    provider.delete(providerRoot, collection);
    String originalRoot = app.root, originalFolder = app.folder;
    ArrayDeque<String> parents = new ArrayDeque<>(app.folders);
    try {
      ui(() -> app.setRoot(root));
      until(() -> !app.loadingFolder, "test library");
      ui(() -> app.bottom.importFiles());
      until(
          () -> app.bottom.getWindow().getDecorView().getWindowVisibility() != View.VISIBLE,
          "file picker");
      check(
          "Import picker leaves the larger display visible",
          !app.pairedTasks.minimized && app.top.isForeground());
      for (int attempt = 0;
          attempt < 3
              && app.bottom.getWindow().getDecorView().getWindowVisibility() != View.VISIBLE;
          attempt++) {
        try (ParcelFileDescriptor command =
                getUiAutomation().executeShellCommand("input -d 2 keyevent 4");
            InputStream input = new FileInputStream(command.getFileDescriptor())) {
          byte[] bytes = new byte[256];
          while (input.read(bytes) != -1) {}
        }
        SystemClock.sleep(350);
      }
      until(
          () -> app.bottom.getWindow().getDecorView().getWindowVisibility() == View.VISIBLE,
          "cancel picker");
      check(
          "Cancelling import leaves the destination and selection intact",
          app.root.equals(root) && !app.busy && !app.pairedTasks.minimized);
    } finally {
      ui(
          () -> {
            app.pairedTasks.externalActivityReturned();
            app.setRoot(originalRoot);
            app.folders.clear();
            app.folders.addAll(parents);
            app.loadFolder(originalFolder);
          });
      until(() -> !app.loadingFolder, "restore library");
    }
  }
}
