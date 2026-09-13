package io.github.crash2recomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class GameSettingsTest {
    @Test
    public void emptySettingsUseSafeAndroidDefaults() {
        GameSettings settings = GameSettings.from(new HashMap<>());

        assertEquals(2, settings.scale);
        assertEquals("4:3", settings.aspect);
        assertEquals(100, settings.volume);
        assertEquals(90, settings.audioLatencyMs);
        assertTrue(settings.vibration);
    }

    @Test
    public void invalidStoredValuesAreClampedOrReset() {
        Map<String, String> values = new HashMap<>();
        values.put("scale", "99");
        values.put("aspect", "wide-ish");
        values.put("volume", "-20");
        values.put("audio_latency_ms", "broken");
        values.put("vibration", "false");

        GameSettings settings = GameSettings.from(values);

        assertEquals(4, settings.scale);
        assertEquals("4:3", settings.aspect);
        assertEquals(0, settings.volume);
        assertEquals(90, settings.audioLatencyMs);
        assertFalse(settings.vibration);
    }

    @Test
    public void runtimeValuesUseExistingPsxEnvironmentNames() {
        Map<String, String> values = new HashMap<>();
        values.put("scale", "3");
        values.put("aspect", "16:9");
        values.put("volume", "55");
        values.put("audio_latency_ms", "120");

        Map<String, String> runtime = GameSettings.from(values).runtimeValues();

        assertEquals("3", runtime.get("PSX_SUPERSAMPLING"));
        assertEquals("16:9", runtime.get("PSX_ASPECT_RATIO"));
        assertEquals("55", runtime.get("PSX_VOLUME"));
        assertEquals("120", runtime.get("PSX_AUDIO_LATENCY_MS"));
    }
}
