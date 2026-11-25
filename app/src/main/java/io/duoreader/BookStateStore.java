package io.duoreader;

import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Map;

/** URI-based identities with a non-destructive migration from the original 32-bit keys. */
public final class BookStateStore {
  private final SharedPreferences preferences;
  private final Map<String, String> keys =
      new LinkedHashMap<String, String>(256, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
          return size() > 256;
        }
      };

  public BookStateStore(SharedPreferences preferences) {
    this.preferences = preferences;
  }

  public synchronized String key(String uri) {
    String cached = keys.get(uri);
    if (cached != null) return cached;
    String prefix = "book.sha256." + Library.id(uri) + ".";
    migrate(uri, prefix);
    keys.put(uri, prefix);
    return prefix;
  }

  private void migrate(String uri, String prefix) {
    if (preferences.contains(prefix + "uri")) return;
    String hash = Integer.toHexString(uri.hashCode());
    String legacy = "book." + hash + ".";
    if (!preferences.contains(legacy + "chapter") && !preferences.contains(legacy + "marks"))
      return;
    String ownerKey = "legacy.owner." + hash;
    String owner = preferences.getString(ownerKey, "");
    String last = preferences.getString("lastUri", "");
    if (owner.isEmpty() && !last.isEmpty() && last.hashCode() == uri.hashCode()) owner = last;
    if (!owner.isEmpty() && !owner.equals(uri)) return;
    SharedPreferences.Editor edit =
        preferences.edit().putString(ownerKey, uri).putString(prefix + "uri", uri);
    for (String field : new String[] {"chapter", "offset", "percent"}) {
      if (preferences.contains(legacy + field))
        edit.putInt(prefix + field, preferences.getInt(legacy + field, 0));
    }
    for (String field : new String[] {"marks", "title"}) {
      if (preferences.contains(legacy + field))
        edit.putString(prefix + field, preferences.getString(legacy + field, ""));
    }
    if (!edit.commit())
      android.util.Log.e(
          "DuoReader", "Could not persist reading-data migration; original data is retained");
  }

  public synchronized void relocate(String oldUri, String newUri) {
    if (oldUri.equals(newUri)) return;
    String oldKey = key(oldUri), newKey = key(newUri);
    SharedPreferences.Editor edit = preferences.edit().putString(newKey + "uri", newUri);
    for (String field : new String[] {"chapter", "offset", "percent", "comicPage"})
      if (preferences.contains(oldKey + field))
        edit.putInt(newKey + field, preferences.getInt(oldKey + field, 0));
    for (String field : new String[] {"marks", "title"})
      if (preferences.contains(oldKey + field))
        edit.putString(newKey + field, preferences.getString(oldKey + field, ""));
    // Retain the previous identity as recovery data if provider or preference writes fail.
    if (!edit.commit())
      throw new IllegalStateException(
          "The file moved, but reading data could not be migrated; its previous data is retained");
  }
}
