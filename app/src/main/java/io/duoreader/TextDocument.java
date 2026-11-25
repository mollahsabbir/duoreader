package io.duoreader;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.Arrays;

/** Bounded Unicode text I/O; preserve encoding and line endings when saving. */
final class TextDocument {
  static final int MAX_BYTES = 2 * 1024 * 1024, MAX_CHARS = 512 * 1024;
  final String uri;
  final Charset charset;
  final byte[] bom;
  final String newline;
  String text;
  byte[] baseline;

  private TextDocument(
      String uri, String text, Charset charset, byte[] bom, String newline, byte[] baseline) {
    this.uri = uri;
    this.text = text;
    this.charset = charset;
    this.bom = bom;
    this.newline = newline;
    this.baseline = baseline;
  }

  static InputStream input(Context context, String uri) throws IOException {
    Uri source = Uri.parse(uri);
    InputStream stream =
        "file".equals(source.getScheme())
            ? new FileInputStream(source.getPath())
            : context.getContentResolver().openInputStream(source);
    if (stream == null) throw new FileNotFoundException("Document is unavailable");
    return stream;
  }

  static byte[] read(Context context, String uri) throws IOException {
    try (InputStream input = input(context, uri);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted())
          throw new InterruptedIOException("Open cancelled");
        if (output.size() + count > MAX_BYTES)
          throw new IOException("Text files must be 2 MB or smaller");
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }

  static TextDocument load(Context context, String uri) throws IOException {
    byte[] bytes = read(context, uri);
    int skip = 0;
    Charset encoding = StandardCharsets.UTF_8;
    if (bytes.length >= 3
        && bytes[0] == (byte) 0xef
        && bytes[1] == (byte) 0xbb
        && bytes[2] == (byte) 0xbf) skip = 3;
    else if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
      skip = 2;
      encoding = StandardCharsets.UTF_16LE;
    } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
      skip = 2;
      encoding = StandardCharsets.UTF_16BE;
    }
    String decoded;
    try {
      decoded =
          encoding
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip))
              .toString();
    } catch (CharacterCodingException error) {
      throw new IOException("Use UTF-8 or BOM-marked UTF-16 text", error);
    }
    if (decoded.indexOf('\0') >= 0) throw new IOException("This file contains binary data");
    if (decoded.length() > MAX_CHARS)
      throw new IOException("Text files must contain at most 524,288 characters");
    String newline = decoded.contains("\r\n") ? "\r\n" : decoded.contains("\r") ? "\r" : "\n";
    return new TextDocument(
        uri,
        decoded.replace("\r\n", "\n").replace('\r', '\n'),
        encoding,
        Arrays.copyOf(bytes, skip),
        newline,
        bytes);
  }

  private byte[] encode(String content) throws IOException {
    byte[] encoded = content.replace("\n", newline).getBytes(charset);
    if (encoded.length + bom.length > MAX_BYTES)
      throw new IOException("Saved text would exceed 2 MB");
    byte[] bytes = Arrays.copyOf(bom, bom.length + encoded.length);
    System.arraycopy(encoded, 0, bytes, bom.length, encoded.length);
    return bytes;
  }

  synchronized void save(Context context, String content) throws IOException {
    if (!Arrays.equals(read(context, uri), baseline))
      throw new IOException(
          "The file changed outside Duo Reader. Your draft is safe; reopen before overwriting it.");
    byte[] bytes = encode(content);
    Uri destination = Uri.parse(uri);
    if ("file".equals(destination.getScheme())) writeAtomic(new File(destination.getPath()), bytes);
    else {
      // Document providers cannot promise atomic replacement. Keep a private baseline backup
      // until a read-back verifies the write, including providers that ignore truncation.
      File backup = new File(context.getFilesDir(), "document-backup-" + Library.id(uri));
      writeAtomic(backup, baseline);
      try (OutputStream output =
          context.getContentResolver().openOutputStream(destination, "rwt")) {
        if (output == null) throw new IOException("This provider cannot write the document");
        output.write(bytes);
        output.flush();
      }
      if (!Arrays.equals(read(context, uri), bytes))
        throw new IOException(
            "The provider did not save the full file. Your draft and original backup are safe.");
      backup.delete();
    }
    baseline = bytes;
    text = content;
  }

  static void writeAtomic(File destination, byte[] bytes) throws IOException {
    AtomicFile file = new AtomicFile(destination);
    FileOutputStream output = null;
    try {
      output = file.startWrite();
      output.write(bytes);
      output.getFD().sync();
      file.finishWrite(output);
    } catch (IOException error) {
      if (output != null) file.failWrite(output);
      throw error;
    }
  }
}
