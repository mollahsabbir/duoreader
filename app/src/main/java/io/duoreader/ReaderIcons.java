package io.duoreader;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.text.*;
import android.text.style.ImageSpan;

/** Small native vector marks, also rendered inside Android's text-selection toolbar. */
public final class ReaderIcons {
  public static String action(String label) {
    if (label.startsWith("Change highlight color")) return "palette";
    if (label.startsWith("Delete")) return "delete";
    if (label.startsWith("Edit")) return "edit";
    if (label.startsWith("Copy")) return "copy";
    if (label.contains("dictionary")) return "dictionary";
    if (label.contains("note") || label.contains("passage")) return "note";
    if (label.startsWith("Move")) return "move";
    if (label.startsWith("Create") || label.startsWith("New")) return "add";
    return null;
  }

  public static Drawable icon(String kind, int color, int size) {
    Icon d = new Icon(kind, color);
    d.setBounds(0, 0, size, size);
    return d;
  }

  public static CharSequence title(Context context, String kind, String label, int color) {
    int size = Math.round(17 * context.getResources().getDisplayMetrics().density);
    SpannableString title = new SpannableString("\ufffc " + label);
    title.setSpan(
        new ImageSpan(icon(kind, color, size), ImageSpan.ALIGN_BASELINE),
        0,
        1,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return title;
  }

  private static final class Icon extends Drawable {
    final String kind;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final int color;

    Icon(String kind, int color) {
      this.kind = kind;
      this.color = color;
    }

    public void draw(Canvas canvas) {
      canvas.save();
      canvas.translate(getBounds().left, getBounds().top);
      canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
      paint.setColor(color);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.7f);
      paint.setStrokeJoin(Paint.Join.ROUND);
      paint.setStrokeCap(Paint.Cap.ROUND);
      Path p = new Path();
      if (kind.equals("palette")) {
        p.moveTo(20, 15);
        p.cubicTo(23, 8, 17, 2, 11, 3);
        p.cubicTo(3, 3, 0, 13, 5, 18);
        p.cubicTo(9, 23, 15, 23, 14, 18);
        p.cubicTo(13, 14, 17, 15, 20, 15);
        p.close();
        canvas.drawPath(p, paint);
        for (float[] dot : new float[][] {{6, 11}, {9, 7}, {14, 6}, {18, 10}})
          canvas.drawCircle(dot[0], dot[1], .8f, paint);
      } else if (kind.equals("delete")) {
        canvas.drawLine(3, 6, 21, 6, paint);
        canvas.drawRoundRect(6, 6, 18, 22, 1, 1, paint);
        canvas.drawRect(9, 2, 15, 6, paint);
        canvas.drawLine(10, 10, 10, 18, paint);
        canvas.drawLine(14, 10, 14, 18, paint);
      } else if (kind.equals("edit")) {
        p.moveTo(4, 16);
        p.lineTo(16, 4);
        p.lineTo(21, 9);
        p.lineTo(9, 21);
        p.lineTo(3, 22);
        p.close();
        canvas.drawPath(p, paint);
        canvas.drawLine(13, 7, 18, 12, paint);
      } else if (kind.equals("add")) {
        canvas.drawLine(12, 4, 12, 20, paint);
        canvas.drawLine(4, 12, 20, 12, paint);
      } else if (kind.equals("move")) {
        canvas.drawLine(3, 12, 21, 12, paint);
        canvas.drawLine(15, 6, 21, 12, paint);
        canvas.drawLine(15, 18, 21, 12, paint);
      } else if (kind.startsWith("align-")) {
        int align = Integer.parseInt(kind.substring(6));
        for (int line = 0; line < 4; line++) {
          float start = align == 2 && line % 2 != 0 ? 8 : 3;
          float end = align == 0 && line % 2 != 0 ? 16 : 21;
          canvas.drawLine(start, 5 + line * 5, end, 5 + line * 5, paint);
        }
      } else if (kind.startsWith("rotate-")) {
        canvas.rotate(Integer.parseInt(kind.substring(7)) - 90, 12, 12);
        canvas.drawRoundRect(4, 2, 20, 18, 2, 2, paint);
        canvas.drawLine(12, 3, 12, 17, paint);
        canvas.drawCircle(12, 21, 1, paint);
      } else if (kind.equals("copy")) {
        canvas.drawRoundRect(8, 8, 21, 21, 1.5f, 1.5f, paint);
        p.moveTo(16, 5);
        p.lineTo(16, 3);
        p.lineTo(3, 3);
        p.lineTo(3, 16);
        p.lineTo(5, 16);
        canvas.drawPath(p, paint);
      } else if (kind.equals("dictionary")) {
        p.moveTo(12, 6);
        p.cubicTo(8, 3, 4, 4, 2, 5);
        p.lineTo(2, 20);
        p.cubicTo(6, 18, 9, 18, 12, 21);
        p.cubicTo(15, 18, 18, 18, 22, 20);
        p.lineTo(22, 5);
        p.cubicTo(18, 4, 15, 3, 12, 6);
        p.lineTo(12, 21);
        canvas.drawPath(p, paint);
        canvas.drawLine(5, 8, 9, 9, paint);
        canvas.drawLine(15, 9, 19, 8, paint);
      } else {
        p.moveTo(4, 3);
        p.lineTo(15, 3);
        p.lineTo(21, 9);
        p.lineTo(21, 21);
        p.lineTo(4, 21);
        p.close();
        p.moveTo(15, 3);
        p.lineTo(15, 9);
        p.lineTo(21, 9);
        canvas.drawPath(p, paint);
        canvas.drawLine(8, 13, 17, 13, paint);
        canvas.drawLine(8, 17, 15, 17, paint);
      }
      canvas.restore();
    }

    public void setAlpha(int alpha) {
      paint.setAlpha(alpha);
    }

    public void setColorFilter(ColorFilter filter) {
      paint.setColorFilter(filter);
    }

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
