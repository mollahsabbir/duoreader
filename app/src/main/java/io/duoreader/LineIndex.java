package io.duoreader;

import java.util.Arrays;

/** Logical lines, independent of soft wraps. Edits scan only inserted characters. */
final class LineIndex {
  private int[] starts = {0};

  int count() {
    return starts.length;
  }

  int start(int line) {
    return starts[Math.max(0, Math.min(starts.length - 1, line))];
  }

  int line(int offset) {
    int index = Arrays.binarySearch(starts, offset);
    return index < 0 ? Math.max(0, -index - 2) : index;
  }

  void changed(CharSequence text, int start, int removed, int added) {
    int from = line(start) + 1, to = line(start + removed) + 1, inserted = 0;
    for (int i = start; i < start + added; i++) if (text.charAt(i) == '\n') inserted++;
    int[] next = new int[starts.length - (to - from) + inserted];
    System.arraycopy(starts, 0, next, 0, from);
    int cursor = from;
    for (int i = start; i < start + added; i++) if (text.charAt(i) == '\n') next[cursor++] = i + 1;
    for (int i = to; i < starts.length; i++) next[cursor++] = starts[i] + added - removed;
    starts = next;
  }
}
