package io.duoreader;

import android.content.Context;
import android.graphics.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Original scan fixtures; stored RAR records exercise both public container formats. */
final class TestComics {
  static File directory(Context context) throws IOException {
    File directory = new File(context.getCacheDir(), "comic-fixtures");
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Fixture directory");
    return directory;
  }

  static byte[] image(int color) {
    Bitmap bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(color);
    Paint paint = new Paint();
    paint.setColor(Color.WHITE);
    canvas.drawCircle(160, 200, 80, paint);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
    bitmap.recycle();
    return output.toByteArray();
  }

  static LinkedHashMap<String, byte[]> pages() {
    LinkedHashMap<String, byte[]> pages = new LinkedHashMap<>();
    pages.put("page10.png", image(0xff8855aa));
    pages.put("page2.png", image(0xff44aa88));
    pages.put("page1.png", image(0xff4488bb));
    pages.put("page3.png", image(0xffbb7744));
    return pages;
  }

  static File zip(Context context) throws IOException {
    File file = new File(directory(context), "Sample.cbz");
    try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
      LinkedHashMap<String, byte[]> pages = pages();
      pages.put("__MACOSX/hidden.png", image(0xff000000));
      pages.put("page11.png", new byte[] {0, 1, 2});
      for (Map.Entry<String, byte[]> page : pages.entrySet()) {
        output.putNextEntry(new ZipEntry(page.getKey()));
        output.write(page.getValue());
        output.closeEntry();
      }
    }
    return file;
  }

  static void little(OutputStream output, long value, int bytes) throws IOException {
    for (int i = 0; i < bytes; i++) {
      output.write((int) value & 255);
      value >>>= 8;
    }
  }

  static long crc(byte[] bytes) {
    CRC32 crc = new CRC32();
    crc.update(bytes);
    return crc.getValue();
  }

  static void rar4Header(OutputStream output, int type, int flags, byte[] data) throws IOException {
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    header.write(type);
    little(header, flags, 2);
    little(header, data.length + 7, 2);
    header.write(data);
    byte[] bytes = header.toByteArray();
    little(output, crc(bytes), 2);
    output.write(bytes);
  }

  static File rar4(Context context) throws IOException {
    File file = new File(directory(context), "Stored-RAR4.cbr");
    try (OutputStream output = new FileOutputStream(file)) {
      output.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1a, 7, 0});
      rar4Header(output, 0x73, 0, new byte[6]);
      for (Map.Entry<String, byte[]> page : pages().entrySet()) {
        byte[] image = page.getValue(), name = page.getKey().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        little(data, image.length, 4);
        little(data, image.length, 4);
        data.write(3);
        little(data, crc(image), 4);
        little(data, 0, 4);
        data.write(20);
        data.write(0x30);
        little(data, name.length, 2);
        little(data, 0x81b4, 4);
        data.write(name);
        rar4Header(output, 0x74, 0x8000, data.toByteArray());
        output.write(image);
      }
      rar4Header(output, 0x7b, 0x4000, new byte[0]);
    }
    return file;
  }

  static void vint(OutputStream output, long value) throws IOException {
    do {
      int b = (int) value & 127;
      value >>>= 7;
      output.write(value == 0 ? b : b | 128);
    } while (value != 0);
  }

  static void rar5Header(OutputStream output, byte[] data) throws IOException {
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    vint(header, data.length);
    header.write(data);
    byte[] bytes = header.toByteArray();
    little(output, crc(bytes), 4);
    output.write(bytes);
  }

  static File rar5(Context context) throws IOException {
    File file = new File(directory(context), "Stored-RAR5.cbr");
    try (OutputStream output = new FileOutputStream(file)) {
      output.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1a, 7, 1, 0});
      rar5Header(output, new byte[] {1, 0, 0});
      for (Map.Entry<String, byte[]> page : pages().entrySet()) {
        byte[] image = page.getValue(), name = page.getKey().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        vint(data, 2);
        vint(data, 2);
        vint(data, image.length);
        vint(data, 4);
        vint(data, image.length);
        vint(data, 0);
        little(data, crc(image), 4);
        vint(data, 0);
        vint(data, 1);
        vint(data, name.length);
        data.write(name);
        rar5Header(output, data.toByteArray());
        output.write(image);
      }
      rar5Header(output, new byte[] {5, 0, 0});
    }
    return file;
  }

  static void chunk(DataOutputStream output, String type, byte[] data) throws IOException {
    byte[] tag = type.getBytes(StandardCharsets.US_ASCII);
    CRC32 crc = new CRC32();
    crc.update(tag);
    crc.update(data);
    output.writeInt(data.length);
    output.write(tag);
    output.write(data);
    output.writeInt((int) crc.getValue());
  }

  static File large(Context context) throws IOException {
    // A 192-megapixel PNG, generated a row at a time without allocating a scan-sized bitmap.
    ByteArrayOutputStream pixels = new ByteArrayOutputStream();
    try (DeflaterOutputStream compressed = new DeflaterOutputStream(pixels)) {
      byte[] row = new byte[16000 * 3 + 1];
      Arrays.fill(row, (byte) 255);
      row[0] = 0;
      for (int i = 0; i < 12000; i++) compressed.write(row);
    }
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(png)) {
      output.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
      ByteArrayOutputStream metadata = new ByteArrayOutputStream();
      DataOutputStream header = new DataOutputStream(metadata);
      header.writeInt(16000);
      header.writeInt(12000);
      header.write(new byte[] {8, 2, 0, 0, 0});
      chunk(output, "IHDR", metadata.toByteArray());
      chunk(output, "IDAT", pixels.toByteArray());
      chunk(output, "IEND", new byte[0]);
    }
    File file = new File(directory(context), "Large.cbz");
    try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
      output.putNextEntry(new ZipEntry("large.png"));
      output.write(png.toByteArray());
      output.closeEntry();
    }
    return file;
  }
}
