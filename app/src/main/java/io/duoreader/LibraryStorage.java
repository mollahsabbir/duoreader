package io.duoreader;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.file.Files;
import java.security.*;
import java.util.*;

/** Storage mutations shared by the library UI and provider/file regression tests. */
final class LibraryStorage {
  private static final long MAX_COPY_BYTES = 2L * 1024 * 1024 * 1024;
  private final Context context;
  private final String root;

  LibraryStorage(Context context, String root) {
    this.context = context;
    this.root = root;
  }

  private static boolean local(String uri) {
    return "file".equals(Uri.parse(uri).getScheme());
  }

  private File checked(String uri, boolean allowRoot) throws IOException {
    if (!local(root) || !local(uri))
      throw new IOException("This location is outside the current library");
    File base = new File(Uri.parse(root).getPath()).getCanonicalFile(),
        file = new File(Uri.parse(uri).getPath()).getCanonicalFile();
    if (!file.toPath().startsWith(base.toPath()) || !allowRoot && file.equals(base))
      throw new IOException("The default library folder cannot be changed by this action");
    return file;
  }

  private Uri document(String uri) {
    Uri source = Uri.parse(uri);
    return DocumentsContract.buildDocumentUriUsingTree(
        source,
        DocumentsContract.isDocumentUri(context, source)
            ? DocumentsContract.getDocumentId(source)
            : DocumentsContract.getTreeDocumentId(source));
  }

  private void protectRoot(String uri) throws IOException {
    if (local(uri)) {
      checked(uri, false);
      return;
    }
    if (DocumentsContract.getDocumentId(document(uri))
        .equals(DocumentsContract.getDocumentId(document(root))))
      throw new IOException("The default library folder cannot be deleted or moved");
    if (!Uri.parse(uri).getAuthority().equals(Uri.parse(root).getAuthority())
        || !DocumentsContract.getTreeDocumentId(Uri.parse(uri))
            .equals(DocumentsContract.getTreeDocumentId(Uri.parse(root))))
      throw new IOException("This document is outside the current library grant");
  }

  private void require(String uri, int flag, String action) throws IOException {
    if (local(uri)) {
      File file = checked(uri, true);
      if (!(file.isDirectory() ? file : file.getParentFile()).canWrite())
        throw new IOException("This folder is read-only");
      return;
    }
    if (!supports(uri, flag))
      throw new IOException("The Android provider does not allow " + action + " here");
  }

  private boolean supports(String uri, int flag) {
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                document(uri),
                new String[] {DocumentsContract.Document.COLUMN_FLAGS},
                null,
                null,
                null)) {
      return cursor != null && cursor.moveToFirst() && (cursor.getInt(0) & flag) != 0;
    }
  }

  private void validateSource(String parent, Library.Entry source) throws Exception {
    protectRoot(source.uri);
    for (Library.Entry entry : Library.list(context, parent))
      if ((entry.uri.equals(source.uri)
              || local(entry.uri)
                  && local(source.uri)
                  && checked(entry.uri, false).equals(checked(source.uri, false)))
          && entry.folder == source.folder) return;
    throw new IOException("This item moved or was deleted. Refresh the library first.");
  }

  private String name(String name) throws IOException {
    String clean = name.trim();
    if (clean.isEmpty()
        || clean.equals(".")
        || clean.equals("..")
        || clean.startsWith(".")
        || clean.getBytes("UTF-8").length > 180
        || clean.matches("(?s).*[\\\\/\\p{Cntrl}].*"))
      throw new IOException(
          "Use a visible name without slashes, control characters, or more than 180 UTF-8 bytes");
    return clean;
  }

  private void requireFreeName(String parent, String name) throws Exception {
    if (local(parent)) {
      File folder = checked(parent, true);
      String[] names = folder.list();
      if (names == null) throw new IOException("Destination folder is unavailable");
      for (String existing : names)
        if (existing.equalsIgnoreCase(name))
          throw new IOException("An item with that name already exists");
    } else {
      Uri folder = document(parent);
      Uri children =
          DocumentsContract.buildChildDocumentsUriUsingTree(
              folder, DocumentsContract.getDocumentId(folder));
      try (Cursor cursor =
          context
              .getContentResolver()
              .query(
                  children,
                  new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                  null,
                  null,
                  null)) {
        if (cursor == null) throw new IOException("Destination provider is unavailable");
        while (cursor.moveToNext())
          if (name.equalsIgnoreCase(cursor.getString(0)))
            throw new IOException("An item with that name already exists");
      }
    }
  }

  Library.Entry create(String parent, String requested, DocumentFormat format, boolean folder)
      throws Exception {
    String name = name(requested);
    if (!folder) {
      if (!format.editable()) throw new IOException("Only TXT and Markdown files can be created");
      String suffix = format == DocumentFormat.MARKDOWN ? ".md" : ".txt";
      if (!name.toLowerCase(Locale.ROOT).endsWith(suffix)) name += suffix;
    }
    require(parent, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE, "creating items");
    requireFreeName(parent, name);
    String uri;
    if (local(parent)) {
      File file = new File(checked(parent, true), name);
      if (!(folder ? file.mkdir() : file.createNewFile()))
        throw new IOException("Could not create this item");
      uri = Uri.fromFile(new File(Uri.parse(parent).getPath(), name)).toString();
    } else {
      Uri created =
          DocumentsContract.createDocument(
              context.getContentResolver(),
              document(parent),
              folder ? DocumentsContract.Document.MIME_TYPE_DIR : format.mime,
              name);
      if (created == null) throw new IOException("The provider did not create this item");
      uri = created.toString();
    }
    return new Library.Entry(uri, name, folder);
  }

  void delete(String parent, Library.Entry source) throws Exception {
    validateSource(parent, source);
    require(source.uri, DocumentsContract.Document.FLAG_SUPPORTS_DELETE, "deleting items");
    if (!local(source.uri)) {
      if (!DocumentsContract.deleteDocument(context.getContentResolver(), document(source.uri)))
        throw new IOException("The provider did not delete this item");
      return;
    }
    // Preflight the complete tree before deleting anything, and never follow symbolic links.
    File target = checked(source.uri, false);
    ArrayList<File> tree = new ArrayList<>();
    ArrayDeque<File> pending = new ArrayDeque<>();
    pending.add(target);
    while (!pending.isEmpty()) {
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("Delete cancelled before starting");
      File next = pending.removeLast();
      tree.add(next);
      if (tree.size() > 10000)
        throw new IOException(
            "Delete collections with more than 10,000 items using Android’s file manager");
      if (next.isDirectory() && !Files.isSymbolicLink(next.toPath())) {
        File[] children = next.listFiles();
        if (children == null || !next.canWrite())
          throw new IOException("A collection is unavailable or read-only");
        Collections.addAll(pending, children);
      }
    }
    Collections.reverse(tree);
    for (File file : tree)
      if (!file.delete())
        throw new IOException(
            "Could not delete "
                + file.getName()
                + ". Some earlier items may already have been deleted.");
  }

  Library.Entry move(String parent, Library.Entry source, String destination) throws Exception {
    validateSource(parent, source);
    requireFreeName(destination, source.name);
    require(source.uri, DocumentsContract.Document.FLAG_SUPPORTS_DELETE, "moving items");
    require(destination, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE, "moving items");
    if (local(parent) && local(destination)) {
      File original = checked(source.uri, false),
          folder = checked(destination, true),
          moved = new File(folder, source.name);
      if (source.folder && folder.toPath().startsWith(original.toPath()))
        throw new IOException("A collection cannot be moved inside itself");
      if (original.renameTo(moved))
        return new Library.Entry(
            Uri.fromFile(new File(Uri.parse(destination).getPath(), source.name)).toString(),
            source.name,
            source.folder);
    } else if (!local(parent)
        && !local(destination)
        && supports(source.uri, DocumentsContract.Document.FLAG_SUPPORTS_MOVE)) {
      try {
        Uri moved =
            DocumentsContract.moveDocument(
                context.getContentResolver(),
                document(source.uri),
                document(parent),
                document(destination));
        if (moved != null) return new Library.Entry(moved.toString(), source.name, source.folder);
      } catch (UnsupportedOperationException | java.io.FileNotFoundException ignored) {
        // Providers may support file creation/deletion without supporting native moves.
      }
    }
    if (source.folder)
      throw new IOException(
          "This provider cannot move collections; individual books can still be moved");
    Library.Entry copied = createExact(destination, source.name);
    boolean verified = false;
    try {
      byte[] expected = copyBytes(source.uri, copied.uri, MAX_COPY_BYTES);
      if (!Arrays.equals(expected, hash(copied.uri)))
        throw new IOException("Copy verification failed; original retained");
      if (!Arrays.equals(expected, hash(source.uri)))
        throw new IOException("The original changed during the move; original retained");
      delete(parent, source);
      verified = true;
      return copied;
    } finally {
      if (!verified) {
        try {
          delete(destination, copied);
        } catch (Exception ignored) {
        }
      }
    }
  }

  private Library.Entry createExact(String parent, String name) throws Exception {
    requireFreeName(parent, name);
    if (local(parent)) {
      File file = new File(checked(parent, true), name);
      if (!file.createNewFile()) throw new IOException("Destination already exists");
      return new Library.Entry(
          Uri.fromFile(new File(Uri.parse(parent).getPath(), name)).toString(), name, false);
    }
    Uri uri =
        DocumentsContract.createDocument(
            context.getContentResolver(), document(parent), DocumentFormat.of(name).mime, name);
    if (uri == null) throw new IOException("Cannot create the destination file");
    return new Library.Entry(uri.toString(), name, false);
  }

  interface PendingCopy {
    void created(Library.Entry entry) throws Exception;
  }

  Library.Entry importFile(
      String parent, String source, String displayName, long declaredSize, PendingCopy pending)
      throws Exception {
    String requested = name(displayName);
    DocumentFormat format = DocumentFormat.of(requested);
    if (format == DocumentFormat.UNKNOWN)
      throw new IOException("Unsupported file type: " + requested);
    long limit =
        format.editable()
            ? TextDocument.MAX_BYTES
            : format.comic() ? MAX_COPY_BYTES : EpubCache.MAX_BYTES;
    if (declaredSize > limit)
      throw new IOException(requested + " exceeds " + limit / 1024 / 1024 + " MB");
    require(parent, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE, "importing files");
    String available = availableName(parent, requested);
    Library.Entry copied = createExact(parent, available);
    boolean verified = false;
    try {
      pending.created(copied);
      byte[] expected = copyBytes(source, copied.uri, limit);
      if (!Arrays.equals(expected, hash(copied.uri, limit)))
        throw new IOException("Import verification failed");
      verified = true;
      return copied;
    } finally {
      if (!verified) {
        try {
          delete(parent, copied);
        } catch (Exception ignored) {
        }
      }
    }
  }

  private String availableName(String parent, String requested) throws Exception {
    HashSet<String> names = new HashSet<>();
    if (local(parent)) {
      String[] children = checked(parent, true).list();
      if (children == null) throw new IOException("Import destination is unavailable");
      for (String child : children) names.add(child.toLowerCase(Locale.ROOT));
    } else {
      Uri folder = document(parent);
      Uri children =
          DocumentsContract.buildChildDocumentsUriUsingTree(
              folder, DocumentsContract.getDocumentId(folder));
      try (Cursor cursor =
          context
              .getContentResolver()
              .query(
                  children,
                  new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                  null,
                  null,
                  null)) {
        if (cursor == null) throw new IOException("Import destination is unavailable");
        while (cursor.moveToNext()) {
          String child = cursor.getString(0);
          if (child != null) names.add(child.toLowerCase(Locale.ROOT));
        }
      }
    }
    int dot = requested.lastIndexOf('.');
    String base = requested.substring(0, dot), extension = requested.substring(dot);
    for (int suffix = 0; suffix < 10000; suffix++) {
      String candidate = suffix == 0 ? requested : base + " (" + suffix + ")" + extension;
      if (!names.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
    }
    throw new IOException("Too many copies with the same filename");
  }

  private byte[] copyBytes(String source, String destination, long limit) throws Exception {
    try (InputStream input = TextDocument.input(context, source);
        OutputStream output = output(destination)) {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[65536];
      long total = 0;
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted())
          throw new InterruptedIOException("Copy cancelled; original retained");
        if ((total += count) > limit)
          throw new IOException("File exceeds " + limit / 1024 / 1024 + " MB");
        hash.update(buffer, 0, count);
        output.write(buffer, 0, count);
      }
      output.flush();
      return hash.digest();
    }
  }

  private OutputStream output(String uri) throws IOException {
    if (local(uri)) return new FileOutputStream(checked(uri, false));
    OutputStream output = context.getContentResolver().openOutputStream(document(uri), "rwt");
    if (output == null) throw new IOException("Destination is not writable");
    return output;
  }

  private byte[] hash(String uri) throws Exception {
    return hash(uri, MAX_COPY_BYTES);
  }

  private byte[] hash(String uri, long limit) throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    long total = 0;
    try (InputStream input = TextDocument.input(context, uri)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted())
          throw new InterruptedIOException("Verification cancelled");
        if ((total += count) > limit) throw new IOException("Destination exceeds the move limit");
        hash.update(buffer, 0, count);
      }
    }
    return hash.digest();
  }
}
