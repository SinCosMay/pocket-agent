package dev.pocket.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class WakeWordSettingsDeviceTest {
    @Test public void persistedThresholdDefaultsTo75AndUpdatesWithoutModelRestart() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("wakeword", Context.MODE_PRIVATE);
        final boolean existed = prefs.contains("threshold_percent");
        final int previous = prefs.getInt("threshold_percent", WakeWordSettings.DEFAULT_PERCENT);
        try {
            assertTrue(prefs.edit().remove("threshold_percent").commit());
            assertEquals(75, WakeWordSettings.thresholdPercent(context));
            WakeWordSettings.setThresholdPercent(context, 2);
            assertEquals(2, WakeWordSettings.thresholdPercent(context));
            assertEquals(0.02f, WakeWordSettings.threshold(context), 0.0001f);
            WakeWordSettings.setThresholdPercent(context, 88);
            assertEquals(88, WakeWordSettings.thresholdPercent(context));
            for (int invalid : new int[]{0, 100, -30, 123}) {
                try { WakeWordSettings.setThresholdPercent(context, invalid); fail("Accepted " + invalid); }
                catch (IllegalArgumentException expected) { }
            }
        } finally {
            SharedPreferences.Editor restore = prefs.edit();
            if (existed) restore.putInt("threshold_percent", previous);
            else restore.remove("threshold_percent");
            assertTrue(restore.commit());
            WakeWordService.updateThreshold(WakeWordSettings.threshold(context));
        }
    }

    @Test public void microphoneSourceIsOptionalAndPersistent() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("wakeword", Context.MODE_PRIVATE);
        boolean existed = prefs.contains("audio_source");
        int previous = prefs.getInt("audio_source", 0);
        try {
            WakeWordSettings.setStandardMicrophone(context, true);
            assertTrue(WakeWordSettings.standardMicrophone(context));
            assertEquals(MediaRecorder.AudioSource.MIC, WakeWordSettings.audioSource(context));
            WakeWordSettings.setStandardMicrophone(context, false);
            assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWordSettings.audioSource(context));
        } finally {
            SharedPreferences.Editor restore = prefs.edit();
            if (existed) restore.putInt("audio_source", previous);
            else restore.remove("audio_source");
            assertTrue(restore.commit());
        }
    }
}
