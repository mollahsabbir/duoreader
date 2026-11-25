package io.duoreader;

import android.text.Layout;
import java.util.concurrent.atomic.AtomicReference;

public final class AlignmentTests extends FeatureTest {
  void exercise() throws Exception {
    Epub original = app.book;
    int alignment = app.alignment;
    try (Epub fixture = new Epub(TestBooks.create(getTargetContext()))) {
      ui(() -> app.book = fixture);
      ReaderApp.Page previous = null;
      for (int choice = 0; choice < 3; choice++) {
        final int value = choice;
        ui(
            () -> {
              app.alignment = value;
              app.appearance();
            });
        AtomicReference<ReaderApp.Page> page = new AtomicReference<>();
        ui(
            () -> {
              try {
                page.set(app.page(new ReaderApp.Location(0, 0), 600, 900));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
        check(
            "Alignment " + choice + " reaches layout",
            page.get().layout.getAlignment()
                == (choice == 2 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL));
        check("Alignment " + choice + " invalidates cached pages", previous != page.get());
        check("Alignment " + choice + " persists", app.prefs.getInt("alignment", -1) == choice);
        previous = page.get();
      }
    } finally {
      ui(
          () -> {
            app.book = original;
            app.alignment = alignment;
            app.appearance();
          });
    }
  }
}
