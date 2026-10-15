package dev.pocket.agent;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoiceAssistantDeviceTest {
    @Test public void assistantIsProtectedAndDiscoverable() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager pm = context.getPackageManager();
        ServiceInfo entry = pm.getServiceInfo(new ComponentName(context, PocketVoiceInteractionService.class),
            PackageManager.GET_META_DATA);
        assertEquals("android.permission.BIND_VOICE_INTERACTION", entry.permission);
        assertTrue(entry.exported);
        assertNotNull(entry.metaData);
        assertTrue(entry.metaData.getInt("android.voice_interaction") != 0);
        ServiceInfo session = pm.getServiceInfo(new ComponentName(context, PocketVoiceSessionService.class), 0);
        assertEquals("android.permission.BIND_VOICE_INTERACTION", session.permission);
        assertTrue(session.exported);
    }
}
