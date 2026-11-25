package io.duoreader;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Copies explicitly selected documents; only the destination folder grant is retained. */
final class LibraryImporter {
  static final int MAX_FILES = 64;

  static final class Result {
    int imported, failed;
    Library.Entry last;
    final ArrayList<String> errors = new ArrayList<>();

    String summary() {
      return imported
          + (imported == 1 ? " file imported" : " files imported")
          + (failed == 0 ? "" : " · " + failed + " not imported")
          + ". Originals were kept."
          + (errors.isEmpty() ? "" : "\n\n" + String.join("\n", errors));
    }
  }

  static ArrayList<Uri> selected(Intent data) throws IOException {
    LinkedHashSet<Uri> sources = new LinkedHashSet<>();
    if (data != null) {
      if (data.getData() != null) sources.add(data.getData());
      ClipData clip = data.getClipData();
      if (clip != null) {
        if (clip.getItemCount() > MAX_FILES)
          throw new IOException("Select up to 64 files at a time");
        for (int i = 0; i < clip.getItemCount(); i++) {
          Uri uri = clip.getItemAt(i).getUri();
          if (uri != null) sources.add(uri);
        }
      }
    }
    if (sources.size() > MAX_FILES) throw new IOException("Select up to 64 files at a time");
    return new ArrayList<>(sources);
  }

  static Result copy(
      Context context, String root, String parent, List<Uri> sources, Consumer<String> progress)
      throws Exception {
    if (sources.size() > MAX_FILES) throw new IOException("Select up to 64 files at a time");
    recover(context);
    LibraryStorage storage = new LibraryStorage(context, root);
    Result result = new Result();
    for (int i = 0; i < sources.size(); i++) {
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("Import interrupted");
      Uri source = sources.get(i);
      String name = "Selected file " + (i + 1);
      long size = -1;
      progress.accept("Importing " + (i + 1) + " of " + sources.size() + "…");
      try {
        if ("file".equals(source.getScheme())) {
          File file = new File(source.getPath());
          name = file.getName();
          size = file.length();
        } else if ("content".equals(source.getScheme())) {
          try (Cursor cursor =
              context
                  .getContentResolver()
                  .query(
                      source,
                      new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                      null,
                      null,
                      null)) {
            if (cursor == null || !cursor.moveToFirst())
              throw new IOException("Source is unavailable");
            int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
          }
        } else throw new IOException("Select a file from Android’s document picker");
        result.last =
            storage.importFile(
                parent,
                source.toString(),
                name,
                size,
                created -> record(context, root, parent, created));
        journal(context).delete();
        result.imported++;
      } catch (Exception error) {
        // The source is read-only throughout. Remove only this transaction's new destination.
        recover(context);
        result.failed++;
        if (result.errors.size() < 8)
          result.errors.add(
              name
                  + ": "
                  + (error instanceof SecurityException
                      ? "Read or write permission is missing"
                      : error.getMessage()));
      }
    }
    return result;
  }

  private static AtomicFile journal(Context context) {
    return new AtomicFile(new File(context.getFilesDir(), "pending-import.json"));
  }

  static void record(Context context, String root, String parent, Library.Entry created)
      throws Exception {
    JSONObject value =
        new JSONObject()
            .put("root", root)
            .put("parent", parent)
            .put("uri", created.uri)
            .put("name", created.name);
    TextDocument.writeAtomic(
        new File(context.getFilesDir(), "pending-import.json"), value.toString().getBytes("UTF-8"));
  }

  static void recover(Context context) throws Exception {
    File base = new File(context.getFilesDir(), "pending-import.json");
    if (!base.isFile() && !new File(base.getPath() + ".bak").isFile()) return;
    JSONObject pending;
    try (InputStream input = journal(context).openRead();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      EpubCache.copy(input, output, 32768);
      pending = new JSONObject(output.toString("UTF-8"));
    }
    String parent = pending.getString("parent"), uri = pending.getString("uri");
    for (Library.Entry entry : Library.list(context, parent))
      if (entry.uri.equals(uri)) {
        new LibraryStorage(context, pending.getString("root")).delete(parent, entry);
        break;
      }
    journal(context).delete();
  }
}
