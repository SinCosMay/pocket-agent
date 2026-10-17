package dev.pocket.agent;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class VoiceSttSettingsDeviceTest {
    @Test public void modeDefaultsToHybridAndPersistsSingleProviderChoices() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("voice_stt", Context.MODE_PRIVATE);
        Object previous = prefs.getAll().get("mode");
        try {
            prefs.edit().remove("mode").commit();
            assertEquals(VoiceSttSettings.Mode.HYBRID_THRESHOLD, VoiceSttSettings.mode(context));
            assertTrue(VoiceSttSettings.usesThreshold(context));

            VoiceSttSettings.setMode(context, VoiceSttSettings.Mode.ANDROID_DEFAULT_ONLY);
            assertEquals(VoiceSttSettings.Mode.ANDROID_DEFAULT_ONLY, VoiceSttSettings.mode(context));
            assertFalse(VoiceSttSettings.usesThreshold(context));

            VoiceSttSettings.setMode(context, VoiceSttSettings.Mode.DEEPGRAM_ONLY);
            assertEquals(VoiceSttSettings.Mode.DEEPGRAM_ONLY, VoiceSttSettings.mode(context));
            assertFalse(VoiceSttSettings.usesThreshold(context));
        } finally {
            SharedPreferences.Editor restore = prefs.edit().remove("mode");
            if (previous instanceof String) restore.putString("mode", (String) previous);
            restore.commit();
        }
    }

    @Test public void thresholdRemainsConfigurableForHybridMode() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("voice_stt", Context.MODE_PRIVATE);
        Object previousMode = prefs.getAll().get("mode");
        Object previousThreshold = prefs.getAll().get("short_utterance_ms");
        try {
            VoiceSttSettings.setMode(context, VoiceSttSettings.Mode.HYBRID_THRESHOLD);
            VoiceSttSettings.setShortUtteranceMs(context, 4250);
            assertTrue(VoiceSttSettings.usesThreshold(context));
            assertEquals(4250, VoiceSttSettings.shortUtteranceMs(context));
        } finally {
            SharedPreferences.Editor restore = prefs.edit()
                .remove("mode").remove("short_utterance_ms");
            if (previousMode instanceof String)
                restore.putString("mode", (String) previousMode);
            if (previousThreshold instanceof Integer)
                restore.putInt("short_utterance_ms", (Integer) previousThreshold);
            restore.commit();
        }
    }

    @Test public void unknownModeFallsBackToHybrid() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("voice_stt", Context.MODE_PRIVATE);
        Object previous = prefs.getAll().get("mode");
        try {
            prefs.edit().putString("mode", "future_mode").commit();
            assertEquals(VoiceSttSettings.Mode.HYBRID_THRESHOLD, VoiceSttSettings.mode(context));
            assertTrue(VoiceSttSettings.usesThreshold(context));
        } finally {
            SharedPreferences.Editor restore = prefs.edit().remove("mode");
            if (previous instanceof String) restore.putString("mode", (String) previous);
            restore.commit();
        }
    }
}
