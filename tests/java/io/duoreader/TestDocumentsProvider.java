package io.duoreader;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

/** Test-only document contract endpoint, with read-only and failed-write scenarios. */
public final class TestDocumentsProvider extends ContentProvider {
  static final String AUTHORITY = "io.duoreader.tests.documents";
  private File root;
  private boolean readOnly, corrupt;

  public boolean onCreate() {
    root = new File(getContext().getCacheDir(), "documents-provider-fixtures");
    root.mkdirs();
    return true;
  }

  private File file(Uri uri) throws IOException {
    String id;
    try {
      id = DocumentsContract.getDocumentId(uri);
    } catch (IllegalArgumentException e) {
      id = DocumentsContract.getTreeDocumentId(uri);
    }
    File target = id.equals("root") ? root : new File(root, id.substring("root/".length()));
    if (!id.equals("root") && !id.startsWith("root/")) throw new IOException("Invalid fixture ID");
    if (!target.getCanonicalFile().toPath().startsWith(root.getCanonicalFile().toPath()))
      throw new IOException("Outside fixtures");
    return target;
  }

  private String id(File file) {
    String relative = root.toPath().relativize(file.toPath()).toString();
    return relative.isEmpty() ? "root" : "root/" + relative;
  }

  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    String[] columns =
        projection == null
            ? new String[] {
              "document_id", "_display_name", "mime_type", "flags", "_size", "last_modified"
            }
            : projection;
    MatrixCursor result = new MatrixCursor(columns);
    try {
      File target = file(uri);
      File[] files =
          uri.getLastPathSegment().equals("children") ? target.listFiles() : new File[] {target};
      if (files != null)
        for (File child : files) {
          if (!child.exists()) continue;
          MatrixCursor.RowBuilder row = result.newRow();
          for (String column : columns) {
            Object value = null;
            if (column.equals("document_id")) value = id(child);
            else if (column.equals("_display_name")) value = child.getName();
            else if (column.equals("mime_type"))
              value =
                  child.isDirectory()
                      ? DocumentsContract.Document.MIME_TYPE_DIR
                      : child.getName().endsWith(".md") ? "text/markdown" : "text/plain";
            else if (column.equals("flags"))
              value =
                  readOnly
                      ? 0
                      : child.isDirectory()
                          ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                              | DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                          : DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                              | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
            else if (column.equals("_size")) value = child.length();
            else if (column.equals("last_modified")) value = child.lastModified();
            row.add(column, value);
          }
        }
      return result;
    } catch (IOException error) {
      throw new IllegalArgumentException(error);
    }
  }

  public Bundle call(String method, String arg, Bundle extras) {
    if (method.equals("configure")) {
      readOnly = extras.getBoolean("readOnly");
      corrupt = extras.getBoolean("corrupt");
      return new Bundle();
    }
    if (readOnly) throw new SecurityException("Read-only fixture provider");
    try {
      Uri uri = extras.getParcelable("uri");
      File parent = file(uri);
      Bundle result = new Bundle();
      if (method.equals("android:createDocument")) {
        String name = extras.getString("_display_name");
        if (name.contains("/") || name.contains(".."))
          throw new IOException("Invalid fixture name");
        File created = new File(parent, name);
        if (!(DocumentsContract.Document.MIME_TYPE_DIR.equals(extras.getString("mime_type"))
            ? created.mkdir()
            : created.createNewFile())) throw new IOException("Already exists");
        result.putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, id(created)));
        return result;
      }
      if (method.equals("android:deleteDocument")) {
        remove(parent);
        return result;
      }
      throw new UnsupportedOperationException(method);
    } catch (IOException error) {
      throw new IllegalArgumentException(error);
    }
  }

  private void remove(File file) throws IOException {
    if (file.equals(root)) throw new IOException("Cannot remove fixture root");
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) remove(child);
    }
    if (!file.delete()) throw new IOException("Fixture delete failed");
  }

  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    try {
      File target = file(uri);
      if (mode.contains("w") && readOnly) throw new FileNotFoundException("Read-only fixture");
      if (mode.contains("w") && corrupt)
        target = new File(getContext().getCacheDir(), "discarded-provider-write");
      return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode));
    } catch (IOException error) {
      throw new FileNotFoundException(error.getMessage());
    }
  }

  public String getType(Uri uri) {
    return "application/octet-stream";
  }

  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri uri, ContentValues values, String where, String[] args) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri uri, String where, String[] args) {
    throw new UnsupportedOperationException();
  }
}
