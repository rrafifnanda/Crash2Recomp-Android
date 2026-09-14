package io.github.crash2recomp;

import java.util.LinkedHashMap;
import java.util.Map;

final class GameSettings {
    final int scale;
    final String aspect;
    final int volume;
    final int audioLatencyMs;
    final boolean vibration;

    private GameSettings(int scale, String aspect, int volume, int audioLatencyMs,
                         boolean vibration) {
        this.scale = scale;
        this.aspect = aspect;
        this.volume = volume;
        this.audioLatencyMs = audioLatencyMs;
        this.vibration = vibration;
    }

    static GameSettings from(Map<String, String> values) {
        return new GameSettings(
                clamp(number(values.get("scale"), 1), 1, 4),
                "16:9".equals(values.get("aspect")) ? "16:9" : "4:3",
                clamp(number(values.get("volume"), 100), 0, 100),
                clamp(number(values.get("audio_latency_ms"), 90), 45, 250),
                !"false".equals(values.get("vibration")));
    }

    Map<String, String> runtimeValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("PSX_SUPERSAMPLING", Integer.toString(scale));
        values.put("PSX_ASPECT_RATIO", aspect);
        values.put("PSX_VOLUME", Integer.toString(volume));
        values.put("PSX_AUDIO_LATENCY_MS", Integer.toString(audioLatencyMs));
        values.put("PSX_VIBRATION", vibration ? "1" : "0");
        return values;
    }

    private static int number(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
