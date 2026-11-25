package io.duoreader;

import java.util.Locale;

/** One format registry shared by browsing, opening, previews, and file creation. */
public enum DocumentFormat {
  EPUB("application/epub+zip"),
  TEXT("text/plain"),
  MARKDOWN("text/markdown"),
  CBZ("application/vnd.comicbook+zip"),
  CBR("application/vnd.comicbook-rar"),
  UNKNOWN("application/octet-stream");
  public final String mime;

  DocumentFormat(String mime) {
    this.mime = mime;
  }

  public boolean editable() {
    return this == TEXT || this == MARKDOWN;
  }

  public boolean comic() {
    return this == CBZ || this == CBR;
  }

  public static DocumentFormat of(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".epub")) return EPUB;
    if (lower.endsWith(".txt")) return TEXT;
    if (lower.endsWith(".md") || lower.endsWith(".markdown")) return MARKDOWN;
    if (lower.endsWith(".cbz")) return CBZ;
    if (lower.endsWith(".cbr")) return CBR;
    return UNKNOWN;
  }

  public static boolean supported(String name) {
    DocumentFormat format = of(name);
    return format == EPUB || format.editable();
  }
}
