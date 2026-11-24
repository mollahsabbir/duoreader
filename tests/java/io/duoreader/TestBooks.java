package io.duoreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/** Original, generated fixtures. No published books or external artwork are needed by tests. */
final class TestBooks {
  static final String PHRASE = "Meet me outside";

  static File create(Context context) throws IOException {
    File directory = new File(context.getCacheDir(), "reader-fixtures-v1");
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Cannot create test library");
    File book = new File(directory, "Fixture.epub");
    if (book.isFile()) {
      try (ZipFile zip = new ZipFile(book)) {
        if (zip.getEntry("book.opf") != null && zip.getEntry("chapter119.xhtml") != null)
          return book;
      } catch (IOException ignored) {
        // Replace an interrupted fixture write, not a user book.
      }
    }
    write(book);
    return book;
  }

  static File prepare(Context context) throws IOException {
    File book = create(context);
    for (String name : new String[] {"Second.epub", "Third.epub", "Fourth.epub"}) {
      File copy = new File(book.getParentFile(), name);
      if (copy.isFile() && copy.length() == book.length()) continue;
      try (InputStream input = new FileInputStream(book);
          OutputStream output = new FileOutputStream(copy)) {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
      }
    }
    return book.getParentFile();
  }

  static void part(ZipOutputStream zip, String name, String text) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(text.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static void write(File book) throws IOException {
    File temporary = new File(book.getPath() + ".part");
    try {
      try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(temporary))) {
        byte[] mime = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(mime);
        ZipEntry mimetype = new ZipEntry("mimetype");
        mimetype.setMethod(ZipEntry.STORED);
        mimetype.setSize(mime.length);
        mimetype.setCrc(crc.getValue());
        zip.putNextEntry(mimetype);
        zip.write(mime);
        zip.closeEntry();
        part(
            zip,
            "META-INF/container.xml",
            "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles>"
                + "<rootfile full-path='book.opf' media-type='application/oebps-package+xml'/>"
                + "</rootfiles></container>");
        StringBuilder manifest = new StringBuilder(),
            spine = new StringBuilder(),
            toc = new StringBuilder();
        for (int i = 0; i < 120; i++) {
          String name = "chapter" + i + ".xhtml";
          String label = i == 0 ? "Opening" : "Chapter " + (i + 1);
          manifest
              .append("<item id='c")
              .append(i)
              .append("' href='")
              .append(name)
              .append("' media-type='application/xhtml+xml'/>");
          spine.append("<itemref idref='c").append(i).append("'/>");
          toc.append("<li><a href='").append(name).append("'>").append(label).append("</a></li>");
          StringBuilder body =
              new StringBuilder("<html xmlns='http://www.w3.org/1999/xhtml'><head><title>")
                  .append(label)
                  .append("</title></head><body><h1>")
                  .append(label)
                  .append("</h1>");
          if (i == 0)
            body.append("<p>")
                .append(PHRASE)
                .append(". We packed a map and walked to the river.</p>");
          for (int paragraph = 0; paragraph < (i == 0 ? 240 : 2); paragraph++)
            body.append(
                "<p>The river follows a winding path through the valley. A footbridge joins the two"
                    + " banks. We checked the map, watched the water, and continued toward the next"
                    + " village. A curious bird rested beside the trail. Each turn revealed another"
                    + " part of the landscape.</p>");
          part(zip, name, body.append("</body></html>").toString());
        }
        part(
            zip,
            "book.opf",
            "<package xmlns='http://www.idpf.org/2007/opf' version='3.0'"
                + " unique-identifier='id'><metadata"
                + " xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier"
                + " id='id'>urn:duoreader:test-fixture</dc:identifier><dc:title>Reader"
                + " Fixture</dc:title><dc:creator>Duo Reader</dc:creator>"
                + "<dc:language>en</dc:language></metadata><manifest><item id='nav'"
                + " href='nav.xhtml' properties='nav' media-type='application/xhtml+xml'/><item"
                + " id='cover' href='cover.png' properties='cover-image' media-type='image/png'/>"
                + manifest
                + "</manifest><spine>"
                + spine
                + "</spine></package>");
        part(
            zip,
            "nav.xhtml",
            "<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'>"
                + "<head><title>Contents</title></head><body><nav epub:type='toc'><ol>"
                + toc
                + "</ol></nav></body></html>");
        Bitmap cover = Bitmap.createBitmap(480, 720, Bitmap.Config.RGB_565);
        try {
          Canvas canvas = new Canvas(cover);
          canvas.drawColor(Color.rgb(34, 92, 97));
          Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
          paint.setColor(Color.WHITE);
          paint.setTextSize(46);
          canvas.drawText("Reader Fixture", 55, 350, paint);
          zip.putNextEntry(new ZipEntry("cover.png"));
          if (!cover.compress(Bitmap.CompressFormat.PNG, 100, zip))
            throw new IOException("Cannot generate test cover");
          zip.closeEntry();
        } finally {
          cover.recycle();
        }
      }
      if (!temporary.renameTo(book)) throw new IOException("Cannot publish test fixture");
    } finally {
      temporary.delete();
    }
  }
}
