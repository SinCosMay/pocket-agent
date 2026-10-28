package dev.pocket.agent;

import android.content.Context;
import android.os.PowerManager;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Offline end-to-end checks in a separate installation; never change the user's app state. */
public final class ScreenOffScheduleDeviceTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private void shell(String command) throws Exception {
        try(android.os.ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
            java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            input.readAllBytes();
        }
    }
    private void prepare() throws Exception {
        assertEquals("dev.pocket.agent.historyvalidation",context().getPackageName());
        new CredentialStore(context()).save(Json.obj("authMode","scripted","model","scripted","apiKey",""));
    }
    @Test public void activeTurnCompletesAfterPhysicalScreenTurnsOff() throws Exception {
        prepare();PowerManager power=context().getSystemService(PowerManager.class);boolean awake=power.isInteractive();
        RuntimeHost host=new RuntimeHost(context(),event->{});
        try(StateStore state=new StateStore(context())) {
            host.start().get(40,TimeUnit.SECONDS);
            assertTrue(host.dispatch(Json.obj("action","send","text","probe delay=6000")).get(10,TimeUnit.SECONDS).optBoolean("ok"));
            shell("input keyevent 223");Thread.sleep(300);assertFalse(power.isInteractive());
            long deadline=android.os.SystemClock.elapsedRealtime()+25000;
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                JSONObject saved=state.load();
                if(!saved.optBoolean("active")&&saved.optJSONArray("messages")!=null&&saved.getJSONArray("messages").toString().contains("Tool result:")) {
                    assertFalse("Task woke the physical display",power.isInteractive());return;
                }
                Thread.sleep(100);
            }
            fail("Active turn did not finish while screen was off");
        } finally {host.closeAsync().get(15,TimeUnit.SECONDS);if(awake)shell("input keyevent 224");}
    }
    @Test public void exactScheduleStartsRuntimeAndCompletesWithoutOpenApp() throws Exception {
        prepare();PowerManager power=context().getSystemService(PowerManager.class);boolean awake=power.isInteractive();
        shell("appops set "+context().getPackageName()+" SCHEDULE_EXACT_ALARM allow");
        assertTrue(AutonomyScheduler.exactAlarmsAllowed(context()));
        assertNull("Test needs no running runtime",RuntimeOwner.peekHost());
        AutonomyStore.persistEnabled(context(),true);
        try(AutonomyStore store=new AutonomyStore(context())) {
            JSONObject root=store.load();long revision=root.getLong("revision");long due=System.currentTimeMillis()+5000;
            root=Json.obj("version",1,"revision",revision+1,"schedules",Json.arr(),"wakeups",Json.arr(),"tasks",Json.arr(),"watches",Json.arr(),"skills",Json.arr(),"decisions",Json.arr(),"notifications",Json.arr(),"runs",Json.arr(),"seenEvents",Json.arr());
            root.getJSONArray("tasks").put(Json.obj("id","screen-off-task","title","Background probe","objective","Read phone capabilities delay=4000","status","active","maxRuns",1,"runCount",0,"approvedTools",Json.arr()));
            root.getJSONArray("schedules").put(Json.obj("id","screen-off-job","taskId","screen-off-task","enabled",true,"prompt","Read phone capabilities delay=4000","nextAt",due,"schedule",Json.obj("type","at","at",java.time.Instant.ofEpochMilli(due).toString())));
            store.save(root,revision);
            shell("input keyevent 223");Thread.sleep(300);assertFalse(power.isInteractive());
            long deadline=android.os.SystemClock.elapsedRealtime()+45000;
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                JSONObject saved=store.load();
                if(saved.getJSONArray("runs").length()>0) {
                    JSONObject run=saved.getJSONArray("runs").getJSONObject(0);
                    if("succeeded".equals(run.optString("status"))) {
                        assertTrue("Alarm delivery was substantially late",run.getLong("startedAt")<due+20000);
                        assertFalse("Scheduled job woke the physical display",power.isInteractive());
                        assertFalse(saved.getJSONArray("schedules").getJSONObject(0).getBoolean("enabled"));return;
                    }
                    assertFalse("Background run failed: "+run,"failed".equals(run.optString("status"))||"interrupted".equals(run.optString("status")));
                }
                Thread.sleep(100);
            }
            fail("Saved schedule did not run independently with the screen off: "+store.load());
        } finally {
            AutonomyStore.setEnabled(context(),false);
            if(awake)shell("input keyevent 224");
        }
    }
}
