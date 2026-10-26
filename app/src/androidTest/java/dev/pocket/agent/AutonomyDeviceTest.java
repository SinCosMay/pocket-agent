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
                for(int i=0;i<63;i++)store.incoming(Json.obj("id","filler:"+i,"type","notification"));
                store.incoming(answer);assertEquals(64,store.events().length());
                try{store.incoming(Json.obj("id","overflow"));fail("Full inbox must reject new events");}catch(IllegalStateException expected){}
                JSONArray fillers=new JSONArray();for(int i=0;i<63;i++)fillers.put("filler:"+i);store.ack(fillers);
            }
            try(AutonomyStore reopened=new AutonomyStore(context,database)) {
                assertEquals("test-job",reopened.load().getJSONArray("schedules").getJSONObject(0).getString("id"));
                assertEquals(1,reopened.events().length());
                reopened.ack(new JSONArray().put("decision:test-decision"));assertEquals(0,reopened.events().length());
            }
        } finally {context.deleteDatabase(database);}
    }
    @Test public void pausedTaskDueCalculationAndQueuedRunsWaitForActiveRun() throws Exception {
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
        }
    }
    @Test public void startupFailuresBackOffPersistentlyAndPauseAfterThreeAttempts() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context isolated=new android.content.ContextWrapper(base) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(name+"-autonomy-test",mode);}
        };
        android.content.SharedPreferences policy=isolated.getSharedPreferences("autonomy-policy",0);policy.edit().clear().commit();
        try {
            AutonomyStore.startupFailed(isolated);
            assertTrue(AutonomyStore.enabled(isolated));assertEquals(1,policy.getInt("startupFailures",0));
            assertTrue(policy.getLong("backoffUntil",0)>System.currentTimeMillis());
            AutonomyStore.startupFailed(isolated);AutonomyStore.startupFailed(isolated);
            assertFalse(AutonomyStore.enabled(isolated));assertEquals(3,policy.getInt("startupFailures",0));
            AutonomyStore.persistEnabled(isolated,true);
            assertTrue(AutonomyStore.enabled(isolated));assertFalse(policy.contains("backoffUntil"));assertFalse(policy.contains("lastFailure"));
            AutonomyStore.startupFailed(isolated);AutonomyStore.startupHealthy(isolated);assertFalse(policy.contains("startupFailures"));
        } finally {policy.edit().clear().commit();}
    }
    @Test public void runtimeLeasesShareOneHost() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        RuntimeOwner first=RuntimeOwner.acquire(context),second=RuntimeOwner.acquire(context);
        assertSame(first.host,second.host);
        boolean firstClosed=false;
        try {
            first.ready.get(40,java.util.concurrent.TimeUnit.SECONDS);
            first.close();firstClosed=true;assertSame(second.host,RuntimeOwner.peekHost());
            assertTrue(second.host.dispatch(Json.obj("action","state")).get(10,java.util.concurrent.TimeUnit.SECONDS).getBoolean("ok"));
        } finally {if(!firstClosed)first.close();second.close();}
    }
    @Test public void expandedNotificationContentAndUnicodeExcerptsRemainReadable() {
        assertEquals("",PhoneNotifications.notificationText(null));
        android.os.Bundle extras=new android.os.Bundle();
        extras.putCharSequence(android.app.Notification.EXTRA_TEXT,"summary");
        extras.putCharSequence(android.app.Notification.EXTRA_BIG_TEXT,"expanded keyword");
        android.os.Bundle message=new android.os.Bundle();message.putCharSequence("text","message keyword");
        extras.putParcelableArray(android.app.Notification.EXTRA_MESSAGES,new android.os.Parcelable[]{message});
        String full=PhoneNotifications.notificationText(extras);
        assertTrue(full.contains("expanded keyword"));assertTrue(full.contains("message keyword"));
        String clipped=PhoneNotifications.clip("x".repeat(1999)+"\uD83D\uDE00"+"tail");
        assertEquals(1999,clipped.length());assertFalse(Character.isHighSurrogate(clipped.charAt(clipped.length()-1)));
    }
    @Test public void nativePersonalMigrationPreservesCustomPrefixAndStripsHistoricalDefaults() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(StateStore store=new StateStore(context);java.io.InputStream input=context.getAssets().open("legacy-main-prompts.json")) {
            JSONObject original=store.load();java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
            JSONArray defaults=new JSONArray(bytes.toString("UTF-8"));
            try {
                for(int i=0;i<defaults.length();i++){store.save(Json.obj("mainSystemPrompt",defaults.getString(i)));assertEquals("",store.load().getString("personalInstructions"));}
                String custom="You are the user's persistent phone assistant. Please use Hindi.";
                store.save(Json.obj("mainSystemPrompt",custom));assertEquals(custom,store.load().getString("personalInstructions"));
            } finally{store.save(original);}
        }
    }

}
