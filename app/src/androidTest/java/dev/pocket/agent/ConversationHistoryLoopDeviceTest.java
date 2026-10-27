package dev.pocket.agent;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses the real ARM64 Node/Pi/native bridge, with a deterministic offline model. */
@RunWith(AndroidJUnit4.class)
public class ConversationHistoryLoopDeviceTest {
    private JSONObject until(StateStore state,int turns,String contains) throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+20000;
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            JSONObject current=state.load();
            if(current.optInt("mainTurns")>=turns&&!current.optBoolean("active")&&
                current.optJSONArray("messages")!=null&&current.getJSONArray("messages").toString().contains(contains))return current;
            Thread.sleep(60);
        }
        throw new AssertionError("History tool loop timed out: "+state.load().optString("lastError"));
    }
    private void command(RuntimeHost host,String action,String text) throws Exception {
        JSONObject request=new JSONObject().put("action",action);if(text!=null)request.put("text",text);
        JSONObject response=host.dispatch(request).get(10,TimeUnit.SECONDS);
        assertTrue(response.toString(),response.optBoolean("ok"));
    }
    @Test public void realPiSearchAndReadRecoverOldThreadAfterRuntimeRestart() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Never replace credentials or reset conversations in an existing user installation.
        assertEquals("Use the isolated validation APK", "dev.pocket.agent.historyvalidation",context.getPackageName());
        new CredentialStore(context).save(new JSONObject().put("authMode","scripted").put("model","scripted").put("apiKey",""));
        String marker="basaltarchive"+System.currentTimeMillis();
        String content=marker+" warranty receipt is in the blue folder.";
        RuntimeHost host=new RuntimeHost(context,event->{});
        try(StateStore state=new StateStore(context)) {
            try {
                host.start().get(30,TimeUnit.SECONDS);
                JSONObject gate=host.runCompatibilityGate().get(30,TimeUnit.SECONDS);
                assertTrue(gate.toString(),gate.optBoolean("ok"));
                command(host,"new_main",null);
                command(host,"send",content);
                String oldThread=until(state,1,content).getString("mainThreadId");
                command(host,"new_main",null);
                assertNotEquals(oldThread,state.load().getString("mainThreadId"));
                assertEquals(0,state.load().getJSONArray("messages").length());
                JSONArray hits=state.searchHistory(marker,10);
                assertTrue("Old thread was not archived",hits.length()>=2);
                long id=-1;
                for(int i=0;i<hits.length();i++)if("user".equals(hits.getJSONObject(i).getString("role")))id=hits.getJSONObject(i).getLong("id");
                assertTrue(id>0);
                command(host,"send","history fixture search "+marker);
                until(state,1,"Tool result:");
                JSONArray searchMessages=state.loadDurable().getJSONArray("mainMessages");
                assertTrue(searchMessages.toString().contains("history_search"));
                assertTrue(searchMessages.toString().contains(content));
                command(host,"send","history fixture read "+id);
                until(state,2,content);
                assertTrue(state.loadDurable().getJSONArray("mainMessages").toString().contains("history_read"));
                host.close();
                // Joining the owner thread through its persisted final checkpoint avoids two Node owners.
                long end=android.os.SystemClock.elapsedRealtime()+10000;
                while(android.os.SystemClock.elapsedRealtime()<end&&isNodeThreadAlive())Thread.sleep(50);
                assertFalse("Old Node owner did not close",isNodeThreadAlive());
                host=new RuntimeHost(context,event->{});host.start().get(30,TimeUnit.SECONDS);
                assertEquals(content,state.readHistory(id,"main").getString("text"));
                command(host,"send","history fixture search "+marker);
                until(state,3,"Tool result:");
                System.out.println("POCKET_HISTORY_E2E="+new JSONObject().put("verified",true)
                    .put("marker",marker).put("messageId",id).put("oldThread",oldThread)
                    .put("nodeGate",gate).put("searchAndReadTools",true).put("runtimeRestart",true));
            } finally {host.close();}
        }
    }
    private boolean isNodeThreadAlive() {
        for(Thread thread:Thread.getAllStackTraces().keySet())if(thread.isAlive()&&"PocketNode".equals(thread.getName()))return true;
        return false;
    }
}
