package io.duoreader;

import android.graphics.*;
import com.github.junrar.Archive;
import com.github.junrar.ArchiveOptions;
import com.github.junrar.io.SeekableReadOnlyFile;
import com.github.junrar.rarfile.FileHeader;
import com.github.junrar.volume.Volume;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Indexed image archives. Extract one page to a bounded temporary file, never all scans. */
public final class ComicArchive implements Closeable {
  static final int MAX_ENTRIES = 10000, MAX_INDEX_BYTES = 8 * 1024 * 1024;
  static final long MAX_PAGE_BYTES = 64L * 1024 * 1024;
  public final ArrayList<String> names = new ArrayList<>();
  private final Map<String, ZipEntry> zipEntries = new HashMap<>();
  private final Map<String, FileHeader> rarEntries = new HashMap<>();
  private ZipFile zip;
  private Archive rar;
  private RarChannel channel;
  private final File temporaryDirectory;

  private ComicArchive(File temporaryDirectory) {
    this.temporaryDirectory = temporaryDirectory;
  }

  static ComicArchive open(File file, DocumentFormat format, File temporaryDirectory)
      throws IOException {
    ComicArchive result = new ComicArchive(temporaryDirectory);
    try {
      if (format == DocumentFormat.CBZ) {
        validateZipIndex(file);
        result.zip = new ZipFile(file);
        Enumeration<? extends ZipEntry> entries = result.zip.entries();
        int count = 0;
        while (entries.hasMoreElements()) {
          ZipEntry entry = entries.nextElement();
          if (++count > MAX_ENTRIES)
            throw new IOException("Comic archives support up to 10,000 entries");
          if (!entry.isDirectory() && image(entry.getName())) {
            validSize(entry.getSize());
            result.add(entry.getName());
            result.zipEntries.put(entry.getName(), entry);
          }
        }
      } else {
        result.rar =
            new Archive(
                (archive, last) -> {
                  if (last != null)
                    return null; // Never resolve another volume outside the selected file.
                  return new Volume() {
                    public com.github.junrar.io.SeekableReadOnlyByteChannel getChannel()
                        throws IOException {
                      result.channel = new RarChannel(file);
                      return result.channel;
                    }

                    public long getLength() {
                      return file.length();
                    }

                    public Archive getArchive() {
                      return archive;
                    }
                  };
                },
                ArchiveOptions.builder().maxDictionarySize(32L * 1024 * 1024).build());
        if (result.rar.isPasswordProtected())
          throw new IOException("Password-protected comics are not supported");
        if (result.rar.hasBrokenHeaders())
          throw new IOException("This RAR archive has damaged or unsupported headers");
        List<FileHeader> headers = result.rar.getFileHeaders();
        if (headers.size() > MAX_ENTRIES)
          throw new IOException("Comic archives support up to 10,000 entries");
        for (FileHeader header : headers) {
          if (header.isSplitBefore() || header.isSplitAfter())
            throw new IOException("Multipart comics must be combined before opening");
          if (!header.isDirectory() && image(header.getFileName())) {
            validSize(header.getFullUnpackSize());
            result.add(header.getFileName());
            result.rarEntries.put(header.getFileName(), header);
          }
        }
      }
      if (result.names.isEmpty())
        throw new IOException("No supported images in this comic (PNG, JPEG, WebP, GIF, BMP)");
      result.names.sort(ComicArchive::naturalCompare);
      return result;
    } catch (Exception error) {
      try {
        result.close();
      } catch (IOException ignored) {
      }
      if (error instanceof IOException) throw (IOException) error;
      throw new IOException("Cannot read this comic: " + error.getMessage(), error);
    }
  }

  private void add(String name) throws IOException {
    if (name.length() > 1024 || names.size() >= MAX_ENTRIES)
      throw new IOException("Comic index exceeds supported limits");
    if (zipEntries.containsKey(name) || rarEntries.containsKey(name))
      throw new IOException("Comic contains duplicate page names");
    names.add(name);
  }

  private static void validSize(long size) throws IOException {
    if (size < 0 || size > MAX_PAGE_BYTES)
      throw new IOException("Comic images must be 64 MB or smaller when extracted");
  }

  private static boolean image(String name) {
    String normalized = name.replace('\\', '/');
    for (String part : normalized.split("/"))
      if (part.equals("..") || part.startsWith(".") || part.equals("__MACOSX")) return false;
    return !normalized.startsWith("/")
        && normalized.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp|gif|bmp)$");
  }

  public synchronized Bitmap decode(int page, int width, int height) throws IOException {
    if (page < 0 || page >= names.size()) throw new IOException("Page is outside this comic");
    File image = File.createTempFile("comic-page-", ".image", temporaryDirectory);
    try {
      String name = names.get(page);
      try (OutputStream output = new LimitedOutput(new FileOutputStream(image))) {
        if (zip != null) {
          try (InputStream input = zip.getInputStream(zipEntries.get(name))) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
          }
        } else {
          channel.extractionBudget();
          rar.extractFile(rarEntries.get(name), output);
        }
      }
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(image.getPath(), bounds);
      if (bounds.outWidth <= 0
          || bounds.outHeight <= 0
          || bounds.outWidth > 65535
          || bounds.outHeight > 65535
          || (long) bounds.outWidth * bounds.outHeight > 500_000_000L)
        throw new IOException("This image is damaged or exceeds supported dimensions");
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inPreferredConfig = Bitmap.Config.ARGB_8888;
      int sample = 1;
      long budget = 2_000_000;
      while ((long) ((bounds.outWidth + sample - 1) / sample)
                  * ((bounds.outHeight + sample - 1) / sample)
              > budget
          || bounds.outWidth / sample > Math.max(64, width) * 2L
          || bounds.outHeight / sample > Math.max(64, height) * 2L) sample *= 2;
      options.inSampleSize = sample;
      Bitmap bitmap = BitmapFactory.decodeFile(image.getPath(), options);
      if (bitmap == null) throw new IOException("This comic page cannot be decoded");
      return bitmap;
    } catch (OutOfMemoryError error) {
      throw new IOException(
          "Not enough memory for this scan. Close another app and try again.", error);
    } catch (Exception error) {
      if (error instanceof IOException) throw (IOException) error;
      throw new IOException("Cannot decode this page: " + error.getMessage(), error);
    } finally {
      image.delete();
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (zip != null) {
      zip.close();
      zip = null;
    }
    if (rar != null) {
      rar.close();
      rar = null;
    } else if (channel != null) channel.close();
  }

  private static final class LimitedOutput extends FilterOutputStream {
    long count;

    LimitedOutput(OutputStream output) {
      super(output);
    }

    @Override
    public void write(byte[] bytes, int offset, int size) throws IOException {
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("Comic page cancelled");
      if ((count += size) > MAX_PAGE_BYTES) throw new IOException("Extracted image exceeds 64 MB");
      out.write(bytes, offset, size);
    }

    @Override
    public void write(int value) throws IOException {
      write(new byte[] {(byte) value}, 0, 1);
    }
  }

  private static final class RarChannel extends SeekableReadOnlyFile {
    long remaining = MAX_INDEX_BYTES, deadline = android.os.SystemClock.uptimeMillis() + 10000;
    int seeks;

    RarChannel(File file) throws IOException {
      super(file);
    }

    void extractionBudget() {
      remaining = 256L * 1024 * 1024;
      seeks = 0;
      deadline = android.os.SystemClock.uptimeMillis() + 15000;
    }

    private void reserve(int bytes) throws IOException {
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("RAR operation cancelled");
      if ((remaining -= bytes) < 0 || android.os.SystemClock.uptimeMillis() > deadline)
        throw new IOException("RAR operation exceeds its memory, input, or time budget");
    }

    @Override
    public void setPosition(long position) throws IOException {
      if (++seeks > MAX_ENTRIES * 4) throw new IOException("Too many RAR headers");
      super.setPosition(position);
    }

    @Override
    public int read() throws IOException {
      reserve(1);
      return super.read();
    }

    @Override
    public int read(byte[] bytes, int offset, int count) throws IOException {
      reserve(count);
      return super.read(bytes, offset, count);
    }

    @Override
    public int readFully(byte[] bytes, int count) throws IOException {
      reserve(count);
      return super.readFully(bytes, count);
    }
  }

  private static void validateZipIndex(File file) throws IOException {
    try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
      long length = input.length();
      int tailSize = (int) Math.min(length, 65557);
      byte[] tail = new byte[tailSize];
      input.seek(length - tailSize);
      input.readFully(tail);
      for (int offset = tailSize - 22; offset >= 0; offset--) {
        if (u32(tail, offset) != 0x06054b50L || offset + 22 + u16(tail, offset + 20) != tailSize)
          continue;
        long count = u16(tail, offset + 10),
            bytes = u32(tail, offset + 12),
            start = u32(tail, offset + 16);
        if (u16(tail, offset + 4) != 0 || u16(tail, offset + 6) != 0)
          throw new IOException("Multipart ZIP comics are not supported");
        if (count > MAX_ENTRIES || bytes > MAX_INDEX_BYTES || start + bytes > length)
          throw new IOException("Comic ZIP index exceeds supported limits");
        input.seek(start);
        byte[] header = new byte[46];
        for (int i = 0; i < count; i++) {
          input.readFully(header);
          if (u32(header, 0) != 0x02014b50L) throw new IOException("Damaged comic ZIP index");
          if ((u16(header, 8) & 1) != 0)
            throw new IOException("Password-protected comics are not supported");
          long next = input.getFilePointer() + u16(header, 28) + u16(header, 30) + u16(header, 32);
          if (next > start + bytes) throw new IOException("Damaged comic ZIP index");
          input.seek(next);
        }
        return;
      }
      throw new IOException("This is not a complete CBZ/ZIP archive");
    }
  }

  private static int u16(byte[] bytes, int offset) {
    return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8;
  }

  private static long u32(byte[] bytes, int offset) {
    return (u16(bytes, offset) | (long) u16(bytes, offset + 2) << 16) & 0xffffffffL;
  }

  static int naturalCompare(String a, String b) {
    int i = 0, j = 0;
    while (i < a.length() && j < b.length()) {
      char x = a.charAt(i), y = b.charAt(j);
      if (x >= '0' && x <= '9' && y >= '0' && y <= '9') {
        int endA = i, endB = j;
        while (endA < a.length() && a.charAt(endA) >= '0' && a.charAt(endA) <= '9') endA++;
        while (endB < b.length() && b.charAt(endB) >= '0' && b.charAt(endB) <= '9') endB++;
        int significantA = i, significantB = j;
        while (significantA < endA - 1 && a.charAt(significantA) == '0') significantA++;
        while (significantB < endB - 1 && b.charAt(significantB) == '0') significantB++;
        int order = Integer.compare(endA - significantA, endB - significantB);
        if (order != 0) return order;
        for (int k = 0; k < endA - significantA; k++) {
          order = Character.compare(a.charAt(significantA + k), b.charAt(significantB + k));
          if (order != 0) return order;
        }
        i = endA;
        j = endB;
      } else {
        int order = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
        if (order != 0) return order;
        i++;
        j++;
      }
    }
    int length = Integer.compare(a.length() - i, b.length() - j);
    return length == 0 ? a.compareTo(b) : length;
  }
}
