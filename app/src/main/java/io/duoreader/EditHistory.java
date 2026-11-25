package io.duoreader;

import android.text.Editable;
import java.util.ArrayDeque;

/** Bounded edit deltas instead of keeping whole document copies for undo. */
final class EditHistory {
  private static final int MAX_BYTES = 512 * 1024;

  private static final class Change {
    final int start;
    final String before, after;

    Change(int start, String before, String after) {
      this.start = start;
      this.before = before;
      this.after = after;
    }

    int bytes() {
      return (before.length() + after.length()) * 2 + 48;
    }
  }

  private final ArrayDeque<Change> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private int bytes;

  void clear() {
    undo.clear();
    redo.clear();
    bytes = 0;
  }

  void record(int start, String before, String after) {
    redo.clear();
    Change change = new Change(start, before, after);
    if (change.bytes() > MAX_BYTES) {
      clear();
      return;
    }
    undo.addLast(change);
    bytes += change.bytes();
    while (undo.size() > 128 || bytes > MAX_BYTES) bytes -= undo.removeFirst().bytes();
  }

  int apply(Editable text, boolean reverse) {
    ArrayDeque<Change> from = reverse ? undo : redo, to = reverse ? redo : undo;
    if (from.isEmpty()) return -1;
    Change change = from.removeLast();
    String removed = reverse ? change.after : change.before;
    String inserted = reverse ? change.before : change.after;
    text.replace(change.start, change.start + removed.length(), inserted);
    to.addLast(change);
    if (reverse) bytes -= change.bytes();
    else bytes += change.bytes();
    return change.start + inserted.length();
  }
}
