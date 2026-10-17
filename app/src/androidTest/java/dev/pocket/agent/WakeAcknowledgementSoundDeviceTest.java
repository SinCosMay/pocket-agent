package dev.pocket.agent;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class WakeAcknowledgementSoundDeviceTest {
    @Test public void earconLoadsAndStartsOnAndroidAudioStack() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (WakeAcknowledgementSound sound = new WakeAcknowledgementSound(context)) {
            long deadline = System.currentTimeMillis() + 3000;
            while (!sound.isLoaded() && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertTrue("Wake acknowledgement sound did not load", sound.isLoaded());
            assertTrue("Wake acknowledgement sound did not start", sound.play());
            Thread.sleep(300);
        }
    }
}
