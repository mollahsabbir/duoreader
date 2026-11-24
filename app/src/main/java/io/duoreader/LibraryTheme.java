package io.duoreader;

import android.graphics.Color;

/** Library palettes sampled visually from Retroid's official five Duo finishes. */
public final class LibraryTheme {
  public final String name;
  public final int shell,
      surface,
      soft,
      ink,
      muted,
      accent,
      line,
      onAccent,
      a,
      b,
      x,
      y,
      stick,
      deep;

  private LibraryTheme(
      String name,
      int shell,
      int surface,
      int soft,
      int ink,
      int muted,
      int accent,
      int line,
      int onAccent,
      int a,
      int b,
      int x,
      int y,
      int stick,
      int deep) {
    this.name = name;
    this.shell = shell;
    this.surface = surface;
    this.soft = soft;
    this.ink = ink;
    this.muted = muted;
    this.accent = accent;
    this.line = line;
    this.onAccent = onAccent;
    this.a = a;
    this.b = b;
    this.x = x;
    this.y = y;
    this.stick = stick;
    this.deep = deep;
  }

  public static final LibraryTheme[] ALL = {
    new LibraryTheme(
        "Black",
        0xff181c20,
        0xff252b31,
        0xff343d45,
        0xfff1f2ed,
        0xffb2bbc0,
        0xffd0d9df,
        0xff4c5862,
        0xff20272c,
        0xffb8bec3,
        0xffb8bec3,
        0xffb8bec3,
        0xffb8bec3,
        0xff747d83,
        0xff101418),
    new LibraryTheme(
        "16Bit",
        0xffdedbd5,
        0xfff6f3eb,
        0xffe4e8ed,
        0xff30343c,
        0xff626976,
        0xff4a60a5,
        0xffb6bbc3,
        0xffffffff,
        0xffd9494d,
        0xffebbd28,
        0xff3d69bf,
        0xff39a65a,
        0xff575f69,
        0xff30384b),
    new LibraryTheme(
        "GC",
        0xff4f548b,
        0xff343b69,
        0xff495387,
        0xfff3f2ed,
        0xffc5cbea,
        0xffc0c9ff,
        0xff747fb0,
        0xff252d58,
        0xff62d37b,
        0xffff786c,
        0xfff0ede6,
        0xfff0ede6,
        0xfff2cf4d,
        0xff202747),
    new LibraryTheme(
        "Ice Blue",
        0xff225c61,
        0xff163e45,
        0xff28575d,
        0xffedfaf3,
        0xffb8dcd6,
        0xff9be3db,
        0xff5f9295,
        0xff173d40,
        0xff8ad4cc,
        0xff8ad4cc,
        0xff8ad4cc,
        0xff8ad4cc,
        0xff64787a,
        0xff102e34),
    new LibraryTheme(
        "Clear Purple",
        0xff736182,
        0xff493d59,
        0xff625172,
        0xfff8f0fa,
        0xffddcae5,
        0xffead0fa,
        0xffa48bae,
        0xff453051,
        0xffd5c5df,
        0xffd5c5df,
        0xffd5c5df,
        0xffd5c5df,
        0xff736b7a,
        0xff30283c)
  };

  public static LibraryTheme get(int theme) {
    return ALL[Math.max(0, Math.min(ALL.length - 1, theme))];
  }

  public static int mix(int a, int b, float amount) {
    return Color.rgb(
        Math.round(Color.red(a) * (1 - amount) + Color.red(b) * amount),
        Math.round(Color.green(a) * (1 - amount) + Color.green(b) * amount),
        Math.round(Color.blue(a) * (1 - amount) + Color.blue(b) * amount));
  }

  public int keyColor(String key) {
    return key.equals("A") ? a : key.equals("B") ? b : key.equals("X") ? x : y;
  }
}
