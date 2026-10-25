package dev.pocket.agent;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Android SQLite and host-ownership coverage, without model inference or phone mutations. */
public final class AutonomyDeviceTest {
    @Test public void autonomyStorePersistsJobsAndDeduplicatesIncomingDecisions() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String database="autonomy-instrumentation.db";context.deleteDatabase(database);
        try {
            try(AutonomyStore store=new AutonomyStore(context,database)) {
                JSONObject root=store.load();assertEquals(0,root.getLong("revision"));
                root.getJSONArray("tasks").put(Json.obj("id","test-task","status","active"));
                root.getJSONArray("schedules").put(Json.obj("id","test-job","taskId","test-task","enabled",true,"nextAt",System.currentTimeMillis()+60000));
                root.put("revision",1);store.save(root,0);
                try{store.save(root,0);fail("Stale writers must be rejected");}catch(IllegalStateException expected){}
                JSONObject answer=Json.obj("id","decision:test-decision","type","decision","decisionId","test-decision","approved",true);
                store.incoming(answer);store.incoming(answer);assertEquals(1,store.events().length());
            }
            try(AutonomyStore reopened=new AutonomyStore(context,database)) {
                assertEquals("test-job",reopened.load().getJSONArray("schedules").getJSONObject(0).getString("id"));
                assertEquals(1,reopened.events().length());
                reopened.ack(new JSONArray().put("decision:test-decision"));assertEquals(0,reopened.events().length());
            }
        } finally {context.deleteDatabase(database);}
    }
    @Test public void pausedTasksDoNotArmTimersAndQueuedRunsWaitForActiveRun() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String database="autonomy-timer-instrumentation.db";context.deleteDatabase(database);
        boolean enabled=AutonomyStore.enabled(context);
        context.getSharedPreferences("autonomy-policy",0).edit().putBoolean("enabled",true).commit();
        try(AutonomyStore store=new AutonomyStore(context,database)) {
            JSONObject root=store.load();long now=System.currentTimeMillis();
            root.getJSONArray("tasks").put(Json.obj("id","task","status","paused"));
            root.getJSONArray("wakeups").put(Json.obj("id","wake","taskId","task","enabled",true,"nextAt",now));
            root.put("revision",1);store.save(root,0);assertEquals(Long.MAX_VALUE,store.nextDue());
            root.getJSONArray("tasks").getJSONObject(0).put("status","active");root.put("wakeups",new JSONArray());
            root.getJSONArray("runs").put(Json.obj("id","active","taskId","task","status","running","startedAt",now));
            root.getJSONArray("runs").put(Json.obj("id","queued","taskId","task","status","queued"));
            root.put("revision",2);store.save(root,1);assertEquals(now+9*60_000L,store.nextDue());
        } finally {
            context.deleteDatabase(database);
            context.getSharedPreferences("autonomy-policy",0).edit().putBoolean("enabled",enabled).commit();
            AutonomyScheduler.arm(context);
        }
    }
    @Test public void serviceAndBackgroundWorkShareOneRuntimeOwner() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        RuntimeOwner first=RuntimeOwner.acquire(context),second=RuntimeOwner.acquire(context);
        assertSame(first.host,second.host);
        try {
            first.ready.get(40,java.util.concurrent.TimeUnit.SECONDS);
            first.close();assertSame(second.host,RuntimeOwner.peekHost());
            assertTrue(second.host.dispatch(Json.obj("action","state")).get(10,java.util.concurrent.TimeUnit.SECONDS).getBoolean("ok"));
        } finally {second.close();}
    }
}
