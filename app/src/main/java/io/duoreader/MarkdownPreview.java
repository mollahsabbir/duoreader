package io.duoreader;

import android.text.Html;
import android.text.Spanned;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** Offline CommonMark preview. Native spans never load remote images or execute HTML. */
final class MarkdownPreview {
  static Spanned render(String source) {
    if (source.length() > 256 * 1024)
      throw new IllegalArgumentException(
          "Markdown previews support up to 262,144 characters. Source editing is still available.");
    String html =
        HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build()
            .render(Parser.builder().build().parse(source));
    return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, null, null);
  }
}
