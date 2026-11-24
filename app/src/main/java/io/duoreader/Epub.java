package io.duoreader;

import android.graphics.*;
import android.graphics.drawable.*;
import android.text.*;
import android.text.style.*;
import java.io.*;
import java.net.URLDecoder;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Offline EPUB 2/3 spine, metadata, navigation, illustrations and text. Never extracts paths. */
public final class Epub implements Closeable {
  public static final class Chapter {
    public String path, title;

    Chapter(String p, String t) {
      path = p;
      title = t;
    }
  }

  public static final class Toc {
    public String label, path, fragment;
    public int chapter;

    Toc(String l, String p, String f, int c) {
      label = l;
      path = p;
      fragment = f;
      chapter = c;
    }
  }

  public final ArrayList<Chapter> chapters = new ArrayList<>();
  public final ArrayList<Toc> toc = new ArrayList<>();
  public String title = "Untitled", author = "Unknown author", description = "", coverPath = "";
  private final ZipFile zip;
  public final File file;
  private boolean closed;
  private static final java.util.regex.Pattern SCRIPT_STYLE =
      java.util.regex.Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
  private static final java.util.regex.Pattern BODY =
      java.util.regex.Pattern.compile("(?is)<body[^>]*>(.*)</body>");
  private final android.util.LruCache<String, Spanned> textCache =
      new android.util.LruCache<String, Spanned>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Spanned value) {
          return textBytes(value);
        }
      };
  private final android.util.LruCache<Integer, String> plainCache =
      new android.util.LruCache<Integer, String>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(Integer key, String value) {
          return Math.max(1, value.length() * 2);
        }
      };

  static int textBytes(Spanned value) {
    long size = value.length() * 2L + 128;
    for (ImageSpan span : value.getSpans(0, value.length(), ImageSpan.class))
      if (span.getDrawable() instanceof BitmapDrawable) {
        Bitmap b = ((BitmapDrawable) span.getDrawable()).getBitmap();
        if (b != null) size += b.getAllocationByteCount();
      }
    return (int) Math.min(Integer.MAX_VALUE, Math.max(1, size));
  }

  public Epub(File file) throws Exception {
    this.file = file;
    zip = new ZipFile(file);
    try {
      parse();
    } catch (Exception e) {
      zip.close();
      throw e;
    }
  }

  static Document xml(byte[] data) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    // Android's built-in DOM parser does not implement all desktop SAX feature URIs.
    String input = new String(data, "UTF-8");
    if (java.util.regex.Pattern.compile("(?i)<!ENTITY\\s").matcher(input).find())
      throw new IOException("Entity declarations are not supported in EPUB metadata");
    for (String feature :
        new String[] {
          "http://xml.org/sax/features/external-general-entities",
          "http://xml.org/sax/features/external-parameter-entities",
          "http://apache.org/xml/features/nonvalidating/load-external-dtd"
        })
      try {
        f.setFeature(feature, false);
      } catch (ParserConfigurationException ignored) {
      }
    f.setExpandEntityReferences(false);
    DocumentBuilder builder = f.newDocumentBuilder();
    builder.setEntityResolver(
        (publicId, systemId) -> new org.xml.sax.InputSource(new StringReader("")));
    return builder.parse(new ByteArrayInputStream(data));
  }

  public byte[] bytes(String path) throws IOException {
    if (closed) throw new IOException("This book is closed");
    ZipEntry entry = zip.getEntry(path);
    if (entry == null) throw new IOException("Missing EPUB resource: " + path);
    if (entry.getSize() > 16 * 1024 * 1024) throw new IOException("EPUB resource exceeds 16 MB");
    try (InputStream in = zip.getInputStream(entry);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) {
        if (out.size() + n > 16 * 1024 * 1024) throw new IOException("EPUB resource exceeds 16 MB");
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    }
  }

  public static String resolve(String base, String href) {
    String clean = href.split("#", 2)[0];
    try {
      clean = URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8");
    } catch (Exception ignored) {
    }
    String parent = base.contains("/") ? base.substring(0, base.lastIndexOf('/') + 1) : "";
    ArrayDeque<String> parts = new ArrayDeque<>();
    for (String s : (parent + clean).split("/")) {
      if (s.equals("..")) {
        if (!parts.isEmpty()) parts.removeLast();
      } else if (!s.equals(".") && !s.isEmpty()) parts.add(s);
    }
    return String.join("/", parts);
  }

  private static String first(Document d, String tag, String fallback) {
    NodeList nodes = d.getElementsByTagNameNS("*", tag);
    if (nodes.getLength() == 0) return fallback;
    String text = nodes.item(0).getTextContent().trim();
    return text.isEmpty() ? fallback : text;
  }

  private static String bounded(String text, int max) {
    if (text.length() <= max) return text;
    int end = max;
    if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
    return text.substring(0, end) + "…";
  }

  private void parse() throws Exception {
    Document container = xml(bytes("META-INF/container.xml"));
    NodeList roots = container.getElementsByTagNameNS("*", "rootfile");
    if (roots.getLength() == 0) throw new IOException("EPUB has no package document");
    String opf = ((Element) roots.item(0)).getAttribute("full-path");
    Document packageDoc = xml(bytes(opf));
    title = bounded(first(packageDoc, "title", title), 512);
    author = bounded(first(packageDoc, "creator", author), 512);
    description =
        bounded(
            Html.fromHtml(first(packageDoc, "description", ""), Html.FROM_HTML_MODE_COMPACT)
                .toString(),
            8192);
    HashMap<String, Element> manifest = new HashMap<>();
    String nav = "", ncx = "", coverId = "";
    NodeList metas = packageDoc.getElementsByTagNameNS("*", "meta");
    for (int i = 0; i < metas.getLength(); i++) {
      Element e = (Element) metas.item(i);
      if (e.getAttribute("name").equals("cover")) coverId = e.getAttribute("content");
    }
    NodeList items = packageDoc.getElementsByTagNameNS("*", "item");
    for (int i = 0; i < items.getLength(); i++) {
      Element e = (Element) items.item(i);
      manifest.put(e.getAttribute("id"), e);
      String props = e.getAttribute("properties"), path = resolve(opf, e.getAttribute("href"));
      if (Arrays.asList(props.split("\\s+")).contains("nav")) nav = path;
      if (e.getAttribute("media-type").equals("application/x-dtbncx+xml")) ncx = path;
      if (props.contains("cover-image") || e.getAttribute("id").equals(coverId)) coverPath = path;
    }
    NodeList spine = packageDoc.getElementsByTagNameNS("*", "itemref");
    for (int i = 0; i < spine.getLength(); i++) {
      Element ref = (Element) spine.item(i), item = manifest.get(ref.getAttribute("idref"));
      if (item == null || ref.getAttribute("linear").equals("no")) continue;
      String media = item.getAttribute("media-type");
      if (media.contains("html"))
        chapters.add(
            new Chapter(
                resolve(opf, item.getAttribute("href")), "Section " + (chapters.size() + 1)));
      if (chapters.size() > 2000) throw new IOException("EPUB contains too many sections");
    }
    if (chapters.isEmpty()) throw new IOException("No readable XHTML spine in this EPUB");
    try {
      if (!nav.isEmpty()) parseNav(nav);
      if (toc.isEmpty() && !ncx.isEmpty()) parseNcx(ncx);
    } catch (Exception ignored) {
      /* malformed navigation must not prevent reading */
    }
    for (Toc t : toc)
      if (t.chapter >= 0 && chapters.get(t.chapter).title.startsWith("Section "))
        chapters.get(t.chapter).title = t.label;
    if (toc.isEmpty())
      for (int i = 0; i < chapters.size(); i++)
        toc.add(new Toc(chapters.get(i).title, chapters.get(i).path, "", i));
  }

  private void addToc(String label, String base, String href) {
    if (href.matches("^[a-zA-Z]+:.*")) return;
    String path = resolve(base, href),
        frag = href.contains("#") ? href.substring(href.indexOf('#') + 1) : "";
    for (int c = 0; c < chapters.size(); c++)
      if (chapters.get(c).path.equals(path)) {
        if (toc.size() < 10000) toc.add(new Toc(bounded(label.trim(), 512), path, frag, c));
        break;
      }
  }

  private void parseNav(String path) throws Exception {
    Document doc = xml(bytes(path));
    NodeList navs = doc.getElementsByTagNameNS("*", "nav");
    Element root = null;
    for (int i = 0; i < navs.getLength(); i++) {
      Element nav = (Element) navs.item(i);
      if (nav.getAttribute("epub:type").contains("toc")
          || nav.getAttributeNS("http://www.idpf.org/2007/ops", "type").contains("toc")) {
        root = nav;
        break;
      }
    }
    if (root == null && navs.getLength() > 0) root = (Element) navs.item(0);
    if (root == null) return;
    NodeList links = root.getElementsByTagNameNS("*", "a");
    for (int i = 0; i < links.getLength(); i++) {
      Element e = (Element) links.item(i);
      addToc(e.getTextContent(), path, e.getAttribute("href"));
    }
  }

  private void parseNcx(String path) throws Exception {
    NodeList points = xml(bytes(path)).getElementsByTagNameNS("*", "navPoint");
    for (int i = 0; i < points.getLength(); i++) {
      Element e = (Element) points.item(i);
      NodeList labels = e.getElementsByTagNameNS("*", "text"),
          contents = e.getElementsByTagNameNS("*", "content");
      if (labels.getLength() > 0 && contents.getLength() > 0)
        addToc(
            labels.item(0).getTextContent(),
            path,
            ((Element) contents.item(0)).getAttribute("src"));
    }
  }

  public Bitmap cover(int max) {
    try {
      return decode(bytes(coverPath), max, max * 2);
    } catch (Exception e) {
      return null;
    }
  }

  static Bitmap decode(byte[] data, int width, int height) {
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(data, 0, data.length, o);
    o.inSampleSize = 1;
    while (o.outWidth / o.inSampleSize > width * 2 || o.outHeight / o.inSampleSize > height * 2)
      o.inSampleSize *= 2;
    o.inJustDecodeBounds = false;
    Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, o);
    if (bitmap == null) return null;
    float fit =
        Math.min(
            1f, Math.min(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight()));
    if (fit < 1) {
      Bitmap scaled =
          Bitmap.createScaledBitmap(
              bitmap,
              Math.max(1, Math.round(bitmap.getWidth() * fit)),
              Math.max(1, Math.round(bitmap.getHeight() * fit)),
              true);
      if (scaled != bitmap) bitmap.recycle();
      return scaled;
    }
    return bitmap;
  }

  public synchronized Spanned text(int chapter, int width, int imageHeight) throws IOException {
    String key = chapter + ":" + width + ":" + imageHeight;
    Spanned cached = textCache.get(key);
    if (cached != null) return cached;
    String path = chapters.get(chapter).path;
    String html = bodyHtml(path);
    final String base = path;
    Spanned parsed =
        Html.fromHtml(
            html,
            Html.FROM_HTML_MODE_COMPACT,
            src -> {
              try {
                Bitmap bitmap = decode(bytes(resolve(base, src)), width, imageHeight);
                if (bitmap == null) return null;
                float scale =
                    Math.min(
                        1f,
                        Math.min(
                            width / (float) bitmap.getWidth(),
                            imageHeight / (float) bitmap.getHeight()));
                BitmapDrawable d = new BitmapDrawable(null, bitmap);
                d.setBounds(
                    0,
                    0,
                    Math.max(1, (int) (bitmap.getWidth() * scale)),
                    Math.max(1, (int) (bitmap.getHeight() * scale)));
                return d;
              } catch (Exception e) {
                ColorDrawable d = new ColorDrawable(Color.TRANSPARENT);
                d.setBounds(0, 0, 1, 1);
                return d;
              }
            },
            null);
    SpannableStringBuilder result = new SpannableStringBuilder(parsed);
    for (URLSpan span : result.getSpans(0, result.length(), URLSpan.class)) result.removeSpan(span);
    while (result.length() > 0 && Character.isWhitespace(result.charAt(result.length() - 1)))
      result.delete(result.length() - 1, result.length());
    if (result.length() == 0) result.append(" ");
    cached = new SpannedString(result);
    textCache.put(key, cached);
    return cached;
  }

  private String bodyHtml(String path) throws IOException {
    String html = SCRIPT_STYLE.matcher(new String(bytes(path), "UTF-8")).replaceAll("");
    java.util.regex.Matcher body = BODY.matcher(html);
    return body.find() ? body.group(1) : html;
  }

  public synchronized String plainText(int chapter) throws IOException {
    String cached = plainCache.get(chapter);
    if (cached != null) return cached;
    Spanned parsed =
        Html.fromHtml(
            bodyHtml(chapters.get(chapter).path),
            Html.FROM_HTML_MODE_COMPACT,
            src -> {
              ColorDrawable empty = new ColorDrawable(Color.TRANSPARENT);
              empty.setBounds(0, 0, 1, 1);
              return empty;
            },
            null);
    String result = parsed.toString();
    int end = result.length();
    while (end > 0 && Character.isWhitespace(result.charAt(end - 1))) end--;
    result = result.substring(0, end);
    if (result.isEmpty()) result = " ";
    plainCache.put(chapter, result);
    return result;
  }

  public synchronized void trim() {
    textCache.evictAll();
    plainCache.evictAll();
  }

  public int anchor(Toc t) {
    if (t.fragment.isEmpty()) return 0;
    try {
      String html = new String(bytes(t.path), "UTF-8");
      java.util.regex.Matcher body =
          java.util.regex.Pattern.compile("(?is)<body[^>]*>(.*)</body>").matcher(html);
      if (body.find()) html = body.group(1);
      java.util.regex.Matcher id =
          java.util.regex.Pattern.compile(
                  "(?is)<[^>]+(?:id|name)\\s*=\\s*['\"]"
                      + java.util.regex.Pattern.quote(t.fragment)
                      + "['\"][^>]*>")
              .matcher(html);
      if (id.find())
        return Html.fromHtml(html.substring(0, id.start()), Html.FROM_HTML_MODE_COMPACT).length();
    } catch (Exception ignored) {
    }
    return 0;
  }

  public synchronized void close() throws IOException {
    if (!closed) {
      closed = true;
      textCache.evictAll();
      plainCache.evictAll();
      zip.close();
    }
  }
}
