package io.duoreader;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Entirely local WordNet lookup, including common inflected forms and exceptions. */
public final class EnglishDictionary {
  private final Context context;
  private SQLiteDatabase database;

  public EnglishDictionary(Context context) {
    this.context = context;
  }

  private void open() throws IOException {
    if (database != null) return;
    File file = new File(context.getFilesDir(), "english-wordnet-v1.sqlite");
    if (!file.exists()) {
      File temporary = new File(file.getPath() + ".part");
      try (InputStream in =
              new GZIPInputStream(context.getAssets().open("english-wordnet.sqlite.gz"));
          FileOutputStream out = new FileOutputStream(temporary)) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) {
          if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Lookup cancelled");
          out.write(buffer, 0, n);
        }
        out.getFD().sync();
      }
      if (!temporary.renameTo(file)) throw new IOException("Cannot prepare the English dictionary");
    }
    database =
        SQLiteDatabase.openDatabase(
            file.getPath(),
            null,
            SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
  }

  public synchronized String lookup(String selection) throws IOException {
    open();
    String word =
        selection
            .toLowerCase(Locale.ENGLISH)
            .trim()
            .replace('’', '\'')
            .replaceAll("^[^a-z]+|[^a-z'-]+$", "");
    if (word.endsWith("'s")) word = word.substring(0, word.length() - 2);
    LinkedHashSet<String> candidates = new LinkedHashSet<>();
    candidates.add(word);
    try (Cursor c =
        database.rawQuery("SELECT lemma FROM exceptions WHERE word=?", new String[] {word})) {
      while (c.moveToNext()) candidates.add(c.getString(0));
    }
    String[][] rules = {
      {"ies", "y"},
      {"ves", "f"},
      {"ves", "fe"},
      {"ing", ""},
      {"ing", "e"},
      {"ed", ""},
      {"ed", "e"},
      {"es", ""},
      {"s", ""},
      {"er", ""},
      {"est", ""},
      {"er", "e"},
      {"est", "e"}
    };
    for (String[] rule : rules)
      if (word.endsWith(rule[0]) && word.length() > rule[0].length() + 1) {
        String stem = word.substring(0, word.length() - rule[0].length()) + rule[1];
        candidates.add(stem);
        if ((rule[0].equals("ing") || rule[0].equals("ed"))
            && stem.length() > 2
            && stem.charAt(stem.length() - 1) == stem.charAt(stem.length() - 2))
          candidates.add(stem.substring(0, stem.length() - 1));
      }
    StringBuilder answer = new StringBuilder();
    int entries = 0;
    for (String candidate : candidates) {
      StringBuilder result = new StringBuilder();
      for (String kind : new String[] {"noun", "verb", "adj", "adv"})
        try (Cursor c =
            database.rawQuery(
                "SELECT DISTINCT d.gloss FROM words w JOIN definitions d ON d.id=w.sense WHERE"
                    + " w.word=? AND d.pos=? ORDER BY w.rank LIMIT 3",
                new String[] {candidate, kind})) {
          if (c.moveToFirst()) {
            if (result.length() > 0) result.append("\n\n");
            result.append(
                kind.equals("adj")
                    ? "Adjective"
                    : kind.equals("adv") ? "Adverb" : kind.equals("noun") ? "Noun" : "Verb");
            int number = 1;
            do {
              result.append("\n").append(number++).append(". ").append(c.getString(0));
            } while (c.moveToNext());
          }
        }
      if (result.length() > 0) {
        if (answer.length() > 0) answer.append("\n\n────────\n\n");
        answer.append(candidate).append("\n\n").append(result);
        if (++entries >= 3) break;
      }
    }
    return answer.toString();
  }
}
