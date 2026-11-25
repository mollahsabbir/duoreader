package io.duoreader;

/**
 * Validate persisted or externally supplied values before UI code indexes arrays or lays out text.
 */
public final class ReaderSettings {
  public static void sanitize(ReaderApp app) {
    app.theme = clamp(app.theme, 0, LibraryTheme.ALL.length - 1);
    app.paper = clamp(app.paper, 0, ReaderApp.PAPER_NAMES.length - 1);
    app.font = clamp(app.font, 0, ReaderApp.FONTS.length - 1);
    app.fontSize = clamp(app.fontSize, 12, 32);
    app.upperFontSize = clamp(app.upperFontSize, 12, 32);
    app.lowerFontSize = clamp(app.lowerFontSize, 12, 32);
    app.margin = clamp(app.margin, 8, 64);
    app.rotation = ReadingOrientation.normalize(app.rotation);
    app.spacing =
        Float.isNaN(app.spacing) || Float.isInfinite(app.spacing)
            ? 1.4f
            : Math.max(1, Math.min(2, app.spacing));
    app.brightness =
        Float.isNaN(app.brightness) || Float.isInfinite(app.brightness) || app.brightness < 0
            ? -1
            : Math.max(.05f, Math.min(1, app.brightness));
  }

  private static int clamp(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }
}
