package dev.pocket.agent;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android AudioRecord service lifecycle on an isolated emulator. */
@RunWith(AndroidJUnit4.class)
public final class JarvisWakeLifecycleDeviceTest {
    @Test public void foregroundWakeMicrophoneRunsWithoutStartingNodeAgent() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        boolean previous = WakeWordService.enabled(context);
        try {
            // Mimic Android's sticky service restoration; no external API key is needed.
            assertTrue(context.getSharedPreferences("wakeword",Context.MODE_PRIVATE).edit()
                .putBoolean("enabled",true).commit());
            context.startForegroundService(new Intent(context,WakeWordService.class));
            long deadline=System.currentTimeMillis()+15000;
            while(!WakeWordService.isListening() && System.currentTimeMillis()<deadline)
                Thread.sleep(100);
            assertTrue("Wake-word microphone did not start",WakeWordService.isListening());
            assertFalse("Node should remain dormant while waiting for wake phrase",AgentService.isRunning());
            Thread.sleep(1300);
            assertTrue("Idle wake-word microphone stopped unexpectedly",WakeWordService.isListening());
        } finally {
            WakeWordService.disableImmediately(context);
            context.stopService(new Intent(context,WakeWordService.class));
            context.getSharedPreferences("wakeword",Context.MODE_PRIVATE).edit()
                .putBoolean("enabled",previous).commit();
            activity.finish();
        }
    }
}
