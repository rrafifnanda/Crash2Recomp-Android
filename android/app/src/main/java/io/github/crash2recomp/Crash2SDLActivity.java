package io.github.crash2recomp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.libsdl.app.SDLActivity;

public final class Crash2SDLActivity extends SDLActivity {
    static native void nativeSetGamepad(int activeLowWord);
    static native void nativeSetSticks(int lx, int ly, int rx, int ry);

    private TouchPadOverlay touchPad;

    @Override
    protected void onCreate(Bundle state) {
        File module = new File(getFilesDir(), "build/output/libcrash2_game.so");
        File external = getExternalFilesDir(null);
        File project = external == null ? null : new File(external, "build/project");
        if (!module.isFile() || project == null || !new File(project, "game.toml").isFile()) {
            startActivity(new Intent(this, LauncherActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            return;
        }

        setEnvironment("PSX_GAME_MODULE", module.getPath());
        setEnvironment("PSX_EXTERNAL_FILES", external.getPath());
        for (Map.Entry<String, String> value : settings().runtimeValues().entrySet()) {
            setEnvironment(value.getKey(), value.getValue());
        }
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        attachTouchPad();
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {"main"};
    }

    @Override
    protected String[] getArguments() {
        File external = getExternalFilesDir(null);
        File project = new File(external, "build/project");
        SharedPreferences preferences = getSharedPreferences("launcher", MODE_PRIVATE);
        String disc = preferences.getString("disc_path", "");
        File saves = new File(external, "saves");
        saves.mkdirs();
        return new String[] {
                "--no-launcher", "--game", new File(project, "game.toml").getPath(),
                "--renderer", "opengl", "--disc", disc,
                "--memcard-dir", saves.getPath()};
    }

    private GameSettings settings() {
        SharedPreferences preferences = getSharedPreferences("launcher", MODE_PRIVATE);
        Map<String, String> values = new HashMap<>();
        for (String key : new String[] {"scale", "aspect", "volume", "audio_latency_ms", "vibration"}) {
            values.put(key, preferences.getString(key, null));
        }
        return GameSettings.from(values);
    }

    private static void setEnvironment(String name, String value) {
        try {
            android.system.Os.setenv(name, value, true);
        } catch (android.system.ErrnoException error) {
            throw new IllegalStateException("Cannot configure " + name, error);
        }
    }

    private void attachTouchPad() {
        String mode = getSharedPreferences("launcher", MODE_PRIVATE)
                .getString("touch_controls", "auto");
        if ("off".equals(mode)) return;
        if (mLayout == null) return;
        touchPad = new TouchPadOverlay(this);
        touchPad.setOnChange(() -> {
            nativeSetGamepad(~touchPad.bits() & 0xffff);
            int axes = touchPad.axes();
            nativeSetSticks(axes & 0xff, (axes >> 8) & 0xff, 0x80, 0x80);
        });
        touchPad.applyMode("on".equals(mode) ? TouchPadOverlay.Mode.ON : TouchPadOverlay.Mode.AUTO,
                hasPhysicalController());
        mLayout.addView(touchPad, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @SuppressWarnings("deprecation")
    public void onNativeRumble(int small, int large) {
        runOnUiThread(() -> {
            Vibrator vibrator = getSystemService(Vibrator.class);
            if (vibrator == null || !settings().vibration) return;
            int amplitude = Math.max(small == 0 ? 0 : 96, large);
            if (amplitude == 0) {
                vibrator.cancel();
            } else if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(150, amplitude));
            } else {
                vibrator.vibrate(150);
            }
        });
    }

    public void onNativeHaptic(int milliseconds, int unused) {
        runOnUiThread(() -> getWindow().getDecorView().performHapticFeedback(
                android.view.HapticFeedbackConstants.KEYBOARD_TAP));
    }

    private boolean hasPhysicalController() {
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice device = android.view.InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) continue;
            int sources = device.getSources();
            if ((sources & android.view.InputDevice.SOURCE_GAMEPAD) != 0
                    || (sources & android.view.InputDevice.SOURCE_JOYSTICK) != 0) return true;
        }
        return false;
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
