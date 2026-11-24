package io.duoreader;

import android.content.SharedPreferences;
import android.view.KeyEvent;

/** Reading shortcuts only. Dialog and library navigation always keep their usual keys. */
public final class ReadingBindings {
  public enum Action {
    PREVIOUS("previous", "Previous spread", true),
    NEXT("next", "Next spread", true),
    FONT_UP("font_up", "Font size +", true),
    FONT_DOWN("font_down", "Font size −", true),
    COLORS("colors", "Cycle page colors", false),
    FONT("font", "Cycle typeface", false),
    CONTROLS("controls", "Show reading controls", false),
    MENU("menu", "Reading menu", false),
    BOOKMARK("bookmark", "Toggle smaller-page bookmark", false),
    CONTENTS("contents", "Contents", false),
    SEARCH("search", "Search book", false),
    NOTES("notes", "Bookmarks & notes", false),
    DICTIONARY("dictionary", "English dictionary", false),
    SELECT_PASSAGE("select_passage", "Select a passage", false),
    MATCH("match", "Match screen sizes", false),
    LIBRARY("library", "Return to library", false),
    BACK("back", "Back / close controls", false),
    NONE("none", "Do nothing", false);
    public final String id, label;
    public final boolean repeatable;

    Action(String id, String label, boolean repeatable) {
      this.id = id;
      this.label = label;
      this.repeatable = repeatable;
    }
  }

  public static final int[] KEYS = {
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_B,
    KeyEvent.KEYCODE_BUTTON_X,
    KeyEvent.KEYCODE_BUTTON_Y,
    KeyEvent.KEYCODE_BUTTON_L1,
    KeyEvent.KEYCODE_BUTTON_R1,
    KeyEvent.KEYCODE_BUTTON_SELECT
  };
  public static final String[] LABELS = {
    "D-pad Up", "D-pad Down", "D-pad Left", "D-pad Right", "A", "B", "X", "Y", "L1", "R1", "Select"
  };
  private static final Action[] DEFAULTS = {
    Action.FONT_UP,
    Action.FONT_DOWN,
    Action.PREVIOUS,
    Action.NEXT,
    Action.CONTROLS,
    Action.BACK,
    Action.MENU,
    Action.BOOKMARK,
    Action.PREVIOUS,
    Action.NEXT,
    Action.LIBRARY
  };
  private final SharedPreferences prefs;

  public ReadingBindings(SharedPreferences prefs) {
    this.prefs = prefs;
  }

  private static int index(int key) {
    for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == key) return i;
    return -1;
  }

  public boolean contains(int key) {
    return index(key) >= 0;
  }

  public Action get(int key) {
    int i = index(key);
    if (i < 0) return Action.NONE;
    String id = prefs.getString("binding." + key, DEFAULTS[i].id);
    for (Action action : Action.values()) if (action.id.equals(id)) return action;
    return DEFAULTS[i];
  }

  public boolean set(int key, Action action) {
    return contains(key) && prefs.edit().putString("binding." + key, action.id).commit();
  }

  public boolean reset() {
    SharedPreferences.Editor edit = prefs.edit();
    for (int key : KEYS) edit.remove("binding." + key);
    return edit.commit();
  }
}
