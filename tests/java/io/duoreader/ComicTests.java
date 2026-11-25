package io.duoreader;

import android.graphics.Bitmap;
import android.net.Uri;
import java.io.*;
import java.util.zip.*;

public final class ComicTests extends FeatureTest {
  void exercise() throws Exception {
    File hostile = new File(TestComics.directory(app), "Unsafe.cbz");
    try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(hostile))) {
      output.putNextEntry(new ZipEntry("../outside.png"));
      output.write(TestComics.image(0xff000000));
      output.closeEntry();
    }
    boolean unsafe = false;
    try (ComicArchive ignored = ComicArchive.open(hostile, DocumentFormat.CBZ, app.getCacheDir())) {
    } catch (IOException expected) {
      unsafe = true;
    }
    check("Android ZIP traversal protection remains enabled", unsafe);
    File oversized = new File(TestComics.directory(app), "Oversized.cbz");
    try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(oversized))) {
      output.putNextEntry(new ZipEntry("bomb.png"));
      byte[] chunk = new byte[65536];
      for (int i = 0; i < 1025; i++) output.write(chunk);
      output.closeEntry();
    }
    boolean bounded = false;
    try (ComicArchive ignored =
        ComicArchive.open(oversized, DocumentFormat.CBZ, app.getCacheDir())) {
    } catch (IOException expected) {
      bounded = true;
    }
    check("Oversized extracted scans are rejected before decoding", bounded);
    File zip = TestComics.zip(app), rar4 = TestComics.rar4(app), rar5 = TestComics.rar5(app);
    app.prefs.edit().putInt(app.key(Uri.fromFile(zip).toString()) + "comicPage", 0).commit();
    for (File file : new File[] {zip, rar4, rar5}) {
      long start = android.os.SystemClock.uptimeMillis();
      try (ComicArchive archive =
          ComicArchive.open(file, DocumentFormat.of(file.getName()), app.getCacheDir())) {
        check(
            file.getName() + " uses natural page order",
            archive.names.get(0).equals("page1.png")
                && archive.names.get(1).equals("page2.png")
                && archive.names.get(3).equals("page10.png"));
        for (int page : new int[] {0, 3, 1}) {
          Bitmap bitmap = archive.decode(page, 960, 1280);
          check(
              file.getName() + " decodes page " + page,
              bitmap != null && bitmap.getAllocationByteCount() <= 8_000_000);
          bitmap.recycle();
        }
        if (file == zip) {
          check("Metadata paths are excluded", archive.names.size() == 5);
          boolean rejected = false;
          try {
            archive.decode(4, 960, 1280);
          } catch (IOException expected) {
            rejected = true;
          }
          check("Damaged scan reports an error", rejected);
        }
      }
      check(
          file.getName() + " completes within ten seconds",
          android.os.SystemClock.uptimeMillis() - start < 10000);
    }
    File large = TestComics.large(app);
    try (ComicArchive archive = ComicArchive.open(large, DocumentFormat.CBZ, app.getCacheDir())) {
      Bitmap bitmap = archive.decode(0, 1080, 1920);
      check(
          "192-megapixel scan downsamples below 8 MB",
          bitmap.getAllocationByteCount() <= 8_000_000 && bitmap.getWidth() < 16000);
      bitmap.recycle();
    }
    Library.Entry original = app.current;
    ReaderApp.Location position = app.position;
    String mode = app.mode;
    int rotation = app.rotation;
    try {
      ui(() -> app.open(new Library.Entry(Uri.fromFile(zip).toString(), zip.getName(), false)));
      until(
          () ->
              !app.busy
                  && app.mode.equals("comic")
                  && app.comics.page(false) != null
                  && app.comics.page(true) != null,
          "comic spread");
      check("Comic displays two decoded pages", app.comics.page(false) != app.comics.page(true));
      ui(() -> app.comics.turn(1));
      until(() -> app.comics.index() == 2 && app.comics.page(false) != null, "next comic spread");
      check(
          "Comic position persists before display",
          app.prefs.getInt(app.bookKey() + "comicPage", -1) == 2);
      ui(() -> app.library());
      ui(() -> app.resume());
      until(() -> app.mode.equals("comic") && app.comics.page(false) != null, "resume comic");
      check("Library resume retains comic position", app.comics.index() == 2);
      for (int angle : new int[] {0, 90, 180, 270}) {
        ui(
            () -> {
              app.rotation = angle;
              app.appearance();
            });
        until(() -> app.controls.root.getRotation() == angle, "comic rotation");
        check("Comic controls follow rotation " + angle, app.top.pageHost.getRotation() == angle);
      }
    } finally {
      ui(
          () -> {
            if (app.controls.dialog != null) app.controls.dialog.dismiss();
            app.library();
            app.rotation = rotation;
            app.appearance();
          });
      if (original != null) {
        ui(() -> app.open(original));
        until(() -> !app.busy, "restore original document");
        ui(
            () -> {
              if (app.book != null) app.jump(position);
              if (mode.equals("library")) app.library();
            });
      }
    }
  }
}
