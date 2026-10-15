package dev.pocket.agent;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Runs on the signed release in the Fedora emulator, never in GitHub Actions. */
@RunWith(AndroidJUnit4.class)
public class RuntimeDeviceTest {
    private Context context() {return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private JSONObject fixtureObservation(NativeTools tools,String owner) throws Exception {
        long deadline=System.currentTimeMillis()+10000;JSONObject observation=new JSONObject();
        while(System.currentTimeMillis()<deadline) {
            observation=tools.execute("phone_observe",new JSONObject(),owner);
            if("dev.pocket.agent.fixture".equals(observation.optString("package_name")) && observation.optJSONArray("nodes")!=null && observation.getJSONArray("nodes").length()>0)return observation;
            Thread.sleep(200);
        }
        throw new AssertionError("Fixture window did not become ready: "+observation);
    }
    private RuntimeHost host() throws Exception {
        // Visible Activity starts the user-visible foreground service; no background start exemption.
        Context c=context();c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(500);
        c.startForegroundService(new Intent(c,AgentService.class).setAction(AgentService.ACTION_START));
        long deadline=System.currentTimeMillis()+30000;
        while(System.currentTimeMillis()<deadline) {
            RuntimeHost h=AgentService.peekHost();
            if(h!=null) {try {h.memoryStats().get(2,TimeUnit.SECONDS);return h;}catch(Exception startup) {}}
            Thread.sleep(100);
        }
        throw new AssertionError("Runtime host did not start");
    }
    @Test public void nodeEmbeddingStreamingAndCancellation() throws Exception {
        RuntimeHost h=host();JSONObject gate=h.runCompatibilityGate().get(30,TimeUnit.SECONDS);
        assertTrue(gate.toString(),gate.optBoolean("ok"));
        System.out.println("POCKET_NODE_GATE="+gate);
        System.out.println("POCKET_NODE_MEMORY="+h.memoryStats().get(5,TimeUnit.SECONDS));
    }
    @Test public void directNativeFixtureAndMutationLedger() throws Exception {
        DeviceTestAccess.ensureAccessibility();Context c=context();NativeTools tools=new NativeTools(c);String owner="instrumentation-fixture";
        JSONObject caps=tools.execute("phone_capabilities",new JSONObject(),owner);
        assertTrue(caps.toString(),caps.optBoolean("accessibility_connected"));
        String prefix=caps.getString("operation_prefix");String nonce=Long.toString(System.nanoTime());
        JSONObject open=new JSONObject().put("package_name","dev.pocket.agent.fixture").put("operation_id",prefix+"_open"+nonce);
        try {
            JSONObject opened=tools.execute("phone_open_app",open,owner);assertFalse(opened.toString(),opened.optBoolean("isError"));
            JSONObject observation=fixtureObservation(tools,owner);assertEquals(observation.toString(),"dev.pocket.agent.fixture",observation.optString("package_name"));
            JSONObject type=new JSONObject().put("kind","type").put("snapshot_id",observation.getString("snapshot_id")).put("selector",new JSONObject().put("description","AGENT_INPUT")).put("text","PocketWorks").put("operation_id",prefix+"_type"+nonce);
            JSONObject typed=tools.execute("phone_act",type,owner);assertFalse(typed.toString(),typed.optBoolean("isError"));Thread.sleep(300);
            observation=tools.execute("phone_observe",new JSONObject(),owner);
            JSONObject click=new JSONObject().put("kind","click").put("snapshot_id",observation.getString("snapshot_id")).put("selector",new JSONObject().put("text","AGENT_CLICK")).put("operation_id",prefix+"_click"+nonce);
            JSONObject clicked=tools.execute("phone_act",click,owner);assertFalse(clicked.toString(),clicked.optBoolean("isError"));Thread.sleep(300);
            JSONObject duplicate=tools.execute("phone_act",click,owner);assertEquals(clicked.toString(),duplicate.toString());
            JSONObject verified=tools.execute("phone_observe",new JSONObject(),owner);assertTrue(verified.toString(),verified.toString().contains("STATUS_CLICKED:PocketWorks COUNT:1"));
            JSONObject other=tools.execute("phone_observe",new JSONObject(),"competing-worker");assertEquals(other.toString(),"SCREEN_BUSY",other.optString("code"));
            JSONObject invalidBatch=new JSONObject().put("calls",new org.json.JSONArray().put(new JSONObject().put("name","phone_act").put("args",click)).put(new JSONObject().put("name","phone_missing").put("args",new JSONObject())));
            assertTrue(tools.execute("phone_batch",invalidBatch,owner).optBoolean("isError"));
            JSONObject first=new JSONObject(click.toString()).put("snapshot_id",verified.getString("snapshot_id")).put("operation_id",prefix+"_partial"+nonce);
            JSONObject second=new JSONObject(click.toString()).put("snapshot_id","unknown-snapshot").put("operation_id",prefix+"_failed"+nonce);
            JSONObject partialBatch=new JSONObject().put("calls",new org.json.JSONArray().put(new JSONObject().put("name","phone_act").put("args",first)).put(new JSONObject().put("name","phone_act").put("args",second)));
            JSONObject partial=tools.execute("phone_batch",partialBatch,owner);
            assertEquals(partial.toString(),"partial",partial.optString("status"));
            JSONObject stale=partial.getJSONArray("results").getJSONObject(1).getJSONObject("result");
            assertEquals("STALE_OBSERVATION",stale.optString("code"));
            assertEquals("not_dispatched",stale.optString("dispatch_status"));
            assertEquals(partial.toString(),tools.execute("phone_batch",partialBatch,owner).toString());
            Thread.sleep(300);
            assertTrue(tools.execute("phone_observe",new JSONObject(),owner).toString().contains("STATUS_CLICKED:PocketWorks COUNT:2"));
            JSONObject unavailable=tools.execute("phone_contacts",new JSONObject().put("query","Synthetic missing permission"),owner);
            assertEquals(unavailable.toString(),"PERMISSION_REQUIRED",unavailable.optString("code"));
            System.out.println("POCKET_FIXTURE_VERIFIED="+verified);
        } finally {tools.releaseOwner(owner);}
    }
}
