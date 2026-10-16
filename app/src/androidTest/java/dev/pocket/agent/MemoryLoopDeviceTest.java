package dev.pocket.agent;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MemoryLoopDeviceTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private JSONObject until(StateStore store,int turn,String contains,long limit)throws Exception {
        long end=System.currentTimeMillis()+limit;
        JSONObject state=new JSONObject();
        while(System.currentTimeMillis()<end){
            state=store.load();
            if(state.optInt("mainTurns")>=turn&&!state.optBoolean("busy")&&
               (contains==null||state.optJSONArray("messages")!=null&&state.getJSONArray("messages").toString().contains(contains)))
                return state;
            Thread.sleep(60);
        }
        throw new AssertionError("Memory main-loop timeout: "+state.optString("lastError"));
    }
    @Test public void realPiMainToolLoopSavesAndRecallsMemory()throws Exception {
        Context c=context();
        new CredentialStore(c).save(new JSONObject().put("authMode","scripted").put("endpoint","https://api.openai.com/v1").put("model","scripted").put("apiKey",""));
        c.startForegroundService(new Intent(c,AgentService.class).setAction(AgentService.ACTION_START));
        RuntimeHost h=null;long end=System.currentTimeMillis()+30000;
        while(System.currentTimeMillis()<end){
            h=AgentService.peekHost();
            if(h!=null)try{h.dispatch(new JSONObject().put("action","state")).get(2,TimeUnit.SECONDS);break;}catch(Exception ignored){}
            Thread.sleep(100);
        }
        assertNotNull("Pocket Agent service failed to start",h);
        StateStore state=new StateStore(c);
        MemoryStore memory=new MemoryStore(c);
        int original=state.load().optInt("mainTurns");
        String key="fixture:memory-e2e-"+System.currentTimeMillis();
        String content="Always inspect an existing project branch before editing.";
        long id=-1;
        try {
            h.dispatch(new JSONObject().put("action","stop_all")).get(5,TimeUnit.SECONDS);
            h.dispatch(new JSONObject().put("action","configure")).get(5,TimeUnit.SECONDS);
            JSONObject sent=h.dispatch(new JSONObject().put("action","send").put("text","memory fixture remember "+key+"|"+content)).get(5,TimeUnit.SECONDS);
            assertTrue("Synthetic memory tool request rejected: "+sent,sent.optBoolean("ok"));
            until(state,original+1,"saved",15000);
            JSONArray hits=memory.run(new JSONObject().put("action","search").put("query","editing"),"main").getJSONArray("memories");
            for(int i=0;i<hits.length();i++)if(key.equals(hits.getJSONObject(i).optString("key")))id=hits.getJSONObject(i).getLong("id");
            assertTrue("Memory tool did not write to Android SQLite",id>0);
            JSONObject recalled=h.dispatch(new JSONObject().put("action","send").put("text","memory fixture recall editing")).get(5,TimeUnit.SECONDS);
            assertTrue(recalled.optBoolean("ok"));
            JSONObject answer=until(state,original+2,content,15000);
            System.out.println("POCKET_MEMORY_MAIN_E2E="+new JSONObject()
                .put("key",key).put("recordId",id).put("piToolUsed",true)
                .put("sqliteSaved",true).put("retrievedInModelToolLoop",true)
                .put("mainTurns",answer.optInt("mainTurns")));
        } finally {
            if(id>0)memory.run(new JSONObject().put("action","forget").put("id",id),"main");
            h.dispatch(new JSONObject().put("action","stop_all")).get(5,TimeUnit.SECONDS);
            memory.close();state.close();
        }
    }
}
