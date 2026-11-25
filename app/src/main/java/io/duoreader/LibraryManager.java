package io.duoreader;

import android.content.SharedPreferences;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;

/** Library actions and destination navigation live outside the reader/editor UI. */
public final class LibraryManager {
  private final ReaderApp app;
  private final ThreadPoolExecutor worker = TaskExecutors.create("reader-library-writes", 1);
  private boolean working;

  LibraryManager(ReaderApp app) {
    this.app = app;
  }

  private ReaderUi ui() {
    return app.controls;
  }

  boolean isWorking() {
    return working;
  }

  public void createMenu() {
    ui().menu(
            "Create in " + app.folderLabel(),
            new String[] {"New collection", "New TXT file", "New Markdown file"},
            index -> {
              String parent = app.folder;
              ui().keyboard(
                      index == 0 ? "Collection name" : "Document name",
                      "",
                      "Create",
                      name ->
                          perform(
                              "Create",
                              () -> {
                                Library.Entry created =
                                    new LibraryStorage(app, app.root)
                                        .create(
                                            parent,
                                            name,
                                            index == 2
                                                ? DocumentFormat.MARKDOWN
                                                : DocumentFormat.TEXT,
                                            index == 0);
                                return () -> {
                                  selectOnReload(parent, created);
                                };
                              }));
            });
  }

  public void manage(Library.Entry entry) {
    if (entry == null) {
      createMenu();
      return;
    }
    String parent = app.folder;
    LinearLayout body = ui().column();
    ui().option(
            body,
            "Move to collection…",
            () -> destinations(entry, parent, app.root, new ArrayDeque<>()));
    ui().option(
            body,
            entry.folder ? "Change collection icon" : "Open",
            () -> {
              ui().dialog.dismiss();
              if (entry.folder) {
                app.selectEntry(entry);
                ui().collectionIcon();
              } else app.enter(entry);
            });
    ui().option(
            body,
            entry.folder ? "Delete collection…" : "Delete file…",
            () ->
                ui().menu(
                        entry.folder ? "Delete collection and all contents?" : "Delete this file?",
                        new String[] {
                          "Keep " + entry.name,
                          "Delete "
                              + entry.name
                              + (entry.folder ? " and everything inside" : " from storage")
                        },
                        choice -> {
                          if (choice == 1)
                            perform(
                                "Delete",
                                () -> {
                                  LibraryStorage storage = new LibraryStorage(app, app.root);
                                  Map<String, String> before = descendants(entry);
                                  app.editor.awaitWrites();
                                  storage.delete(parent, entry);
                                  return () -> {
                                    if (app.current != null
                                        && before.containsValue(app.current.uri))
                                      releaseCurrent(true);
                                    if (!app.closing && app.folder.equals(parent))
                                      app.loadFolder(parent);
                                  };
                                });
                        }));
    ui().showDialog(entry.name, body);
  }

  private void destinations(
      Library.Entry source, String originalParent, String destination, ArrayDeque<String> parents) {
    if (working) return;
    perform(
        "List destinations",
        () -> {
          ArrayList<Library.Entry> entries = Library.list(app, destination);
          return () -> {
            if (app.closing || ui() == null) return;
            LinearLayout body = ui().column();
            ui().option(
                    body,
                    "Move here",
                    () -> {
                      ui().dialog.dismiss();
                      String currentUri = app.current == null ? "" : app.current.uri;
                      perform(
                          "Move",
                          () -> {
                            LibraryStorage storage = new LibraryStorage(app, app.root);
                            Map<String, String> before = descendants(source);
                            app.editor.awaitWrites();
                            Library.Entry moved = storage.move(originalParent, source, destination);
                            Map<String, String> after = descendants(moved);
                            String relocatedCurrent = null;
                            SharedPreferences.Editor icons = app.collectionPrefs.edit();
                            for (Map.Entry<String, String> item : before.entrySet()) {
                              String relocated = after.get(item.getKey());
                              if (relocated == null) continue;
                              String oldUri = item.getValue();
                              app.stateStore.relocate(oldUri, relocated);
                              String icon =
                                  app.collectionPrefs.getString("icon." + Library.id(oldUri), null);
                              if (icon != null)
                                icons.putString("icon." + Library.id(relocated), icon);
                              File draft =
                                  new File(
                                      app.getFilesDir(), "draft-" + Library.id(oldUri) + ".json");
                              if (draft.isFile() && !oldUri.equals(relocated)) {
                                File target =
                                    new File(
                                        app.getFilesDir(),
                                        "draft-" + Library.id(relocated) + ".json");
                                if (!draft.renameTo(target))
                                  throw new IOException(
                                      "The document moved but its draft is still stored under its"
                                          + " previous location");
                              }
                              if (oldUri.equals(currentUri)) relocatedCurrent = relocated;
                            }
                            icons.commit();
                            String nextCurrent = relocatedCurrent;
                            return () -> {
                              if (nextCurrent != null
                                  && app.current != null
                                  && app.current.uri.equals(currentUri)) {
                                String lastName = app.current.name;
                                releaseCurrent(false);
                                app.prefs
                                    .edit()
                                    .putString("lastUri", nextCurrent)
                                    .putString("lastName", lastName)
                                    .putLong("lastModified", 0)
                                    .putLong("lastSize", 0)
                                    .commit();
                              }
                              if (!app.closing && app.folder.equals(originalParent))
                                app.loadFolder(originalParent);
                            };
                          });
                    });
            if (!parents.isEmpty())
              ui().option(
                      body,
                      "Parent collection",
                      () -> {
                        ArrayDeque<String> next = new ArrayDeque<>(parents);
                        String parent = next.removeLast();
                        destinations(source, originalParent, parent, next);
                      });
            for (Library.Entry entry : entries)
              if (entry.folder && !entry.uri.equals(source.uri))
                ui().option(
                        body,
                        entry.name + "  ›",
                        () -> {
                          if (parents.size() >= 64) {
                            ui().toast("Collection nesting limit reached");
                            return;
                          }
                          ArrayDeque<String> next = new ArrayDeque<>(parents);
                          next.addLast(destination);
                          destinations(source, originalParent, entry.uri, next);
                        });
            ui().showDialog("Choose destination", body);
          };
        });
  }

  private Map<String, String> descendants(Library.Entry root) throws Exception {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    result.put("", root.uri);
    ArrayDeque<Library.Entry> pending = new ArrayDeque<>();
    ArrayDeque<String> paths = new ArrayDeque<>();
    if (root.folder) {
      pending.add(root);
      paths.add("");
    }
    HashSet<String> seen = new HashSet<>();
    while (!pending.isEmpty()) {
      Library.Entry parent = pending.removeFirst();
      String prefix = paths.removeFirst();
      if (!seen.add(parent.uri)) throw new IOException("Cyclic collection structure");
      for (Library.Entry child : Library.list(app, parent.uri)) {
        String path = prefix + '\0' + child.name;
        result.put(path, child.uri);
        if (result.size() > 10000 || path.length() > 16384)
          throw new IOException("Manage this large collection using Android’s file manager");
        if (child.folder) {
          pending.addLast(child);
          paths.addLast(path);
        }
      }
    }
    return result;
  }

  private void releaseCurrent(boolean forget) {
    app.save();
    app.editor.clear();
    app.comics.clear();
    if (app.book != null) {
      try {
        app.book.close();
      } catch (IOException ignored) {
      }
      app.book = null;
    }
    app.current = null;
    app.topPage = app.bottomPage = null;
    app.mode = "library";
    if (forget)
      app.prefs.edit().remove("lastUri").remove("lastName").putBoolean("reading", false).commit();
  }

  private void selectOnReload(String parent, Library.Entry entry) {
    app.browserPrefs.edit().putString("selected." + Library.id(parent), entry.uri).commit();
    if (!app.closing && app.folder.equals(parent)) {
      app.selected = null;
      app.loadFolder(parent);
    }
  }

  private interface Operation {
    Runnable run() throws Exception;
  }

  private void perform(String name, Operation operation) {
    if (working || app.busy || app.loadingFolder) {
      ui().toast("Wait for the current library operation");
      return;
    }
    working = true;
    app.busy = true;
    app.save();
    app.libraryRepository.cancelRequests();
    app.changed();
    worker.execute(
        () -> {
          try {
            Runnable finished = operation.run();
            app.main.post(
                () -> {
                  working = false;
                  app.busy = false;
                  finished.run();
                  if (!app.closing) app.changed();
                });
          } catch (Exception error) {
            app.main.post(
                () -> {
                  working = false;
                  app.busy = false;
                  if (app.closing) return;
                  app.changed();
                  ui().message(
                          name + " failed",
                          error instanceof SecurityException
                              ? "Write access is missing. Choose the same folder again in"
                                  + " Preferences to grant access."
                              : error.getMessage() == null ? error.toString() : error.getMessage());
                });
          }
        });
  }
}
