package io.duoreader;

import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

public final class NoteTests extends FeatureTest {
  View find(View root, String title) {
    if (root instanceof TextView && ((TextView) root).getText().toString().equals(title))
      return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View found = find(((ViewGroup) root).getChildAt(i), title);
        if (found != null) return found;
      }
    return null;
  }

  NumberedText field(View root) {
    if (root instanceof NumberedText) return (NumberedText) root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        NumberedText found = field(((ViewGroup) root).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  void exercise() throws Exception {
    LineIndex index = new LineIndex();
    StringBuilder text = new StringBuilder();
    Random random = new Random(1);
    for (int edit = 0; edit < 500; edit++) {
      int start = random.nextInt(text.length() + 1),
          removed = random.nextInt(text.length() - start + 1);
      String inserted = new String[] {"abc", "\n", "x\ny", "", "\n\n"}[random.nextInt(5)];
      text.replace(start, start + removed, inserted);
      index.changed(text, start, removed, inserted.length());
      int logical = 0;
      for (int offset = 0; offset <= text.length(); offset++) {
        if (offset > 0 && text.charAt(offset - 1) == '\n') logical++;
        if (index.line(offset) != logical) throw new AssertionError("Line index edit " + edit);
      }
    }
    check("Logical line index survives 500 random edits", true);
    AtomicReference<String> saved = new AtomicReference<>();
    ui(() -> app.controls.keyboard("Test note", "First", "Save note", true, saved::set));
    ui(
        () -> {
          NumberedText input = field(app.controls.dialogSurface);
          input.insert("\nSecond 😀");
          input.erase();
          find(app.controls.dialogSurface, "Save note").performClick();
        });
    check(
        "Note entry preserves newlines and deletes whole Unicode characters",
        "First\nSecond ".equals(saved.get()));
    JSONArray original = app.marks;
    int rotation = app.rotation;
    try {
      JSONObject note =
          new JSONObject()
              .put("quote", "A short selected passage")
              .put("kind", "note")
              .put(
                  "note",
                  "Line one\n" + new String(new char[200]).replace('\0', 'w') + "\nLast line");
      ui(
          () -> {
            app.marks = new JSONArray().put(note);
            app.controls.annotation(0);
          });
      check(
          "Note detail omits dictionary",
          find(app.controls.dialogSurface, "English dictionary") == null);
      check("Note is the main numbered text area", field(app.controls.dialogSurface) != null);
      check(
          "Edit and delete share vector icons",
          ((TextView) find(app.controls.dialogSurface, "Edit note")).getCompoundDrawables()[0]
                  != null
              && ((TextView) find(app.controls.dialogSurface, "Delete")).getCompoundDrawables()[0]
                  != null);
      ui(() -> app.controls.dialog.dismiss());
      for (int angle : new int[] {0, 90, 180, 270}) {
        ui(
            () -> {
              app.rotation = angle;
              app.appearance();
              app.controls.keyboard("Keyboard", "", "Save", true, value -> {});
            });
        until(() -> field(app.controls.dialogSurface).getHeight() > 0, "keyboard layout");
        View enter = find(app.controls.dialogSurface, "Enter");
        check(
            "Keyboard fits rotation " + angle,
            enter != null
                && enter.getHeight() >= app.controls.dp(20)
                && field(app.controls.dialogSurface).getHeight() >= app.controls.dp(20));
        ui(() -> app.controls.dialog.dismiss());
      }
    } finally {
      ui(
          () -> {
            if (app.controls.dialog != null) app.controls.dialog.dismiss();
            app.marks = original;
            app.rotation = rotation;
            app.appearance();
          });
    }
  }
}
