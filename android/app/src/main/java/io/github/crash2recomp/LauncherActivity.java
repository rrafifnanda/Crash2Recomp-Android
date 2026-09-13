package io.github.crash2recomp;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        TextView status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setText("Checking Android builder support…");
        status.setTextSize(20);
        status.setPadding(32, 32, 32, 32);
        setContentView(status);

        Thread probe = new Thread(() -> {
            try {
                String result = NativeProbe.run(
                        getApplicationInfo().nativeLibraryDir,
                        getFilesDir().getPath());
                runOnUiThread(() -> status.setText(
                        result.startsWith("ok:")
                                ? "Android builder probe passed"
                                : result));
            } catch (RuntimeException error) {
                runOnUiThread(() -> status.setText("Probe failed: " + error.getMessage()));
            }
        }, "Crash2Probe");
        probe.start();
    }
}
