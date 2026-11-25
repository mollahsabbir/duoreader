package io.duoreader;

import android.graphics.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.util.*;
import org.json.*;

public final class CurlTests extends FeatureTest {
  void picture(String name, android.view.View view) {
    Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
    try (OutputStream output = new FileOutputStream(new File(app.getFilesDir(), name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
    } catch (IOException error) {
      throw new RuntimeException(error);
    }
    bitmap.recycle();
  }

  void geometryAndPixels() throws Exception {
    Bitmap before = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888),
        after = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888),
        frame = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888);
    try {
      Canvas source = new Canvas(before);
      source.drawColor(Color.WHITE);
      Paint paint = new Paint();
      paint.setColor(Color.BLUE);
      source.drawRect(0, 0, 200, 1400, paint);
      paint.setColor(Color.RED);
      source.drawRect(800, 0, 1000, 1400, paint);
      new Canvas(after).drawColor(0xff3d996d);
      Canvas canvas = new Canvas(frame);
      PageCurlRenderer renderer = new PageCurlRenderer();
      renderer.draw(canvas, before, after, .2f, 1, .5f, 0, Color.WHITE);
      int rightText = frame.getPixel(650, 700);
      check(
          "Forward flap reflects the right source edge",
          Color.red(rightText) > Color.blue(rightText) + 25);
      renderer.draw(canvas, before, after, .2f, -1, .5f, 0, Color.WHITE);
      int leftText = frame.getPixel(350, 700);
      check(
          "Backward flap reflects the left source edge",
          Color.blue(leftText) > Color.red(leftText) + 25);
      renderer.draw(canvas, before, after, 0, 1, .1f, .1f, Color.WHITE);
      check(
          "Turn begins with the exact original page",
          frame.getPixel(900, 700) == Color.RED && frame.getPixel(100, 700) == Color.BLUE);
      renderer.draw(canvas, before, after, 1, 1, .1f, .1f, Color.WHITE);
      check(
          "Turn ends with the exact destination page",
          frame.getPixel(900, 700) == after.getPixel(900, 700));
      Path outline = new Path();
      RectF early = new RectF(), later = new RectF();
      renderer.geometry.update(1000, 1400, .03f, 1, 0, 0);
      renderer.outline(outline, 0, Float.POSITIVE_INFINITY);
      outline.computeBounds(early, true);
      check(
          "A top-corner turn lifts a corner rather than the full edge",
          early.height() < 1400 * .5f && early.top < 10);
      renderer.geometry.update(1000, 1400, .03f, 1, 1, 0);
      renderer.outline(outline, 0, Float.POSITIVE_INFINITY);
      outline.computeBounds(later, true);
      check("Bottom-corner turns start at the bottom", later.top > 700 && later.bottom > 1390);
      renderer.geometry.update(1000, 1400, .1f, 1, .5f, 0);
      renderer.outline(outline, 0, Float.POSITIVE_INFINITY);
      outline.computeBounds(early, true);
      renderer.geometry.update(1000, 1400, .3f, 1, .5f, 0);
      renderer.outline(outline, 0, Float.POSITIVE_INFINITY);
      outline.computeBounds(later, true);
      check("The lifted flap widens while crossing the page", later.width() > early.width() * 2);
      for (int direction : new int[] {1, -1})
        for (float progress : new float[] {.001f, .02f, .1f, .5f, .9f, .999f})
          for (float origin : new float[] {0, .5f, 1})
            renderer.draw(canvas, before, after, progress, direction, origin, .16f, Color.WHITE);
      check("Both directions remain stable at corners and endpoints", true);
    } finally {
      before.recycle();
      after.recycle();
      frame.recycle();
    }
  }

  void exercise() throws Exception {
    geometryAndPixels();
    Library.Entry original = app.current;
    ReaderApp.Location position = app.position;
    String mode = app.mode;
    int size = app.fontSize, rotation = app.rotation;
    float spacing = app.spacing;
    try {
      File fixture = TestBooks.create(app);
      ui(
          () -> {
            app.fontSize = 22;
            app.spacing = 1.3f;
            app.rotation = 90;
            app.appearance();
            app.open(
                new Library.Entry(
                    android.net.Uri.fromFile(fixture).toString(), fixture.getName(), false));
          });
      until(
          () -> !app.busy && app.mode.equals("reading") && app.controls.paper != null,
          "fixture pages");
      ui(() -> app.jump(new ReaderApp.Location(1, 0)));
      SystemClock.sleep(250);
      waitForIdleSync();
      ui(
          () -> {
            app.turns.beginDrag(1);
            app.turns.drag(.4f, .5f, 0);
          });
      check(
          "A held edge travels with the finger rather than twice as far",
          app.turns.active && Math.abs(app.turns.lowerProgress - .2f) < .001f);
      check(
          "Held curl keeps the other screen and saved anchor stationary",
          app.turns.upperProgress == 0 && app.position.equals(new ReaderApp.Location(1, 0)));
      ui(
          () -> {
            picture("curl-side", app.controls.paper);
            app.turns.drag(.45f, .04f, .04f);
            picture("curl-top-corner", app.controls.paper);
            app.turns.drag(.75f, .92f, -.05f);
            picture("curl-bottom-corner", app.controls.paper);
            app.turns.cancel();
            app.turns.beginDrag(-1);
            app.turns.drag(.6f, .1f, .05f);
            picture("curl-backward", app.top.paper);
            app.turns.cancel();
          });
      HandlerThread sampling = new HandlerThread("curl-frame-metrics");
      sampling.start();
      ArrayList<Long> draw = new ArrayList<>(), total = new ArrayList<>();
      Window.OnFrameMetricsAvailableListener sample =
          (window, metrics, dropped) -> {
            synchronized (draw) {
              if (draw.size() < 1000) {
                draw.add(metrics.getMetric(FrameMetrics.DRAW_DURATION));
                total.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION));
              }
            }
          };
      try {
        for (int warmup = 0; warmup < 2; warmup++) {
          final int direction = warmup % 2 == 0 ? 1 : -1;
          ui(() -> app.turns.start(direction));
          until(() -> !app.turns.active, "warm curl");
        }
        ui(
            () -> {
              app.top
                  .getWindow()
                  .addOnFrameMetricsAvailableListener(sample, new Handler(sampling.getLooper()));
              app.bottom
                  .getWindow()
                  .addOnFrameMetricsAvailableListener(sample, new Handler(sampling.getLooper()));
            });
        for (int turn = 0; turn < 6; turn++) {
          final int direction = turn % 2 == 0 ? 1 : -1;
          ui(() -> app.turns.start(direction));
          until(() -> !app.turns.active, "measured curl");
        }
      } finally {
        ui(
            () -> {
              app.top.getWindow().removeOnFrameMetricsAvailableListener(sample);
              app.bottom.getWindow().removeOnFrameMetricsAvailableListener(sample);
            });
        sampling.quitSafely();
        sampling.join(2000);
      }
      synchronized (draw) {
        Collections.sort(draw);
        Collections.sort(total);
        check("Both screens contribute animation frame measurements", draw.size() >= 30);
        long p95Draw = draw.get(Math.min(draw.size() - 1, (int) (draw.size() * .95)));
        JSONObject benchmark =
            new JSONObject()
                .put("frames", draw.size())
                .put("draw_p95_ms", p95Draw / 1e6)
                .put("frame_median_ms", total.get(total.size() / 2) / 1e6)
                .put(
                    "frame_p95_ms",
                    total.get(Math.min(total.size() - 1, (int) (total.size() * .95))) / 1e6);
        try (Writer writer =
            new OutputStreamWriter(
                new FileOutputStream(new File(app.getFilesDir(), "curl-performance.json")),
                "UTF-8")) {
          writer.write(benchmark.toString(2));
        }
        check("Curl drawing stays within a 60 Hz frame budget", p95Draw < 16_666_667);
      }
    } finally {
      ui(
          () -> {
            app.turns.cancel();
            app.fontSize = size;
            app.rotation = rotation;
            app.spacing = spacing;
            app.appearance();
            app.library();
          });
      if (original != null) {
        ui(() -> app.open(original));
        until(() -> !app.busy, "restore book");
        ui(
            () -> {
              if (app.book != null) app.jump(position);
              if (mode.equals("library")) app.library();
            });
      }
    }
  }
}
