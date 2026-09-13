package io.github.crash2recomp;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LauncherActivity extends Activity {
    private static final int PICK_DISC = 94154;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private ProgressBar progress;
    private Button selectDisc;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(48, 32, 48, 32);

        TextView title = new TextView(this);
        title.setText("Crash 2 Recompiled");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        content.addView(title, matchWrap());

        status = new TextView(this);
        status.setText("Select your legally owned SCUS-94154 disc dump.");
        status.setTextSize(17);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 24, 0, 24);
        content.addView(status, matchWrap());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        content.addView(progress, matchWrap());

        selectDisc = new Button(this);
        selectDisc.setText("Select disc…");
        selectDisc.setOnClickListener(view -> openDiscPicker());
        content.addView(selectDisc, wrapWrap());

        setContentView(content);
        runNativeProbe();
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void runNativeProbe() {
        worker.execute(() -> {
            try {
                NativeProbe.run(getApplicationInfo().nativeLibraryDir, getFilesDir().getPath());
            } catch (RuntimeException error) {
                runOnUiThread(() -> status.setText("Builder unavailable: " + error.getMessage()));
            }
        });
    }

    private void openDiscPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_DISC);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_DISC || resultCode != RESULT_OK || data == null) return;

        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        importDisc(uris);
    }

    private void importDisc(List<Uri> uris) {
        selectDisc.setEnabled(false);
        status.setText("Importing disc…");
        progress.setProgress(0);
        worker.execute(() -> {
            try {
                File root = new File(getExternalFilesDir(null), "disc");
                DiscSelection selection = DiscImporter.importUris(
                        getContentResolver(), uris, root,
                        (copied, total) -> {
                            int percent = total > 0 ? (int) Math.min(100, copied * 100 / total) : 0;
                            runOnUiThread(() -> progress.setProgress(percent));
                            return true;
                        });
                runOnUiThread(() -> {
                    String identity = selection.serial.isEmpty()
                            ? "CHD imported; identity will be checked during build."
                            : selection.serial + " verified.";
                    status.setText(identity);
                    selectDisc.setText("Replace disc…");
                    selectDisc.setEnabled(true);
                    progress.setProgress(100);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    status.setText("Import failed: " + error.getMessage());
                    selectDisc.setEnabled(true);
                    progress.setProgress(0);
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
