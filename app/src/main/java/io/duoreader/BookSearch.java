package io.duoreader;

import java.io.*;
import java.util.ArrayList;
import java.util.regex.*;

/** Search owns its document/cache, so it cannot lock or close the document used by page turns. */
public final class BookSearch implements Closeable {
  private Epub document;
  private String identity = "";

  public synchronized ArrayList<ReaderApp.Match> find(Epub source, String query) throws Exception {
    ArrayList<ReaderApp.Match> results = new ArrayList<>();
    if (source == null || query.isEmpty()) return results;
    String key =
        source.file.getAbsolutePath()
            + ":"
            + source.file.lastModified()
            + ":"
            + source.file.length();
    if (!identity.equals(key)) {
      if (document != null) document.close();
      document = new Epub(source.file);
      identity = key;
    }
    Pattern pattern =
        Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    for (int chapter = 0; chapter < document.chapters.size(); chapter++) {
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("Search cancelled");
      String text = document.plainText(chapter);
      Matcher matches = pattern.matcher(text);
      while (matches.find()) {
        if (Thread.currentThread().isInterrupted())
          throw new InterruptedIOException("Search cancelled");
        int offset = matches.start();
        results.add(
            new ReaderApp.Match(
                document.chapters.get(chapter).title
                    + "\n…"
                    + ReaderApp.snippet(text, Math.max(0, offset - 28), 110)
                    + "…",
                new ReaderApp.Location(chapter, offset)));
        if (results.size() >= 300) return results;
      }
    }
    return results;
  }

  @Override
  public synchronized void close() throws IOException {
    if (document != null) document.close();
    document = null;
    identity = "";
  }
}
