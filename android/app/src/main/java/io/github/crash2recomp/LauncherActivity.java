package io.github.crash2recomp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LauncherActivity extends Activity {
    private static final int PICK_DISC = 94154;
    private static final int IMPORT_CARD = 94155;
    private static final int EXPORT_CARD = 94156;
    private static final int EXPORT_LOG = 94157;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver buildReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            buildRunning = !intent.getBooleanExtra(BuildService.EXTRA_DONE, false);
            buildMessage = intent.getStringExtra(BuildService.EXTRA_MESSAGE);
            buildSuccess = intent.getBooleanExtra(BuildService.EXTRA_SUCCESS, false);
            refreshCurrentPage();
        }
    };

    private SharedPreferences preferences;
    private FrameLayout page;
    private String currentPage = "Setup";
    private boolean buildRunning;
    private boolean buildSuccess;
    private boolean deleteArmed;
    private long deleteArmedAt;
    private String buildMessage = "Import your disc, then build the game.";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("launcher", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff10141a);

        TextView title = text("Crash 2 Recompiled", 26);
        title.setTextColor(0xffff8a3d);
        title.setPadding(dp(20), dp(14), dp(20), dp(8));
        root.addView(title, matchWrap());

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER);
        for (String label : new String[] {"Setup", "Play", "Settings", "Log"}) {
            Button button = new Button(this);
            button.setText(label);
            button.setOnClickListener(view -> showPage(label));
            navigation.addView(button, new LinearLayout.LayoutParams(0, dp(52), 1));
        }
        root.addView(navigation, matchWrap());

        page = new FrameLayout(this);
        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        showPage(state == null ? "Setup" : state.getString("page", "Setup"));
        runNativeProbe();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(BuildService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(buildReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(buildReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        unregisterReceiver(buildReceiver);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("page", currentPage);
        super.onSaveInstanceState(state);
    }

    private void showPage(String name) {
        currentPage = name;
        page.removeAllViews();
        switch (name) {
            case "Play": page.addView(playPage()); break;
            case "Settings": page.addView(settingsPage()); break;
            case "Log": page.addView(logPage()); break;
            default: page.addView(setupPage()); break;
        }
    }

    private void refreshCurrentPage() {
        showPage(currentPage);
    }

    private View setupPage() {
        LinearLayout content = column();
        content.addView(heading("Setup"));
        String disc = preferences.getString("disc_path", "");
        String serial = preferences.getString("disc_serial", "");
        content.addView(body(disc.isEmpty()
                ? "Choose your legally owned Crash Bandicoot 2 USA disc. Select one CHD, or the CUE and every BIN track together."
                : (serial.isEmpty() ? "Disc imported. Its identity will be checked during Build."
                                    : serial + " is ready to build.")));
        if (buildMessage.startsWith("Disc import failed")) content.addView(body(buildMessage));
        Button select = action(disc.isEmpty() ? "Select disc…" : "Replace disc…", view -> openDiscPicker());
        content.addView(select);
        return scroll(content);
    }

    private View playPage() {
        LinearLayout content = column();
        content.addView(heading("Play"));
        String disc = preferences.getString("disc_path", "");
        boolean ready = isReady();
        String message = buildRunning ? buildMessage
                : ready ? "The Android build is ready."
                : disc.isEmpty() ? "Select your disc on Setup first." : buildMessage;
        content.addView(body(message));
        ProgressBar progress = new ProgressBar(this, null,
                buildRunning ? android.R.attr.progressBarStyleLarge : android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(buildRunning);
        content.addView(progress, matchWrap());

        Button build = action(ready ? "Rebuild game" : "Build game", view -> startBuild());
        build.setEnabled(!buildRunning && !disc.isEmpty());
        content.addView(build);
        if (buildRunning) content.addView(action("Cancel build", view -> cancelBuild()));
        Button playGame = action("Play", view -> startActivity(new Intent(this, Crash2SDLActivity.class)));
        playGame.setEnabled(ready && !buildRunning);
        content.addView(playGame);
        Button delete = action(deleteArmed ? "Tap again to confirm deletion" : "Delete built game…", view -> {
            long now = System.currentTimeMillis();
            if (deleteArmed && now - deleteArmedAt < 15000) {
                deleteArmed = false;
                deleteBuild();
            } else {
                deleteArmed = true;
                deleteArmedAt = now;
                showPage("Play");
            }
        });
        delete.setEnabled(!buildRunning && hasBuildFiles());
        content.addView(delete);
        return scroll(content);
    }

    private View settingsPage() {
        LinearLayout content = column();
        content.addView(heading("Settings"));
        content.addView(body("Settings apply the next time the game starts."));
        content.addView(cycleSetting("Resolution", "scale", new String[] {"1", "2", "3", "4"}, "1", "×"));
        content.addView(cycleSetting("Aspect", "aspect", new String[] {"4:3", "16:9"}, "4:3", ""));
        content.addView(cycleSetting("Volume", "volume",
                new String[] {"0", "25", "50", "75", "100"}, "100", "%"));
        content.addView(cycleSetting("Audio latency", "audio_latency_ms",
                new String[] {"45", "60", "90", "120", "180", "250"}, "90", " ms"));
        content.addView(cycleSetting("Vibration", "vibration",
                new String[] {"true", "false"}, "true", ""));
        content.addView(cycleSetting("Touch controls", "touch_controls",
                new String[] {"auto", "on", "off"}, "auto", ""));
        content.addView(action("Import memory card…", view -> openSingleDocument(IMPORT_CARD, "*/*")));
        content.addView(action("Export memory card…", view -> createDocument(EXPORT_CARD, "crash2-card.mcd")));
        return scroll(content);
    }

    private View logPage() {
        LinearLayout content = column();
        content.addView(heading("Log"));
        File log = buildLog();
        TextView output = body(readLog(log));
        output.setTextIsSelectable(true);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        content.addView(output, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        content.addView(action("Refresh log", view -> showPage("Log")));
        content.addView(action("Save log…", view -> createDocument(EXPORT_LOG, "crash2-build.log")));
        return content;
    }

    private Button cycleSetting(String label, String key, String[] values,
                                String fallback, String suffix) {
        String current = preferences.getString(key, fallback);
        Button button = new Button(this);
        button.setText(settingLabel(label, current, suffix));
        button.setContentDescription(label + ", current value " + current);
        button.setOnClickListener(view -> {
            String value = preferences.getString(key, fallback);
            int index = 0;
            for (int i = 0; i < values.length; i++) if (values[i].equals(value)) index = i;
            String next = values[(index + 1) % values.length];
            preferences.edit().putString(key, next).apply();
            button.setText(settingLabel(label, next, suffix));
            button.setContentDescription(label + ", current value " + next);
        });
        return button;
    }

    private static String settingLabel(String label, String value, String suffix) {
        if ("true".equals(value)) value = "On";
        if ("false".equals(value)) value = "Off";
        return label + ": " + value + suffix;
    }

    private void openDiscPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_DISC);
    }

    private void openSingleDocument(int request, String type) {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(type), request);
    }

    private void createDocument(int request, String name) {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, name), request);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == PICK_DISC) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            importDisc(uris);
        } else if (data.getData() != null) {
            if (requestCode == IMPORT_CARD) importCard(data.getData());
            if (requestCode == EXPORT_CARD) exportFile(memoryCard(), data.getData(), "No memory card exists yet.");
            if (requestCode == EXPORT_LOG) exportFile(buildLog(), data.getData(), "No build log exists yet.");
        }
    }

    private void importDisc(List<Uri> uris) {
        buildMessage = "Copying and checking the selected disc…";
        showPage("Setup");
        worker.execute(() -> {
            try {
                File external = getExternalFilesDir(null);
                if (external == null) throw new Exception("App storage is unavailable.");
                DiscSelection selection = DiscImporter.importUris(
                        getContentResolver(), uris, new File(external, "disc"), (copied, total) -> true);
                String previous = preferences.getString("disc_sha256", "");
                SharedPreferences.Editor edit = preferences.edit()
                        .putString("disc_path", selection.entryFile.toString())
                        .putString("disc_sha256", selection.sha256)
                        .putString("disc_serial", selection.serial);
                if (!selection.sha256.equals(previous)) edit.remove("built_disc_sha256");
                edit.apply();
                buildMessage = "Disc imported. Tap Build game on Play.";
                runOnUiThread(() -> showPage("Setup"));
            } catch (Exception error) {
                buildMessage = "Disc import failed: " + error.getMessage() + " Check the selected files and try again.";
                runOnUiThread(() -> showPage("Setup"));
            }
        });
    }

    private void startBuild() {
        Intent build = new Intent(this, BuildService.class).setAction(BuildService.ACTION_START)
                .putExtra(BuildService.EXTRA_DISC, preferences.getString("disc_path", ""))
                .putExtra(BuildService.EXTRA_DISC_SHA256, preferences.getString("disc_sha256", ""));
        buildRunning = true;
        buildMessage = "Starting the Android build…";
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(build); else startService(build);
        showPage("Play");
    }

    private void cancelBuild() {
        startService(new Intent(this, BuildService.class).setAction(BuildService.ACTION_CANCEL));
    }

    private boolean hasBuildFiles() {
        File external = getExternalFilesDir(null);
        if (external != null && new File(external, "build").exists()) return true;
        if (new File(getFilesDir(), "build").exists()) return true;
        return preferences.contains("built_disc_sha256");
    }

    private void deleteBuild() {
        buildMessage = "Deleting the built game…";
        showPage("Play");
        worker.execute(() -> {
            try {
                long freed = 0;
                File external = getExternalFilesDir(null);
                // Only the derived build trees go away. The imported disc,
                // memory cards, settings and staged toolchain are kept, so a
                // rebuild needs no re-import and no toolchain re-unpack.
                if (external != null) freed += deleteTree(new File(external, "build"));
                freed += deleteTree(new File(getFilesDir(), "build"));
                preferences.edit().remove("built_disc_sha256").apply();
                buildSuccess = false;
                buildMessage = "Built game deleted (" + formatBytes(freed) + " freed)."
                        + " Disc, saves and settings were kept.";
            } catch (Exception error) {
                buildMessage = "Delete failed: " + error.getMessage();
            }
            runOnUiThread(() -> showPage("Play"));
        });
    }

    private static long deleteTree(File root) {
        long freed = 0;
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) freed += deleteTree(child);
        }
        if (root.isFile()) freed += root.length();
        if (root.exists() && !root.delete()) freed -= root.isFile() ? root.length() : 0;
        return Math.max(0, freed);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) return String.format("%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1024 * 1024) return String.format("%.0f MB", bytes / 1048576.0);
        if (bytes >= 1024) return String.format("%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private boolean isReady() {
        String disc = preferences.getString("disc_sha256", "");
        return !disc.isEmpty()
                && disc.equals(preferences.getString("built_disc_sha256", ""))
                && new File(getFilesDir(), "build/output/libcrash2_game.so").isFile();
    }

    private void runNativeProbe() {
        worker.execute(() -> {
            try {
                NativeProbe.run(getApplicationInfo().nativeLibraryDir, getFilesDir().getPath());
            } catch (RuntimeException error) {
                buildMessage = "This device cannot run the bundled builder: " + error.getMessage();
            }
        });
    }

    private void importCard(Uri source) {
        worker.execute(() -> {
            try {
                File card = memoryCard();
                File parent = card.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new Exception("Cannot create the save folder.");
                File temporary = new File(parent, card.getName() + ".tmp");
                copyUri(source, temporary);
                if (temporary.length() != 128 * 1024) {
                    temporary.delete();
                    throw new Exception("A raw PlayStation memory card must be exactly 128 KiB.");
                }
                Files.move(temporary.toPath(), card.toPath(), StandardCopyOption.REPLACE_EXISTING);
                buildMessage = "Memory card imported.";
            } catch (Exception error) {
                buildMessage = "Memory card import failed: " + error.getMessage();
            }
            runOnUiThread(() -> showPage("Settings"));
        });
    }

    private void exportFile(File source, Uri destination, String missingMessage) {
        worker.execute(() -> {
            try {
                if (!source.isFile()) throw new Exception(missingMessage);
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new Exception("The selected destination cannot be opened.");
                    copy(input, output);
                }
                buildMessage = "File saved.";
            } catch (Exception error) {
                buildMessage = "Save failed: " + error.getMessage();
            }
            runOnUiThread(this::refreshCurrentPage);
        });
    }

    private void copyUri(Uri source, File destination) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new Exception("The selected file cannot be opened.");
            copy(input, output);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[1024 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
    }

    private File buildLog() {
        File external = getExternalFilesDir(null);
        return new File(external == null ? getFilesDir() : external, "build/build.log");
    }

    private File memoryCard() {
        File external = getExternalFilesDir(null);
        return new File(external == null ? getFilesDir() : external, "saves/card1.mcd");
    }

    private static String readLog(File log) {
        if (!log.isFile()) return "Build output will appear here.";
        try {
            byte[] bytes = Files.readAllBytes(log.toPath());
            int start = Math.max(0, bytes.length - 128 * 1024);
            return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
        } catch (Exception error) {
            return "The log cannot be read: " + error.getMessage();
        }
    }

    private LinearLayout column() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(18), dp(24), dp(24));
        return content;
    }

    private View scroll(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(child);
        return scroll;
    }

    private TextView heading(String value) {
        TextView view = text(value, 24);
        view.setTextColor(0xffffffff);
        view.setPadding(0, 0, 0, dp(12));
        return view;
    }

    private TextView body(String value) {
        TextView view = text(value, 16);
        view.setTextColor(0xffd7dde5);
        view.setPadding(0, 0, 0, dp(16));
        return view;
    }

    private TextView text(String value, float size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        return view;
    }

    private Button action(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setMinHeight(dp(48));
        button.setOnClickListener(listener);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
