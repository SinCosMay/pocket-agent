package dev.pocket.agent;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
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
    private boolean rejectedBeforeDispatch(JSONObject result) {
        return result.optBoolean("isError") && "STALE_OBSERVATION".equals(result.optString("code"))
            && "not_dispatched".equals(result.optString("dispatch_status"));
    }
    private void requireText(JSONObject observation,String expected) throws Exception {
        org.json.JSONArray nodes=observation.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++)if(expected.equals(nodes.getJSONObject(i).optString("text")))return;
        throw new AssertionError("Fixture precondition changed: expected "+expected+" in "+observation);
    }
    /** Change snapshot/ID only after a recorded pre-dispatch rejection. args retains the successful request for duplicate checks. */
    private JSONObject fixtureAction(NativeTools tools,String owner,JSONObject args,String expected) throws Exception {
        String base=args.getString("operation_id");
        for(int attempt=0;attempt<4;attempt++) {
            JSONObject observation=fixtureObservation(tools,owner);requireText(observation,expected);
            args.put("snapshot_id",observation.getString("snapshot_id")).put("operation_id",base+"r"+attempt);
            JSONObject result=tools.execute("phone_act",args,owner);
            if(!rejectedBeforeDispatch(result))return result;
            Thread.sleep(250);
        }
        throw new AssertionError("Fixture action did not settle within four pre-dispatch attempts");
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
    @Test public void observationNodeSerializationStaysBelowToolTextBudget() throws Exception {
        java.util.List<dev.pocket.control.ControlBackend.Node> nodes=new java.util.ArrayList<>();
        String hostile=("\\\"\n".repeat(500))+"tail";
        for(int i=0;i<500;i++)nodes.add(new dev.pocket.control.ControlBackend.Node(i,hostile,hostile,hostile,hostile,
            0,0,720,1280,true,false,false,true,false,false));
        JSONObject bounded=NativeTools.boundedObservationNodes(nodes);
        assertTrue(bounded.toString(),bounded.getBoolean("nodes_truncated"));
        assertTrue("Serialized node payload must leave room below the 32 KB runtime limit: "+bounded.toString().length(),bounded.toString().length()<25_000);
        assertTrue(bounded.getJSONArray("nodes").length()<500);
        if(bounded.getJSONArray("nodes").length()>0) {
            JSONObject first=bounded.getJSONArray("nodes").getJSONObject(0);
            assertTrue(first.getString("text").length()<=400);
            assertTrue(first.getString("description").length()<=400);
            assertTrue(first.getString("resource_id").length()<=240);
            assertTrue(first.getString("role").length()<=160);
        }
        java.util.List<dev.pocket.control.ControlBackend.Node> clipped=java.util.List.of(
            new dev.pocket.control.ControlBackend.Node(0,"id","x".repeat(500),"short","role",0,0,10,10,true,false,false,false,false,false));
        JSONObject clippedResult=NativeTools.boundedObservationNodes(clipped);
        assertEquals(1,clippedResult.getJSONArray("nodes").length());
        assertTrue("Field clipping must be disclosed",clippedResult.getBoolean("nodes_truncated"));
        JSONObject batchBudget=NativeTools.boundedObservationNodes(nodes,3000);
        assertTrue(batchBudget.toString(),batchBudget.getBoolean("nodes_truncated"));
        assertTrue("Batch-sized node payload must leave envelope room: "+batchBudget.toString().length(),batchBudget.toString().length()<4000);
    }
    @Test public void batchTextBudgetExcludesScreenshotPayloads() throws Exception {
        String largeImage="A".repeat(40_000);
        JSONObject entry=Json.obj("index",0,"name","phone_observe","result",Json.obj(
            "snapshot_id","s","package_name","dev.pocket.agent","nodes",new org.json.JSONArray()
                .put(Json.obj("text","x".repeat(150))),"images",Json.arr(Json.obj("data",largeImage,"mimeType","image/jpeg"))));
        assertTrue("Fixture must exceed the old budget path",entry.toString().length()>40_000);
        int textLength=NativeTools.textLengthWithoutImages(entry);
        assertTrue("Screenshot base64 must not consume the batch text budget: "+textLength,textLength<1_000);
        assertTrue("Actual textual payload must still be counted",textLength>150);
    }
    @Test public void batchImagePayloadKeepsOnlyLatestAndCapsOversized() throws Exception {
        JSONObject priorResult=Json.obj("snapshot_id","old","images",Json.arr(Json.obj("data","A".repeat(40_000),"mimeType","image/jpeg")));
        JSONArray prior=new JSONArray().put(Json.obj("index",0,"name","phone_observe","result",priorResult));
        JSONObject current=Json.obj("snapshot_id","new","images",Json.arr(Json.obj("data","B".repeat(50_000),"mimeType","image/jpeg")));
        NativeTools.prepareBatchObservationImages(prior,current);
        assertFalse("Superseded batch screenshots must be discarded before bridge serialization",priorResult.has("images"));
        assertEquals(1,current.getJSONArray("images").length());
        assertEquals(50_000,current.getJSONArray("images").getJSONObject(0).getString("data").length());

        JSONObject oversized=Json.obj("snapshot_id","huge","images",Json.arr(Json.obj("data","C".repeat(2_000_001),"mimeType","image/jpeg")));
        NativeTools.prepareBatchObservationImages(new JSONArray(),oversized);
        assertFalse("Oversized screenshot must not cross the native bridge",oversized.has("images"));
        assertTrue(oversized.getBoolean("image_omitted"));
    }
    @Test public void batchObservationLeaseRecoveryClassifiesOnlyReadFailuresThatNeedReplacement() throws Exception {
        assertTrue(NativeTools.observationLeaseReplacementNeeded(Json.failure("RESULT_UNKNOWN","Timed out; inspect before repeating the action")));
        assertTrue(NativeTools.observationLeaseReplacementNeeded(Json.failure("NOT_READY","Shizuku backend disconnected; observe after recovery")));
        assertTrue(NativeTools.observationLeaseReplacementNeeded(Json.failure("NOT_READY","Virtual display backend disconnected")));
        assertTrue(NativeTools.observationLeaseReplacementNeeded(Json.failure("NOT_READY","No virtual display for conversation")));
        assertFalse(NativeTools.observationLeaseReplacementNeeded(Json.failure("STALE_OBSERVATION","Screen changed during observation")));
        assertFalse(NativeTools.observationLeaseReplacementNeeded(Json.failure("NOT_READY","No active accessibility window yet")));
        assertFalse(NativeTools.observationLeaseReplacementNeeded(Json.obj("snapshot_id","ready")));
    }
    @Test public void batchObservationRecoversMissingNativeLeaseInPlace() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        try(NativeTools tools=new NativeTools(context())) {
            String owner="batch-missing-lease";
            JSONObject batch=new JSONObject().put("calls",new org.json.JSONArray()
                .put(new JSONObject().put("name","phone_observe").put("args",new JSONObject())));
            JSONObject result=tools.execute("phone_batch",batch,owner);
            assertFalse(result.toString(),result.optBoolean("isError"));
            assertEquals(result.toString(),"completed",result.optString("status"));
            JSONObject observed=result.getJSONArray("results").getJSONObject(0).getJSONObject("result");
            assertTrue(observed.toString(),observed.optBoolean("display_replaced"));
            assertEquals(observed.toString(),"dev.pocket.agent",observed.optString("package_name"));
        }
    }
    @Test public void batchLeaseReplacementStopsBeforeRemainingCalls() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        try(NativeTools tools=new NativeTools(context())) {
            String owner="batch-replacement-stop";
            JSONObject batch=new JSONObject().put("calls",new org.json.JSONArray()
                .put(new JSONObject().put("name","phone_observe").put("args",new JSONObject()))
                .put(new JSONObject().put("name","phone_apps").put("args",new JSONObject())));
            JSONObject result=tools.execute("phone_batch",batch,owner);
            assertTrue(result.toString(),result.optBoolean("isError"));
            assertEquals(result.toString(),"partial",result.optString("status"));
            assertTrue(result.toString(),result.optBoolean("display_replaced"));
            assertEquals(result.toString(),0,result.optInt("stopped_after",-1));
            assertEquals(result.toString(),1,result.optInt("next_index",-1));
            assertEquals(result.toString(),1,result.getJSONArray("results").length());
            assertTrue(result.toString(),result.getJSONArray("results").getJSONObject(0).getJSONObject("result").optBoolean("display_replaced"));
        }
    }
    @Test public void coldDisplayBootstrapsAndRapidReacquireStaysHealthy() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        try(NativeTools tools=new NativeTools(context())) {
            String owner="rapid-reacquire";
            for(int i=0;i<8;i++) {
                JSONObject acquired=tools.execute("phone_screen",new JSONObject().put("action","acquire"),owner);
                assertFalse("Acquire iteration "+i+": "+acquired,acquired.optBoolean("isError"));
                assertTrue(acquired.toString(),acquired.optInt("display_id")>0);
                long deadline=System.currentTimeMillis()+4000;JSONObject observation=new JSONObject();
                while(System.currentTimeMillis()<deadline) {
                    observation=tools.execute("phone_observe",new JSONObject(),owner);
                    if(!observation.optBoolean("isError"))break;
                    assertEquals(observation.toString(),"NOT_READY",observation.optString("code"));
                    Thread.sleep(100);
                }
                assertFalse("Cold display never became observable on iteration "+i+": "+observation,observation.optBoolean("isError"));
                assertEquals(observation.toString(),"dev.pocket.agent",observation.optString("package_name"));
                JSONObject released=tools.execute("phone_screen",new JSONObject().put("action","release"),owner);
                assertEquals(released.toString(),"released",released.optString("status"));
            }
        }
    }
    @Test public void batchLaunchSettlesFinalObservationWithoutReplayingMutation() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        try(NativeTools tools=new NativeTools(context())) {
            String owner="batch-readiness";JSONObject caps=tools.execute("phone_capabilities",new JSONObject(),owner);
            String prefix=caps.getString("operation_prefix"),nonce=Long.toString(System.nanoTime());
            JSONObject acquired=tools.execute("phone_screen",new JSONObject().put("action","acquire"),owner);
            assertFalse(acquired.toString(),acquired.optBoolean("isError"));
            JSONObject batch=new JSONObject().put("calls",new org.json.JSONArray()
                .put(new JSONObject().put("name","phone_open_app").put("args",new JSONObject()
                    .put("package_name","dev.pocket.agent.fixture").put("operation_id",prefix+"_batchopen"+nonce)))
                .put(new JSONObject().put("name","phone_observe").put("args",new JSONObject().put("max_nodes",500))));
            JSONObject result=tools.execute("phone_batch",batch,owner);
            assertFalse(result.toString(),result.optBoolean("isError"));
            assertEquals(result.toString(),"completed",result.optString("status"));
            JSONObject observed=result.getJSONArray("results").getJSONObject(1).getJSONObject("result");
            assertEquals(observed.toString(),"dev.pocket.agent.fixture",observed.optString("package_name"));
            assertTrue("Whole batch must stay below the runtime text cap: "+result.toString().length(),result.toString().length()<30_000);
        }
    }
    @Test public void rapidScreenshotsRetryReadThrottleWithoutLosingObservation() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        try(NativeTools tools=new NativeTools(context())) {
            String owner="rapid-screenshot";JSONObject caps=tools.execute("phone_capabilities",new JSONObject(),owner);
            String prefix=caps.getString("operation_prefix"),nonce=Long.toString(System.nanoTime());
            JSONObject acquired=tools.execute("phone_screen",new JSONObject().put("action","acquire"),owner);
            assertFalse(acquired.toString(),acquired.optBoolean("isError"));
            JSONObject opened=tools.execute("phone_open_app",new JSONObject().put("package_name","dev.pocket.agent.fixture")
                .put("operation_id",prefix+"_shotopen"+nonce),owner);
            assertFalse(opened.toString(),opened.optBoolean("isError"));
            fixtureObservation(tools,owner);
            JSONObject args=new JSONObject().put("include_image",true).put("max_image_dimension",512).put("max_nodes",80);
            JSONObject first=tools.execute("phone_observe",args,owner);
            assertFalse(first.toString(),first.optBoolean("isError"));
            assertEquals(1,first.getJSONArray("images").length());
            JSONObject second=tools.execute("phone_observe",args,owner);
            assertFalse(second.toString(),second.optBoolean("isError"));
            assertEquals(1,second.getJSONArray("images").length());
            assertTrue(second.toString(),second.getJSONArray("nodes").length()>0);
            JSONObject batch=tools.execute("phone_batch",new JSONObject().put("calls",new JSONArray()
                .put(new JSONObject().put("name","phone_observe").put("args",args))
                .put(new JSONObject().put("name","phone_observe").put("args",args))),owner);
            assertFalse(batch.toString(),batch.optBoolean("isError"));
            assertEquals("completed",batch.optString("status"));
            assertEquals(2,batch.getJSONArray("results").length());
            assertTrue(batch.toString(),batch.getJSONArray("results").getJSONObject(1).getJSONObject("result").has("images"));
        }
    }
    @Test public void delayedAccessibilityTextVerificationCoversExactUnicodeClearAndBatch() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        try(NativeTools tools=new NativeTools(context())) {
            String owner="delayed-text";JSONObject caps=tools.execute("phone_capabilities",new JSONObject(),owner);
            String prefix=caps.getString("operation_prefix"),nonce=Long.toString(System.nanoTime());
            JSONObject acquired=tools.execute("phone_screen",new JSONObject().put("action","acquire"),owner);
            assertFalse(acquired.toString(),acquired.optBoolean("isError"));
            JSONObject opened=tools.execute("phone_open_app",new JSONObject().put("package_name","dev.pocket.agent.fixture")
                .put("operation_id",prefix+"_delayopen"+nonce),owner);
            assertFalse(opened.toString(),opened.optBoolean("isError"));
            JSONObject observation=fixtureObservation(tools,owner);
            String exact="  नमस्ते 😀 café  ";
            JSONObject type=new JSONObject().put("kind","type").put("snapshot_id",observation.getString("snapshot_id"))
                .put("selector",new JSONObject().put("description","AGENT_DELAYED_INPUT")).put("text",exact).put("replace",true)
                .put("operation_id",prefix+"_delaytype"+nonce);
            long started=android.os.SystemClock.elapsedRealtime();
            JSONObject typed=tools.execute("phone_act",type,owner);
            long elapsed=android.os.SystemClock.elapsedRealtime()-started;
            assertFalse(typed.toString(),typed.optBoolean("isError"));
            assertEquals("verified",typed.optString("status"));
            assertTrue("Delayed fixture should require asynchronous verification, elapsed="+elapsed,elapsed>=250);
            requireText(fixtureObservation(tools,owner),exact);

            observation=fixtureObservation(tools,owner);
            JSONObject clear=new JSONObject(type.toString()).put("snapshot_id",observation.getString("snapshot_id"))
                .put("text","").put("operation_id",prefix+"_delayclear"+nonce);
            JSONObject cleared=tools.execute("phone_act",clear,owner);
            assertFalse(cleared.toString(),cleared.optBoolean("isError"));
            assertEquals("verified",cleared.optString("status"));

            observation=fixtureObservation(tools,owner);
            String batchText="Batch verified state "+nonce;
            JSONObject batchType=new JSONObject(type.toString()).put("snapshot_id",observation.getString("snapshot_id"))
                .put("text",batchText).put("operation_id",prefix+"_delaybatch"+nonce);
            JSONObject batch=tools.execute("phone_batch",new JSONObject().put("calls",new JSONArray()
                .put(new JSONObject().put("name","phone_act").put("args",batchType))
                .put(new JSONObject().put("name","phone_observe").put("args",new JSONObject().put("max_nodes",120)))),owner);
            assertFalse(batch.toString(),batch.optBoolean("isError"));
            assertEquals("completed",batch.optString("status"));
            requireText(batch.getJSONArray("results").getJSONObject(1).getJSONObject("result"),batchText);

            observation=fixtureObservation(tools,owner);
            String uncertainText="Applied before rejected result "+nonce;
            JSONObject uncertain=new JSONObject(type.toString()).put("snapshot_id",observation.getString("snapshot_id"))
                .put("selector",new JSONObject().put("description","AGENT_REJECTING_INPUT")).put("text",uncertainText)
                .put("operation_id",prefix+"_delayunknown"+nonce);
            JSONObject unknown=tools.execute("phone_act",uncertain,owner);
            assertTrue(unknown.toString(),unknown.optBoolean("isError"));
            assertEquals("RESULT_UNKNOWN",unknown.optString("code"));
            requireText(fixtureObservation(tools,owner),uncertainText);
            JSONObject duplicateUnknown=tools.execute("phone_act",uncertain,owner);
            assertEquals(unknown.toString(),duplicateUnknown.toString());
        }
    }
    @Test public void physicalDisplayCanLaunchObserveAndActWithoutVirtualLease() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        try {
            try(NativeTools tools=new NativeTools(context())) {
                String owner="physical-display";JSONObject caps=tools.execute("phone_capabilities",new JSONObject(),owner);
                assertEquals(caps.toString(),0,caps.getJSONObject("virtual_displays").optInt("active",-1));
                assertTrue(caps.toString(),caps.getJSONObject("physical_display").optBoolean("available"));
                String prefix=caps.getString("operation_prefix"),nonce=Long.toString(System.nanoTime());
                JSONObject opened=tools.execute("phone_open_app",new JSONObject()
                    .put("package_name","dev.pocket.agent.fixture").put("display_target","physical")
                    .put("operation_id",prefix+"_physicalopen"+nonce),owner);
                assertFalse(opened.toString(),opened.optBoolean("isError"));
                assertEquals(opened.toString(),0,opened.optInt("display_id",-1));
                assertEquals(opened.toString(),"physical",opened.optString("display_target"));

                JSONObject observeArgs=new JSONObject().put("display_target","physical").put("max_nodes",120);
                long deadline=System.currentTimeMillis()+10000;JSONObject observation=new JSONObject();
                while(System.currentTimeMillis()<deadline) {
                    observation=tools.execute("phone_observe",observeArgs,owner);
                    if("dev.pocket.agent.fixture".equals(observation.optString("package_name")) && observation.optJSONArray("nodes")!=null && observation.getJSONArray("nodes").length()>0)break;
                    Thread.sleep(200);
                }
                assertFalse(observation.toString(),observation.optBoolean("isError"));
                assertEquals(observation.toString(),"dev.pocket.agent.fixture",observation.optString("package_name"));
                assertEquals(observation.toString(),0,observation.optInt("display_id",-1));
                assertEquals(observation.toString(),"physical",observation.optString("display_target"));
                requireText(observation,"STATUS_READY");

                JSONObject click=new JSONObject().put("kind","click").put("snapshot_id",observation.getString("snapshot_id"))
                    .put("selector",new JSONObject().put("text","AGENT_CLICK")).put("display_target","physical")
                    .put("operation_id",prefix+"_physicalclick"+nonce);
                JSONObject clicked=tools.execute("phone_act",click,owner);
                assertFalse(clicked.toString(),clicked.optBoolean("isError"));
                assertEquals(clicked.toString(),0,clicked.optInt("display_id",-1));
                assertEquals(clicked.toString(),"physical",clicked.optString("display_target"));

                deadline=System.currentTimeMillis()+5000;JSONObject verified=new JSONObject();
                while(System.currentTimeMillis()<deadline) {
                    verified=tools.execute("phone_observe",observeArgs,owner);
                    JSONArray nodes=verified.optJSONArray("nodes");boolean found=false;
                    if(nodes!=null)for(int i=0;i<nodes.length();i++)if(nodes.getJSONObject(i).optString("text").startsWith("STATUS_CLICKED:")){found=true;break;}
                    if(found)break;
                    Thread.sleep(150);
                }
                boolean clickedVisible=false;JSONArray nodes=verified.optJSONArray("nodes");
                if(nodes!=null)for(int i=0;i<nodes.length();i++)if(nodes.getJSONObject(i).optString("text").startsWith("STATUS_CLICKED:"))clickedVisible=true;
                assertTrue("Physical-display click was not visible in fixture: "+verified,clickedVisible);
                JSONObject after=tools.execute("phone_capabilities",new JSONObject(),owner);
                assertEquals("Physical-only UI work must not allocate a private display: "+after,0,after.getJSONObject("virtual_displays").optInt("active",-1));
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("am force-stop dev.pocket.agent.fixture").close();
        }
    }

    @Test public void directNativeFixtureAndMutationLedger() throws Exception {
        DeviceTestAccess.ensureAccessibility();Context c=context();NativeTools tools=new NativeTools(c);String owner="instrumentation-fixture";
        JSONObject caps=tools.execute("phone_capabilities",new JSONObject(),owner);
        assertTrue(caps.toString(),caps.optBoolean("accessibility_connected"));
        String prefix=caps.getString("operation_prefix");String nonce=Long.toString(System.nanoTime());
        JSONObject open=new JSONObject().put("package_name","dev.pocket.agent.fixture").put("operation_id",prefix+"_open"+nonce);
        try {
            JSONObject invalidId=tools.execute("phone_open_app",new JSONObject(open.toString()).put("operation_id",prefix+"_type001"),owner);
            assertEquals("INVALID_ARGUMENT",invalidId.optString("code"));
            assertTrue(invalidId.toString(),invalidId.optString("message").contains("8..48"));
            assertTrue(invalidId.toString(),invalidId.optString("message").contains("not dispatched"));
            JSONObject opened=tools.execute("phone_open_app",open,owner);assertFalse(opened.toString(),opened.optBoolean("isError"));
            JSONObject observation=fixtureObservation(tools,owner);assertEquals(observation.toString(),"dev.pocket.agent.fixture",observation.optString("package_name"));
            JSONObject type=new JSONObject().put("kind","type").put("snapshot_id",observation.getString("snapshot_id")).put("selector",new JSONObject().put("description","AGENT_INPUT")).put("text","PocketWorks").put("operation_id",prefix+"_type"+nonce);
            JSONObject typed=fixtureAction(tools,owner,type,"STATUS_READY");assertFalse(typed.toString(),typed.optBoolean("isError"));Thread.sleep(300);
            observation=fixtureObservation(tools,owner);requireText(observation,"PocketWorks");
            JSONObject click=new JSONObject().put("kind","click").put("snapshot_id",observation.getString("snapshot_id")).put("selector",new JSONObject().put("text","AGENT_CLICK")).put("operation_id",prefix+"_click"+nonce);
            JSONObject clicked=fixtureAction(tools,owner,click,"STATUS_READY");assertFalse(clicked.toString(),clicked.optBoolean("isError"));Thread.sleep(300);
            JSONObject duplicate=tools.execute("phone_act",click,owner);assertEquals(clicked.toString(),duplicate.toString());
            JSONObject verified=fixtureObservation(tools,owner);requireText(verified,"STATUS_CLICKED:PocketWorks COUNT:1");
            JSONObject other=tools.execute("phone_observe",new JSONObject(),"competing-worker");assertEquals(other.toString(),"SCREEN_BUSY",other.optString("code"));
            JSONObject invalidBatch=new JSONObject().put("calls",new org.json.JSONArray().put(new JSONObject().put("name","phone_act").put("args",click)).put(new JSONObject().put("name","phone_missing").put("args",new JSONObject())));
            assertTrue(tools.execute("phone_batch",invalidBatch,owner).optBoolean("isError"));
            JSONObject first=new JSONObject(click.toString()).put("snapshot_id",verified.getString("snapshot_id")).put("operation_id",prefix+"_partial"+nonce);
            JSONObject second=new JSONObject(click.toString()).put("snapshot_id","unknown-snapshot").put("operation_id",prefix+"_failed"+nonce);
            JSONObject partialBatch=new JSONObject().put("calls",new org.json.JSONArray().put(new JSONObject().put("name","phone_act").put("args",first)).put(new JSONObject().put("name","phone_act").put("args",second)));
            JSONObject partial=new JSONObject();
            for(int attempt=0;attempt<4;attempt++) {
                observation=fixtureObservation(tools,owner);requireText(observation,"STATUS_CLICKED:PocketWorks COUNT:1");
                first.put("snapshot_id",observation.getString("snapshot_id")).put("operation_id",prefix+"_partial"+nonce+"r"+attempt);
                partial=tools.execute("phone_batch",partialBatch,owner);
                if(partial.optInt("stopped_at",-1)!=0)break;
                assertTrue(partial.toString(),rejectedBeforeDispatch(partial.getJSONArray("results").getJSONObject(0).getJSONObject("result")));
                Thread.sleep(250);
            }
            assertEquals(partial.toString(),"partial",partial.optString("status"));
            assertEquals(partial.toString(),1,partial.optInt("stopped_at"));
            JSONObject stale=partial.getJSONArray("results").getJSONObject(1).getJSONObject("result");
            assertEquals("STALE_OBSERVATION",stale.optString("code"));
            assertEquals("not_dispatched",stale.optString("dispatch_status"));
            assertEquals(partial.toString(),tools.execute("phone_batch",partialBatch,owner).toString());
            Thread.sleep(300);
            requireText(fixtureObservation(tools,owner),"STATUS_CLICKED:PocketWorks COUNT:2");
            JSONObject unavailable=tools.execute("phone_contacts",new JSONObject().put("query","Synthetic missing permission"),owner);
            assertEquals(unavailable.toString(),"PERMISSION_REQUIRED",unavailable.optString("code"));
            System.out.println("POCKET_FIXTURE_VERIFIED="+verified);
        } finally {tools.releaseOwner(owner);}
    }
}
