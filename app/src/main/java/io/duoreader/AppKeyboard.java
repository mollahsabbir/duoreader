package io.duoreader;

import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import java.util.*;

/** One adaptive keypad for search, notes, and the document editor. */
public final class AppKeyboard extends LinearLayout {
  public interface Target {
    void insert(String text);

    void erase();
  }

  private final Target target;
  private final boolean multiline;
  private boolean caps;
  private int symbolSet;
  private final ArrayList<Button> letters = new ArrayList<>(), punctuation = new ArrayList<>();

  public AppKeyboard(ReaderUi ui, Target target, boolean multiline) {
    super(ui.activity);
    this.target = target;
    this.multiline = multiline;
    setOrientation(VERTICAL);
    for (String chars : new String[] {"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm.,?"}) {
      LinearLayout row = ui.row();
      for (char ch : chars.toCharArray()) {
        Button key = ui.button(String.valueOf(ch), () -> {});
        key.setOnClickListener(v -> target.insert(key.getText().toString()));
        letters.add(key);
        addKey(ui, row, key);
      }
      addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    LinearLayout extra = ui.row();
    for (char ch : "'\"!-:;()*/".toCharArray()) {
      Button key = ui.button(String.valueOf(ch), () -> {});
      key.setOnClickListener(v -> target.insert(key.getText().toString()));
      punctuation.add(key);
      addKey(ui, extra, key);
    }
    addView(extra, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout actions = ui.row();
    addKey(
        ui,
        actions,
        ui.button(
            "Shift",
            () -> {
              caps = !caps;
              for (Button key : letters) {
                String label = key.getText().toString();
                key.setText(caps ? label.toUpperCase(Locale.ROOT) : label.toLowerCase(Locale.ROOT));
              }
            }));
    addKey(
        ui,
        actions,
        ui.button(
            "#+=",
            () -> {
              symbolSet = (symbolSet + 1) % 3;
              String chars = new String[] {"'\"!-:;()*/", "#_[]{}<>`=", "~|+\\%@&$^:"}[symbolSet];
              for (int i = 0; i < punctuation.size(); i++)
                punctuation.get(i).setText(chars.substring(i, i + 1));
            }));
    addKey(ui, actions, ui.button("Space", () -> target.insert(" ")));
    if (multiline) addKey(ui, actions, ui.button("Enter", () -> target.insert("\n")));
    Button delete = ui.button("⌫", target::erase);
    delete.setContentDescription("Backspace");
    addKey(ui, actions, delete);
    addView(actions, new LinearLayout.LayoutParams(-1, 0, 1));
  }

  private void addKey(ReaderUi ui, LinearLayout row, Button key) {
    key.setPadding(0, 0, 0, 0);
    key.setMinHeight(0);
    key.setMinimumHeight(0);
    key.setTextSize(12);
    LayoutParams params = new LayoutParams(0, -1, 1);
    params.setMargins(ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1));
    row.addView(key, params);
  }

  public boolean handle(KeyEvent event) {
    int key = event.getKeyCode();
    boolean space = key == KeyEvent.KEYCODE_BUTTON_Y || key == KeyEvent.KEYCODE_SPACE;
    boolean erase = key == KeyEvent.KEYCODE_BUTTON_L1 || key == KeyEvent.KEYCODE_DEL;
    boolean enter =
        multiline && (key == KeyEvent.KEYCODE_BUTTON_R1 || key == KeyEvent.KEYCODE_ENTER);
    int unicode = event.getUnicodeChar();
    boolean character =
        unicode >= 32 && unicode != 127 && !event.isCtrlPressed() && !event.isAltPressed();
    if (!(space || erase || enter || character)) return false;
    if (event.getAction() == KeyEvent.ACTION_DOWN) {
      if (erase) target.erase();
      else if (space) target.insert(" ");
      else if (enter) target.insert("\n");
      else target.insert(new String(Character.toChars(unicode)));
    }
    return true;
  }

  public static AppKeyboard forText(
      ReaderUi ui, NumberedText field, boolean multiline, int maxLength) {
    field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(maxLength)});
    return new AppKeyboard(
        ui,
        new Target() {
          public void insert(String text) {
            field.insert(text);
          }

          public void erase() {
            field.erase();
          }
        },
        multiline);
  }
}
