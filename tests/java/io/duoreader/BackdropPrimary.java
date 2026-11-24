package io.duoreader;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class BackdropPrimary extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    TextView text = new TextView(this);
    text.setText("Testing background resume");
    text.setGravity(17);
    setContentView(text);
  }
}
