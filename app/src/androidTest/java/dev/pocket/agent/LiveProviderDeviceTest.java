package dev.pocket.agent;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Explicit opt-in real inference, provisioned locally after installing the exact APK. */
@RunWith(AndroidJUnit4.class)
public class LiveProviderDeviceTest {
    private Context context() {return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private RuntimeHost host() throws Exception {
        Context c=context();
        c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(500);
        c.startForegroundService(new Intent(c,AgentService.class).setAction(AgentService.ACTION_START));
        long deadline=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<deadline) {
            RuntimeHost h=AgentService.peekHost();
            if(h!=null) {try {h.memoryStats().get(2,TimeUnit.SECONDS);return h;}catch(Exception startup) {}}
            Thread.sleep(100);
        }
        throw new AssertionError("Runtime host did not start");
    }
    private JSONObject command(RuntimeHost h, String action) throws Exception {
        JSONObject result=h.dispatch(new JSONObject().put("action",action)).get(5,TimeUnit.SECONDS);
        assertTrue("Runtime command rejected: "+action,result.optBoolean("ok"));return result;
    }
    private boolean activeTask(JSONObject task) {
        String status=task.optString("status");
        return status.equals("running")||status.equals("queued")||status.equals("waiting")||status.equals("steering");
    }
    private JSONObject findTask(JSONObject state,Set<String> old) {
        JSONArray tasks=state.optJSONArray("tasks");
        if(tasks!=null)for(int i=0;i<tasks.length();i++) {
            JSONObject task=tasks.optJSONObject(i);
            if(task!=null&&!old.contains(task.optString("conversationId")))return task;
        }
        return null;
    }
    private boolean answeredFour(JSONObject state,String question) {
        JSONArray messages=state.optJSONArray("messages");boolean found=false;
        if(messages==null)return false;
        for(int i=0;i<messages.length();i++) {
            JSONObject m=messages.optJSONObject(i);if(m==null)continue;
            if(m.optString("role").equals("user")&&m.optString("text").equals(question))found=true;
            else if(found&&m.optString("role").equals("assistant")&&m.optString("text").matches("(?is).*(\\b4\\b|\\bfour\\b).*"))return true;
        }
        return false;
    }
    private boolean observedFixture(JSONArray messages) {
        if(messages==null)return false;
        for(int i=0;i<messages.length();i++) {
            JSONObject message=messages.optJSONObject(i);
            if(message!=null&&message.optString("role").equals("toolResult")&&message.optString("toolName").equals("phone_observe")&&!message.optBoolean("isError")&&message.toString().contains("STATUS_CLICKED:LiveCodex COUNT:1"))return true;
        }
        return false;
    }
    @Test public void liveCodexDelegationAndFixture() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        String configPath=InstrumentationRegistry.getArguments().getString("providerConfigPath");
        Assume.assumeTrue("Opt-in local provider configuration required",configPath!=null&&!configPath.isEmpty());
        Context c=context();File provisioned=new File(configPath).getCanonicalFile();
        File allowed=c.getExternalFilesDir(null).getCanonicalFile();
        assertEquals("Provider file must be directly inside this app's external files directory",allowed,provisioned.getParentFile());
        assertTrue("Provider file is missing or too large",provisioned.isFile()&&provisioned.length()<=16384);
        CredentialStore credentials=new CredentialStore(c);RuntimeHost h=null;
        long deadline=SystemClock.elapsedRealtime()+165000;String authMode="";boolean direct=false;
        try(StateStore store=new StateStore(c)) {
            try {
                JSONObject settings;
                try {settings=new JSONObject(new String(Files.readAllBytes(provisioned.toPath()),StandardCharsets.UTF_8));}
                catch(org.json.JSONException invalid) {throw new AssertionError("Invalid provider configuration JSON");}
                authMode=settings.optString("authMode");direct=authMode.equals("codex-account");
                assertTrue("This test requires an explicit direct account or legacy emulator relay configuration",direct||authMode.equals("codex")||authMode.equals("codex-relay")||authMode.equals("codexRelay"));
                assertEquals("Live validation must use Luna","gpt-6-luna",settings.optString("model"));
                if(direct) {
                    assertTrue("Direct account mode must not carry a provider endpoint",!settings.has("endpoint")||settings.optString("endpoint").isEmpty());
                    assertTrue("Direct account access token is missing",settings.optString("accessToken").length()>0);
                    assertTrue("Direct account refresh token is missing",settings.optString("refreshToken").length()>0);
                    assertTrue("Direct account identity is missing",settings.optString("accountId").length()>0);
                    assertTrue("Direct account access token is already expired",settings.optLong("expiresAt")>System.currentTimeMillis());
                }
                credentials.save(settings);
            } finally {
                assertTrue("Could not remove transient provider configuration",!provisioned.exists()||provisioned.delete());
            }
            h=host();command(h,"configure");
            Set<String> old=new HashSet<>();JSONArray existing=store.load().optJSONArray("tasks");
            if(existing!=null)for(int i=0;i<existing.length();i++)old.add(existing.getJSONObject(i).optString("conversationId"));
            String taskText="Use a background phone-operator worker to open dev.pocket.agent.fixture, fill AGENT_INPUT with LiveCodex, press AGENT_CLICK once, and verify STATUS_CLICKED:LiveCodex COUNT:1. Tell the worker to read capabilities first, use individual phone calls rather than phone_batch, use fresh observations, and release the screen afterward. Keep main available and end your response promptly after dispatch; do not wait for or poll the worker.";
            assertTrue("Send rejected",h.dispatch(new JSONObject().put("action","send").put("text",taskText)).get(5,TimeUnit.SECONDS).optBoolean("ok"));
            JSONObject task=null;
            while(SystemClock.elapsedRealtime()<deadline) {
                task=findTask(store.load(),old);
                if(task!=null&&task.optString("status").equals("running"))break;
                Thread.sleep(150);
            }
            assertNotNull("No background worker was dispatched",task);
            assertEquals("Worker did not enter running state","running",task.optString("status"));
            String id=task.getString("conversationId");
            String question="While the worker continues, what is 2 + 2? Reply briefly. Test "+System.nanoTime();
            assertTrue("Concurrent main message rejected",h.dispatch(new JSONObject().put("action","send").put("text",question)).get(5,TimeUnit.SECONDS).optBoolean("ok"));
            boolean mainAnswered=false,answeredWhileActive=false,workerFinished=false;
            JSONObject state=new JSONObject();
            while(SystemClock.elapsedRealtime()<deadline) {
                state=store.load();task=findTask(state,old);
                boolean answered=answeredFour(state,question);
                if(answered&&!mainAnswered)answeredWhileActive=task!=null&&activeTask(task);
                mainAnswered|=answered;
                if(task!=null&&!activeTask(task)) {
                    assertEquals("Live worker did not complete successfully","completed",task.optString("status"));
                    workerFinished=true;
                }
                if(mainAnswered&&workerFinished&&!state.optBoolean("busy"))break;
                Thread.sleep(200);
            }
            assertTrue("Main did not answer the concurrent message",mainAnswered);
            assertTrue("Worker did not complete within the bounded live-test window",workerFinished);
            JSONObject durable=store.loadDurable();JSONObject workers=durable.optJSONObject("workerMessages");
            JSONArray messages=workers==null?null:workers.optJSONArray(id);
            assertNotNull("Worker tool transcript was not persisted",messages);
            Set<String> toolNames=new HashSet<>();int toolCount=0;
            for(int i=0;i<messages.length();i++) {
                JSONObject m=messages.optJSONObject(i);
                if(m!=null&&m.optString("role").equals("toolResult")) {toolNames.add(m.optString("toolName"));toolCount++;}
            }
            for(String expected:new String[]{"phone_capabilities","phone_open_app","phone_observe","phone_act"})
                assertTrue("Worker did not execute required native tool: "+expected,toolNames.contains(expected));
            assertTrue("No native observation verified the actual fixture outcome",observedFixture(messages));
            assertTrue("Main answer arrived only after worker completion",answeredWhileActive);
            System.out.println("LIVE_DEVICE_RESULT="+new JSONObject().put("authMode",authMode).put("direct",direct).put("directAccountInference",direct).put("model","gpt-6-luna").put("modelInference",true).put("backgroundWorker",true).put("mainAnsweredFour",true).put("answeredWhileWorkerActive",answeredWhileActive).put("fixtureObserved","STATUS_CLICKED:LiveCodex COUNT:1").put("workerNativeToolResults",toolCount));
        } finally {
            // A subsequent ordinary device test must never unexpectedly spend inference.
            if(h!=null) {
                try {command(h,"stop_all");}catch(Exception ignored) { }
                long stopDeadline=SystemClock.elapsedRealtime()+10000;
                while(SystemClock.elapsedRealtime()<stopDeadline) {
                    try(StateStore current=new StateStore(c)) {
                        if(!current.load().optBoolean("active"))break;
                    }
                    Thread.sleep(100);
                }
            }
            credentials.save(new JSONObject().put("authMode","scripted").put("endpoint","https://api.openai.com/v1").put("model","scripted").put("apiKey",""));
            if(h!=null)command(h,"configure");
            if(provisioned.exists())provisioned.delete();
        }
    }
}
