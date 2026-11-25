package io.duoreader;

import android.content.Context;
import android.net.Uri;
import java.io.*;

/**
 * Bounded, atomic copies. A fixed lock stripe prevents concurrent readers publishing partial files.
 */
public final class EpubCache {
  public static final long MAX_BYTES = 200L * 1024 * 1024;
  private static final Object[] LOCKS = new Object[32];

  static {
    for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
  }

  public static File resolve(Context context, String source, long modified, long size)
      throws IOException {
    return resolve(context, source, modified, size, MAX_BYTES, "epub");
  }

  static File resolve(Context context, String source, long modified, long size, long limit, String format)
      throws IOException {
    Uri uri = Uri.parse(source);
    if ("file".equals(uri.getScheme())) {
      File file = new File(uri.getPath());
      if (!file.isFile()) throw new FileNotFoundException("This document is no longer available");
      if (file.length() > limit) throw new IOException("Document exceeds " + limit / 1024 / 1024 + " MB");
      return file;
    }
    String identity = Library.id(source + "\n" + modified + "\n" + size);
    File destination = new File(context.getCacheDir(), "book-" + identity + "." + format);
    synchronized (LOCKS[(identity.hashCode() & Integer.MAX_VALUE) % LOCKS.length]) {
      if (destination.isFile() && destination.length() > 0) return destination;
      if (size > limit) throw new IOException("Document exceeds " + limit / 1024 / 1024 + " MB");
      if (size > 0 && context.getCacheDir().getUsableSpace() < size + 32L * 1024 * 1024)
        throw new IOException("Not enough storage to cache this document");
      File temporary = File.createTempFile("epub-", ".part", context.getCacheDir());
      try {
        try (InputStream input = context.getContentResolver().openInputStream(uri);
            FileOutputStream output = new FileOutputStream(temporary)) {
          if (input == null) throw new FileNotFoundException("This document is no longer available");
          copy(input, output, limit);
          output.getFD().sync();
        }
        if (!temporary.renameTo(destination)) throw new IOException("Cannot cache this document");
        return destination;
      } finally {
        if (temporary.exists()) temporary.delete();
      }
    }
  }

  static void copy(InputStream input, OutputStream output, long limit) throws IOException {
    byte[] buffer = new byte[65536];
    long total = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("Copy cancelled");
      total += count;
      if (total > limit) throw new IOException("Document exceeds " + limit / 1024 / 1024 + " MB");
      output.write(buffer, 0, count);
    }
  }
}
