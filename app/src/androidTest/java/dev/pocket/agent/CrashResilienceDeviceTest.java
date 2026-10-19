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
        String dumpsys="Display #0 (activities from top to bottom):\n"
            +"  * Task{abc #11 type=standard A=physical}\n"
            +"Display #7 (activities from top to bottom):\n"
            +"  * Task{def #41 type=standard A=worker}\n"
            +"  RootTask #42: type=standard\n";
        assertEquals(Set.of(41,42),ShellDisplayService.parseRootTasks(dumpsys,7));
        assertEquals(Set.of(11),ShellDisplayService.parseRootTasks(dumpsys,0));
    }


    @Test public void workerActivityFlagsExcludeTasksFromRecents() throws Exception {
        java.lang.reflect.Field field=ShellDisplayService.class.getDeclaredField("WORKER_ACTIVITY_FLAGS");
        field.setAccessible(true);
        int flags=Integer.decode((String)field.get(null));
        assertTrue((flags&android.content.Intent.FLAG_ACTIVITY_NEW_TASK)!=0);
        assertTrue((flags&android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)!=0);
        assertTrue((flags&android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)!=0);
    }

    @Test public void largeDurableBlobLoadsWithoutCursorWindowOverflow() throws Exception {
        Context c=context();
        try(StateStore store=new StateStore(c)) {
            org.json.JSONObject original=store.loadDurable();
            char[] chars=new char[4_000_000];java.util.Arrays.fill(chars,'x');
            org.json.JSONObject large=new org.json.JSONObject().put("payload",new String(chars));
            try {
                store.saveDurableBytes(large.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(4_000_000,store.loadDurable().getString("payload").length());
            } finally {store.saveDurableBytes(original.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }
    }

    @Test public void unlockedAccessibilityRecoveryDoesNotConsumeRetryBudget() {
        assertEquals(AccessibilityGuardianService.RepairDecision.DEFER,
            AccessibilityGuardianService.repairDecision(false,0));
        assertEquals(AccessibilityGuardianService.RepairDecision.DEFER,
            AccessibilityGuardianService.repairDecision(false,2));
        assertEquals(AccessibilityGuardianService.RepairDecision.START,
            AccessibilityGuardianService.repairDecision(true,0));
        assertEquals(AccessibilityGuardianService.RepairDecision.EXHAUSTED,
            AccessibilityGuardianService.repairDecision(true,3));
        assertEquals(0,AccessibilityGuardianService.repairAttemptsAfterResult(1,"deferred_unlocked"));
    }

    @Test public void lostAccessibilityMutationResponseIsResultUnknown() {
        assertEquals(dev.pocket.control.ControlException.Code.RESULT_UNKNOWN,
            AccessibilityProxyBackend.missingResultCode(true));
        assertEquals(dev.pocket.control.ControlException.Code.NOT_READY,
            AccessibilityProxyBackend.missingResultCode(false));
    }

    @Test public void unlockedAccessibilityRecoveryDoesNotConsumeRetryBudget() {
        int attempts=0;
        for(int i=0;i<3;i++) {
            assertEquals(AccessibilityGuardianService.RepairDecision.DEFER,
                AccessibilityGuardianService.repairDecision(false,attempts));
        }
        assertEquals(0,attempts);
        assertEquals(AccessibilityGuardianService.RepairDecision.START,
            AccessibilityGuardianService.repairDecision(true,attempts));
        assertEquals(1,AccessibilityGuardianService.repairAttemptsAfterResult(2,"deferred_unlocked"));
    }

    @Test public void missingMutationResponseRequiresStateObservationBeforeRetry() {
        assertEquals(dev.pocket.control.ControlException.Code.RESULT_UNKNOWN,
            AccessibilityProxyBackend.missingResultCode(true));
        assertEquals(dev.pocket.control.ControlException.Code.NOT_READY,
            AccessibilityProxyBackend.missingResultCode(false));
    }

    @Test public void accessibilityAndGuardianHaveIndependentProcesses() throws Exception {
        Context c=context();String pkg=c.getPackageName();
        ServiceInfo accessibility=c.getPackageManager().getServiceInfo(new ComponentName(c,AgentAccessibility.class),0);
        ServiceInfo guardian=c.getPackageManager().getServiceInfo(new ComponentName(c,AccessibilityGuardianService.class),0);
        ProviderInfo bridge=c.getPackageManager().resolveContentProvider(pkg+".accessibility-bridge",0);
        assertNotNull(bridge);
        assertEquals(pkg+":accessibility",accessibility.processName);
        assertEquals(pkg+":accessibility",bridge.processName);
        assertEquals(pkg+":guardian",guardian.processName);
    }
}
