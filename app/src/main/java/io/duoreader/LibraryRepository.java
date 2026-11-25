package io.duoreader;

import android.graphics.Bitmap;
import android.util.LruCache;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Visible/selected items only; bounded work queues and byte-budgeted bitmap caches. */
public final class LibraryRepository {
  private final ReaderApp app;
  private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>(32);
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          2, 2, 30, TimeUnit.SECONDS, queue, TaskExecutors.factory("reader-covers"));
  private final LinkedHashMap<String, Job> pending = new LinkedHashMap<>();
  private final LruCache<String, Metadata> metadata = new LruCache<>(256);
  private final BitmapCache thumbnails = new BitmapCache(6 * 1024 * 1024, false);
  private final BitmapCache heroes = new BitmapCache(4 * 1024 * 1024, true);
  private final Runnable publish;
  private boolean publishPending;

  public LibraryRepository(ReaderApp app) {
    this.app = app;
    executor.allowCoreThreadTimeOut(true);
    publish =
        () -> {
          publishPending = false;
          if (!app.closing) app.changed();
        };
  }

  private static String key(Library.Entry entry) {
    return entry.uri + "\n" + entry.modified + "\n" + entry.size;
  }

  public void request(Library.Entry entry, boolean selected) {
    if (entry.folder || app.closing || app.busy || !app.mode.equals("library")) return;
    String identity = key(entry);
    Metadata cached = metadata.get(identity);
    if (cached != null) cached.apply(entry);
    if (entry.loaded && cached == null && entry.error.isEmpty())
      metadata.put(identity, new Metadata(entry));
    Cover thumb = thumbnails.get(identity);
    if (thumb != null) thumb.attach(entry, false);
    if (!entry.loaded || entry.hasCover && entry.cover == null) schedule(entry, false, selected);
    if (selected && entry.loaded && entry.hasCover) {
      Cover hero = heroes.get(identity);
      if (hero != null) hero.attach(entry, true);
      else schedule(entry, true, true);
    }
  }

  private void schedule(Library.Entry entry, boolean hero, boolean priority) {
    String identity = (hero ? "hero:" : "meta:") + key(entry);
    Job existing = pending.get(identity);
    if (existing != null) {
      if (!existing.targets.contains(entry)) existing.targets.add(entry);
      if (priority && queue.remove(existing)) queue.offerFirst(existing);
      return;
    }
    if (pending.size() >= 24) {
      Job obsolete = null;
      for (Job job : pending.values())
        if (queue.contains(job)) {
          obsolete = job;
          break;
        }
      if (obsolete != null) {
        pending.remove(obsolete.identity);
        executor.remove(obsolete);
        obsolete.cancel(true);
      } else return;
    }
    Job job = new Job(identity, entry, hero, app.folderGeneration);
    pending.put(identity, job);
    executor.execute(job);
    if (priority && queue.remove(job)) queue.offerFirst(job);
  }

  public void cancelRequests() {
    for (Job job : new ArrayList<>(pending.values())) {
      executor.remove(job);
      job.cancel(true);
    }
    pending.clear();
    app.main.removeCallbacks(publish);
    publishPending = false;
  }

  public void trim() {
    thumbnails.evictAll();
    heroes.evictAll();
  }

  private final class Job extends FutureTask<Result> {
    final String identity;
    final int generation;
    final boolean hero;
    final ArrayList<Library.Entry> targets = new ArrayList<>();

    Job(String identity, Library.Entry entry, boolean hero, int generation) {
      super(() -> read(entry, hero));
      this.identity = identity;
      this.hero = hero;
      this.generation = generation;
      targets.add(entry);
    }

    @Override
    protected void done() {
      app.main.post(
          () -> {
            if (pending.get(identity) != this) return;
            pending.remove(identity);
            if (isCancelled() || generation != app.folderGeneration || app.closing) return;
            Result result;
            try {
              result = get();
            } catch (Exception error) {
              return;
            }
            String key = key(targets.get(0));
            if (result.metadata != null && result.error.isEmpty())
              metadata.put(key, result.metadata);
            if (!result.error.isEmpty()) metadata.remove(key);
            Cover cover = result.bitmap == null ? null : new Cover(result.bitmap);
            if (cover != null) (hero ? heroes : thumbnails).put(key, cover);
            for (Library.Entry entry : targets) {
              if (result.metadata != null) result.metadata.apply(entry);
              if (!hero) {
                entry.loaded = true;
                entry.error = result.error;
                if (!result.error.isEmpty()) entry.hasCover = false;
              }
              if (cover != null) cover.attach(entry, hero);
              if (!hero && app.selected != null && app.selected.uri.equals(entry.uri))
                request(entry, true);
            }
            if (!publishPending) {
              publishPending = true;
              app.main.postDelayed(publish, 16);
            }
          });
    }
  }

  private Result read(Library.Entry entry, boolean hero) {
    Result result = new Result();
    if (DocumentFormat.of(entry.name).comic()) {
      try {
        result.bitmap = ComicReader.cover(app, entry, hero ? 480 : 128);
        result.metadata = new Metadata(entry.title, "", "", result.bitmap != null);
      } catch (IOException error) {
        result.error = error.getMessage();
      }
      return result;
    }
    if (DocumentFormat.of(entry.name).editable()) {
      result.metadata = new Metadata(entry.title, "", "", false);
      return result;
    }
    try (Epub book = new Epub(Library.cached(app, entry.uri, entry.modified, entry.size))) {
      if (Thread.currentThread().isInterrupted()) return result;
      if (!hero)
        result.metadata =
            new Metadata(
                book.title.equals("Untitled") ? entry.title : book.title,
                book.author,
                book.description,
                !book.coverPath.isEmpty());
      result.bitmap = book.cover(hero ? 480 : 128);
      if (result.metadata != null && result.bitmap == null)
        result.metadata =
            new Metadata(
                book.title.equals("Untitled") ? entry.title : book.title,
                book.author,
                book.description,
                false);
    } catch (Exception error) {
      result.error = error.getMessage() == null ? "Cannot read this EPUB" : error.getMessage();
    }
    return result;
  }

  private static final class Result {
    Metadata metadata;
    Bitmap bitmap;
    String error = "";
  }

  private static final class Metadata {
    final String title, author, description;
    final boolean hasCover;

    Metadata(Library.Entry entry) {
      this(entry.title, entry.author, entry.description, entry.hasCover);
    }

    Metadata(String title, String author, String description, boolean hasCover) {
      this.title = title;
      this.author = author;
      this.description = description;
      this.hasCover = hasCover;
    }

    void apply(Library.Entry entry) {
      entry.title = title;
      entry.author = author;
      entry.description = description;
      entry.hasCover = hasCover;
      entry.loaded = true;
      entry.error = "";
    }
  }

  private static final class Cover {
    final Bitmap bitmap;
    final ArrayList<WeakReference<Library.Entry>> targets = new ArrayList<>();

    Cover(Bitmap bitmap) {
      this.bitmap = bitmap;
    }

    void attach(Library.Entry entry, boolean hero) {
      boolean known = false;
      for (Iterator<WeakReference<Library.Entry>> iterator = targets.iterator();
          iterator.hasNext(); ) {
        Library.Entry old = iterator.next().get();
        if (old == null) iterator.remove();
        else if (old == entry) known = true;
      }
      if (!known) targets.add(new WeakReference<>(entry));
      if (hero) entry.heroCover = bitmap;
      else entry.cover = bitmap;
    }
  }

  private static final class BitmapCache extends LruCache<String, Cover> {
    final boolean hero;

    BitmapCache(int bytes, boolean hero) {
      super(bytes);
      this.hero = hero;
    }

    @Override
    protected int sizeOf(String key, Cover cover) {
      return cover.bitmap.getAllocationByteCount();
    }

    @Override
    protected void entryRemoved(boolean evicted, String key, Cover old, Cover replacement) {
      for (WeakReference<Library.Entry> reference : old.targets) {
        Library.Entry entry = reference.get();
        if (entry == null) continue;
        if (hero && entry.heroCover == old.bitmap) entry.heroCover = null;
        if (!hero && entry.cover == old.bitmap) entry.cover = null;
      }
    }
  }
}
