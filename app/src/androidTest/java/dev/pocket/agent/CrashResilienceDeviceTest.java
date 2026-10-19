package dev.pocket.agent;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class CrashResilienceDeviceTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}

    @Test public void workerRootTaskParserNeverSelectsPhysicalDisplayTasks() {
        String sample="RootTask id=11 bounds=[0,0][1080,2400] displayId=0 userId=0\n"
            +"RootTask id=41 bounds=[0,0][720,1280] displayId=7 userId=0\n"
            +"Stack id=42 bounds=[0,0][720,1280] displayId=7 userId=0\n";
        assertEquals(Set.of(41,42),ShellDisplayService.parseRootTasks(sample,7));
        assertEquals(Set.of(11),ShellDisplayService.parseRootTasks(sample,0));
    }

    @Test public void accessibilityAndGuardianHaveIndependentProcesses() throws Exception {
        Context c=context();String pkg=c.getPackageName();
        ServiceInfo accessibility=c.getPackageManager().getServiceInfo(new ComponentName(c,AgentAccessibility.class),0);
        ServiceInfo guardian=c.getPackageManager().getServiceInfo(new ComponentName(c,AccessibilityGuardianService.class),0);
        ServiceInfo repair=c.getPackageManager().getServiceInfo(new ComponentName(c,AccessibilityRepairService.class),0);
        ProviderInfo bridge=c.getPackageManager().resolveContentProvider(pkg+".accessibility-bridge",0);
        assertNotNull(bridge);
        assertEquals(pkg+":accessibility",accessibility.processName);
        assertEquals(pkg+":accessibility",bridge.processName);
        assertEquals(pkg+":guardian",guardian.processName);
        assertEquals(pkg+":guardian",repair.processName);
    }
}
