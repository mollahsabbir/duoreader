package io.duoreader;

import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

public final class Library {
  /** Empty app-owned folder; books are supplied by the user through Android's folder picker. */
  public static File appDirectory(Context context) {
    File external = context.getExternalFilesDir(null);
    File directory = new File(external == null ? context.getFilesDir() : external, "Books");
    if (directory.isDirectory() || directory.mkdirs()) return directory;
    directory = new File(context.getFilesDir(), "Books");
    directory.mkdirs();
    return directory;
  }

  public static final class Preview {
    public String label = "", text = "", error = "";
    public ArrayList<Entry> children = new ArrayList<>();
    public int books, collections;
  }

  public static final class Entry {
    public String uri, name, title, author = "", description = "", error = "";
    public boolean folder, loaded, hasCover;
    public long modified, size;
    public final long stableId;
    public Bitmap cover, heroCover;

    public Entry(String u, String n, boolean f) {
      uri = u;
      name = n;
      title = n.replaceFirst("(?i)\\.(epub|txt|md|markdown|cbz|cbr)$", "");
      folder = f;
      long id = 0xcbf29ce484222325L;
      for (int i = 0; i < u.length(); i++) {
        id ^= u.charAt(i);
        id *= 0x100000001b3L;
      }
      stableId = id == Long.MIN_VALUE ? 0 : id;
    }
  }

  public static ArrayList<Entry> list(Context context, String location) throws Exception {
    return list(context, location, null);
  }

  public static ArrayList<Entry> list(
      Context context, String location, android.os.CancellationSignal cancellation)
      throws Exception {
    ArrayList<Entry> result = new ArrayList<>();
    Uri uri = Uri.parse(location);
    if (uri.getScheme().equals("file")) {
      File[] files = new File(uri.getPath()).listFiles();
      if (files == null) throw new IOException("This folder is unavailable.");
      for (File f : files)
        if (!f.isHidden() && (f.isDirectory() || DocumentFormat.supported(f.getName()))) {
          if (cancellation != null) cancellation.throwIfCanceled();
          Entry e = new Entry(Uri.fromFile(f).toString(), f.getName(), f.isDirectory());
          e.modified = f.lastModified();
          e.size = f.length();
          result.add(e);
        }
    } else {
      String id =
          DocumentsContract.isDocumentUri(context, uri)
              ? DocumentsContract.getDocumentId(uri)
              : DocumentsContract.getTreeDocumentId(uri);
      Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id);
      String[] cols = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE
      };
      try (Cursor c =
          context.getContentResolver().query(children, cols, null, null, null, cancellation)) {
        if (c == null) throw new IOException("The folder provider did not respond.");
        while (c.moveToNext()) {
          if (cancellation != null) cancellation.throwIfCanceled();
          String name = c.getString(1);
          if (name == null || name.isEmpty() || name.startsWith(".") || c.getString(0) == null)
            continue;
          boolean folder = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
          if (folder || DocumentFormat.supported(name)) {
            Entry e =
                new Entry(
                    DocumentsContract.buildDocumentUriUsingTree(uri, c.getString(0)).toString(),
                    name,
                    folder);
            e.modified = c.getLong(3);
            e.size = c.getLong(4);
            result.add(e);
          }
        }
      }
    }
    Collections.sort(
        result,
        (a, b) -> {
          if (a.folder != b.folder) return a.folder ? -1 : 1;
          int order = a.name.compareToIgnoreCase(b.name);
          if (order == 0) order = a.name.compareTo(b.name);
          return order == 0 ? a.uri.compareTo(b.uri) : order;
        });
    return result;
  }

  public static File cached(Context context, String source, long modified, long size)
      throws IOException {
    return EpubCache.resolve(context, source, modified, size);
  }

  public static void metadata(Context c, Entry e) {
    if (e.folder || e.loaded) return;
    if (DocumentFormat.of(e.name).editable()) {
      e.loaded = true;
      return;
    }
    try (Epub book = new Epub(cached(c, e.uri, e.modified, e.size))) {
      e.title = book.title.equals("Untitled") ? e.title : book.title;
      e.author = book.author;
      e.description = book.description;
      e.cover = book.cover(128);
      e.hasCover = e.cover != null;
    } catch (Exception ex) {
      e.error = ex.getMessage() == null ? "Cannot read this EPUB" : ex.getMessage();
    }
    e.loaded = true;
  }

  public static String id(String uri) {
    try {
      byte[] hash =
          java.security.MessageDigest.getInstance("SHA-256").digest(uri.getBytes("UTF-8"));
      char[] hex = new char[hash.length * 2];
      String digits = "0123456789abcdef";
      for (int i = 0; i < hash.length; i++) {
        hex[i * 2] = digits.charAt((hash[i] & 255) >>> 4);
        hex[i * 2 + 1] = digits.charAt(hash[i] & 15);
      }
      return new String(hex);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String clean(String text, int limit) {
    String s = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").replaceAll("\\s+", " ").trim();
    return s.length() <= limit ? s : s.substring(0, limit).trim() + "…";
  }

  public static Preview preview(Context context, Entry entry) {
    Preview p = new Preview();
    try {
      if (entry.folder) {
        p.children = list(context, entry.uri);
        for (Entry e : p.children)
          if (e.folder) p.collections++;
          else p.books++;
        p.label = "IN THIS COLLECTION";
      } else if (DocumentFormat.of(entry.name).editable()) {
        p.label =
            DocumentFormat.of(entry.name) == DocumentFormat.MARKDOWN
                ? "MARKDOWN DOCUMENT"
                : "TEXT DOCUMENT";
        p.text = TextDocument.load(context, entry.uri).text;
        if (p.text.length() > 2400) p.text = p.text.substring(0, 2400) + "…";
      } else
        try (Epub epub = new Epub(cached(context, entry.uri, entry.modified, entry.size))) {
          String description = clean(epub.description, 2400);
          if (description.length() >= 40) {
            p.label = "ABOUT THIS BOOK";
            p.text = description;
            return p;
          }
          LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
          for (Epub.Toc t : epub.toc)
            if (t.label.matches("(?is)^\\s*(chapter|part|book|canto)\\s+(1|i)\\b.*")) {
              candidates.add(t.chapter);
              break;
            }
          for (int i = 0; i < Math.min(24, epub.chapters.size()); i++) {
            Epub.Chapter chapter = epub.chapters.get(i);
            String label = (chapter.title + " " + chapter.path).toLowerCase(Locale.ROOT);
            if (!label.matches(
                "(?s).*(copyright|cover|titlepage|title_page|contents|navigation|imprint|colophon|dedication|frontmatter|toc\\.).*"))
              candidates.add(i);
          }
          String fallback = "";
          for (int i : candidates) {
            if (Thread.currentThread().isInterrupted()) return p;
            String text = clean(epub.plainText(i), 2400);
            if (text.length() > fallback.length()) fallback = text;
            if (text.replaceAll("[^\\p{L}]", "").length() >= 120) {
              p.text = text;
              break;
            }
          }
          if (p.text.isEmpty()) p.text = fallback;
          p.label = "OPENING EXCERPT";
          if (p.text.isEmpty()) {
            p.label = "PREVIEW UNAVAILABLE";
            p.text = "This EPUB has no readable text preview. You can still try opening the book.";
          }
        }
    } catch (Exception e) {
      p.error =
          entry.folder
              ? "This collection is unavailable. Check its location or folder permission in"
                  + " Preferences."
              : "A preview could not be read. Open the book for details, or check that the EPUB is"
                  + " still available.";
    }
    return p;
  }
}
