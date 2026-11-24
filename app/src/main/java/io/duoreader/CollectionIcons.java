package io.duoreader;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** One 20-cell pixel language; color is always supplied by the library theme. */
public final class CollectionIcons {
  public static final String[] SETS = {"Library", "Quest", "Nature", "Space"};
  public static final String[] IDS = {
    "folder",
    "books",
    "book",
    "bookmark",
    "quill",
    "scroll",
    "coffee",
    "heart",
    "sword",
    "shield",
    "castle",
    "crown",
    "gem",
    "potion",
    "key",
    "chest",
    "tree",
    "leaf",
    "flower",
    "mountain",
    "sun",
    "moon",
    "mushroom",
    "fish",
    "rocket",
    "planet",
    "star",
    "ufo",
    "robot",
    "alien",
    "comet",
    "satellite"
  };
  public static final String[] NAMES = {
    "Folder",
    "Books",
    "Open book",
    "Bookmark",
    "Quill",
    "Scroll",
    "Coffee",
    "Heart",
    "Sword",
    "Shield",
    "Castle",
    "Crown",
    "Gem",
    "Potion",
    "Key",
    "Treasure",
    "Tree",
    "Leaf",
    "Flower",
    "Mountain",
    "Sun",
    "Moon",
    "Mushroom",
    "Fish",
    "Rocket",
    "Planet",
    "Star",
    "UFO",
    "Robot",
    "Alien",
    "Comet",
    "Satellite"
  };

  public static String valid(String id) {
    for (String known : IDS) if (known.equals(id)) return id;
    return "folder";
  }

  public static int index(String id) {
    for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
    return 0;
  }

  public static Drawable icon(String id, LibraryTheme theme, int size) {
    Pixel d = new Pixel(valid(id), theme);
    d.setBounds(0, 0, size, size);
    return d;
  }

  private static final class Mask {
    int[][] p = new int[20][20];

    void dot(int x, int y, int v) {
      if (x >= 1 && x < 19 && y >= 1 && y < 19) p[y][x] = v;
    }

    void rect(int l, int t, int r, int b, int v) {
      for (int y = t; y < b; y++) for (int x = l; x < r; x++) dot(x, y, v);
    }

    void line(int x, int y, int ex, int ey, int v) {
      int dx = Math.abs(ex - x),
          sx = x < ex ? 1 : -1,
          dy = -Math.abs(ey - y),
          sy = y < ey ? 1 : -1,
          e = dx + dy;
      while (true) {
        dot(x, y, v);
        if (x == ex && y == ey) break;
        int e2 = e * 2;
        if (e2 >= dy) {
          e += dy;
          x += sx;
        }
        if (e2 <= dx) {
          e += dx;
          y += sy;
        }
      }
    }

    void oval(int l, int t, int r, int b, int v) {
      float cx = (l + r - 1) / 2f, cy = (t + b - 1) / 2f, rx = (r - l) / 2f, ry = (b - t) / 2f;
      for (int y = t; y < b; y++)
        for (int x = l; x < r; x++)
          if ((x - cx) * (x - cx) / (rx * rx) + (y - cy) * (y - cy) / (ry * ry) <= 1) dot(x, y, v);
    }

    void triangle(int cx, int top, int half, int bottom, int v) {
      for (int y = top; y <= bottom; y++) {
        int width = Math.round(half * (y - top) / (float) Math.max(1, bottom - top));
        rect(cx - width, y, cx + width + 1, y + 1, v);
      }
    }
  }

  private static Mask mask(String id) {
    Mask m = new Mask();
    switch (id) {
      case "folder":
        m.rect(2, 7, 18, 16, 1);
        m.rect(3, 4, 9, 8, 1);
        m.rect(3, 8, 17, 10, 2);
        break;
      case "books":
        m.rect(3, 4, 7, 17, 1);
        m.rect(8, 3, 12, 17, 1);
        m.line(14, 5, 17, 16, 1);
        m.line(15, 4, 18, 15, 1);
        m.rect(3, 13, 7, 14, 2);
        m.rect(8, 6, 12, 7, 2);
        break;
      case "book":
        m.rect(2, 5, 9, 16, 1);
        m.rect(11, 5, 18, 16, 1);
        m.rect(4, 4, 8, 5, 1);
        m.rect(12, 4, 16, 5, 1);
        m.rect(8, 6, 12, 17, 1);
        m.rect(9, 6, 11, 16, 0);
        m.rect(4, 8, 8, 9, 2);
        m.rect(12, 8, 16, 9, 2);
        m.rect(4, 11, 8, 12, 2);
        m.rect(12, 11, 16, 12, 2);
        break;
      case "bookmark":
        m.rect(5, 3, 15, 16, 1);
        m.rect(8, 14, 12, 17, 0);
        m.rect(9, 13, 11, 14, 0);
        m.rect(7, 5, 13, 7, 2);
        break;
      case "quill":
        m.line(4, 17, 15, 4, 1);
        for (int i = 0; i < 9; i++) {
          m.line(7 + i, 14 - i, 7 + i, 7 - i / 2, 1);
          m.line(7 + i, 13 - i, 15, 12 - i, 1);
        }
        m.line(4, 17, 15, 4, 2);
        break;
      case "scroll":
        m.rect(5, 4, 15, 16, 1);
        m.rect(3, 3, 15, 6, 1);
        m.rect(5, 14, 17, 17, 1);
        m.rect(6, 8, 13, 9, 2);
        m.rect(6, 11, 12, 12, 2);
        break;
      case "coffee":
        m.rect(3, 7, 14, 16, 1);
        m.rect(14, 8, 17, 13, 1);
        m.rect(14, 9, 16, 12, 0);
        m.rect(5, 3, 6, 5, 2);
        m.rect(9, 2, 10, 5, 2);
        m.rect(2, 17, 16, 18, 1);
        break;
      case "heart":
        m.oval(3, 4, 10, 11, 1);
        m.oval(10, 4, 17, 11, 1);
        for (int y = 9; y < 17; y++) m.rect(3 + y - 9, y, 17 - (y - 9), y + 1, 1);
        m.rect(5, 6, 7, 7, 2);
        break;
      case "sword":
        m.line(5, 15, 15, 5, 1);
        m.line(6, 15, 16, 5, 1);
        m.line(5, 14, 15, 4, 1);
        m.rect(13, 3, 17, 7, 1);
        m.line(4, 11, 9, 16, 2);
        m.rect(3, 15, 6, 18, 1);
        break;
      case "shield":
        m.rect(4, 4, 16, 11, 1);
        for (int y = 11; y < 17; y++) m.rect(4 + (y - 11), y, 16 - (y - 11), y + 1, 1);
        m.rect(9, 5, 11, 14, 2);
        m.rect(6, 8, 14, 10, 2);
        break;
      case "castle":
        m.rect(3, 7, 17, 17, 1);
        m.rect(3, 3, 6, 8, 1);
        m.rect(8, 3, 12, 8, 1);
        m.rect(14, 3, 17, 8, 1);
        m.rect(8, 12, 12, 17, 0);
        m.rect(5, 9, 7, 11, 2);
        m.rect(13, 9, 15, 11, 2);
        break;
      case "crown":
        m.rect(3, 10, 17, 16, 1);
        m.triangle(4, 5, 2, 11, 1);
        m.triangle(10, 3, 3, 11, 1);
        m.triangle(16, 5, 2, 11, 1);
        m.rect(5, 13, 15, 14, 2);
        break;
      case "gem":
        m.rect(6, 4, 14, 5, 1);
        m.rect(4, 5, 16, 9, 1);
        for (int y = 9; y < 17; y++) m.rect(4 + (y - 9), y, 16 - (y - 9), y + 1, 1);
        m.line(5, 7, 14, 7, 2);
        m.line(9, 8, 10, 14, 2);
        break;
      case "potion":
        m.rect(7, 3, 13, 5, 2);
        m.rect(8, 5, 12, 8, 1);
        m.oval(4, 7, 16, 18, 1);
        m.rect(6, 12, 14, 15, 2);
        m.rect(6, 9, 8, 11, 2);
        break;
      case "key":
        m.oval(3, 3, 12, 12, 1);
        m.oval(5, 5, 10, 10, 0);
        m.line(10, 10, 16, 16, 1);
        m.line(11, 10, 17, 16, 1);
        m.rect(13, 14, 17, 17, 1);
        m.rect(14, 13, 17, 15, 2);
        break;
      case "chest":
        m.rect(3, 8, 17, 17, 1);
        m.oval(3, 4, 17, 12, 1);
        m.rect(3, 10, 17, 12, 2);
        m.rect(9, 9, 11, 14, 2);
        break;
      case "tree":
        m.triangle(10, 2, 5, 9, 1);
        m.triangle(10, 6, 7, 14, 1);
        m.rect(9, 14, 12, 18, 2);
        break;
      case "leaf":
        m.oval(5, 3, 17, 14, 1);
        m.rect(3, 10, 10, 17, 1);
        m.line(4, 16, 14, 6, 2);
        m.line(9, 11, 9, 6, 2);
        break;
      case "flower":
        m.oval(7, 3, 13, 9, 1);
        m.oval(3, 6, 9, 12, 1);
        m.oval(11, 6, 17, 12, 1);
        m.oval(7, 10, 13, 15, 1);
        m.rect(9, 13, 11, 18, 1);
        m.oval(8, 7, 12, 11, 2);
        m.rect(11, 15, 15, 17, 1);
        break;
      case "mountain":
        m.triangle(8, 3, 6, 17, 1);
        m.triangle(14, 7, 4, 17, 1);
        m.triangle(8, 3, 2, 8, 2);
        break;
      case "sun":
        m.oval(6, 6, 14, 14, 1);
        for (int i = 0; i < 8; i++) {
          double a = i * Math.PI / 4;
          m.line(
              10 + (int) (6 * Math.cos(a)),
              10 + (int) (6 * Math.sin(a)),
              10 + (int) (8 * Math.cos(a)),
              10 + (int) (8 * Math.sin(a)),
              2);
        }
        break;
      case "moon":
        m.oval(3, 3, 17, 17, 1);
        m.oval(8, 1, 19, 14, 0);
        m.rect(5, 11, 7, 13, 2);
        break;
      case "mushroom":
        m.oval(3, 3, 17, 14, 1);
        m.rect(2, 9, 18, 12, 1);
        m.rect(7, 12, 13, 18, 1);
        m.rect(5, 6, 7, 8, 2);
        m.rect(11, 5, 14, 8, 2);
        break;
      case "fish":
        m.oval(3, 6, 14, 15, 1);
        for (int x = 13; x < 18; x++) m.rect(x, 10 - (x - 13), x + 1, 11 + (x - 13), 1);
        m.rect(5, 8, 7, 10, 0);
        m.rect(9, 4, 12, 7, 2);
        break;
      case "rocket":
        m.oval(6, 3, 14, 15, 1);
        m.triangle(10, 2, 3, 7, 1);
        m.rect(8, 7, 12, 10, 2);
        m.rect(4, 12, 7, 17, 1);
        m.rect(13, 12, 16, 17, 1);
        m.rect(9, 15, 11, 18, 2);
        break;
      case "planet":
        m.oval(5, 4, 15, 16, 1);
        m.line(2, 15, 17, 5, 2);
        m.line(2, 14, 17, 4, 2);
        m.rect(8, 6, 11, 7, 2);
        break;
      case "star":
        m.triangle(10, 2, 3, 10, 1);
        m.rect(3, 8, 17, 11, 1);
        m.triangle(10, 9, 6, 16, 1);
        m.rect(8, 13, 12, 17, 0);
        m.rect(9, 8, 11, 10, 2);
        break;
      case "ufo":
        m.oval(6, 4, 14, 11, 1);
        m.oval(2, 9, 18, 14, 1);
        m.rect(4, 11, 6, 12, 2);
        m.rect(9, 11, 11, 12, 2);
        m.rect(14, 11, 16, 12, 2);
        m.rect(6, 16, 7, 18, 2);
        m.rect(13, 16, 14, 18, 2);
        break;
      case "robot":
        m.rect(4, 5, 16, 16, 1);
        m.rect(9, 2, 11, 5, 2);
        m.rect(2, 8, 4, 13, 1);
        m.rect(16, 8, 18, 13, 1);
        m.rect(6, 8, 8, 10, 0);
        m.rect(12, 8, 14, 10, 0);
        m.rect(7, 13, 13, 14, 2);
        break;
      case "alien":
        m.oval(3, 3, 17, 14, 1);
        m.rect(7, 11, 13, 17, 1);
        m.line(5, 7, 8, 10, 0);
        m.line(14, 7, 11, 10, 0);
        m.rect(9, 14, 11, 15, 2);
        break;
      case "comet":
        m.oval(3, 10, 11, 18, 1);
        m.line(6, 12, 16, 2, 2);
        m.line(10, 13, 18, 5, 1);
        m.line(4, 10, 12, 2, 1);
        break;
      case "satellite":
        m.rect(8, 7, 12, 13, 1);
        m.rect(2, 8, 7, 13, 2);
        m.rect(13, 7, 18, 12, 2);
        m.line(9, 6, 13, 3, 1);
        m.rect(8, 14, 11, 17, 1);
        break;
    }
    return m;
  }

  private static final class Pixel extends Drawable {
    final Mask mask;
    final LibraryTheme theme;
    final Paint paint = new Paint();
    int alpha = 255;
    ColorFilter filter;

    Pixel(String id, LibraryTheme theme) {
      this.mask = mask(id);
      this.theme = theme;
      paint.setAntiAlias(false);
    }

    public void draw(Canvas c) {
      c.save();
      c.translate(getBounds().left, getBounds().top);
      c.scale(getBounds().width() / 20f, getBounds().height() / 20f);
      paint.setAlpha(alpha);
      paint.setColorFilter(filter);
      for (int y = 1; y < 19; y++)
        for (int x = 1; x < 19; x++)
          if (mask.p[y][x] != 0) {
            paint.setColor(theme.deep);
            c.drawRect(x - 1, y - 1, x + 2, y + 2, paint);
          }
      for (int y = 1; y < 19; y++)
        for (int x = 1; x < 19; x++)
          if (mask.p[y][x] != 0) {
            paint.setColor(mask.p[y][x] == 2 ? theme.stick : theme.accent);
            c.drawRect(x, y, x + 1, y + 1, paint);
          }
      c.restore();
    }

    public void setAlpha(int a) {
      alpha = a;
      invalidateSelf();
    }

    public void setColorFilter(ColorFilter f) {
      filter = f;
      invalidateSelf();
    }

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
