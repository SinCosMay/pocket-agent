package dev.pocket.agent;
import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AsyncAgentDeviceTest {
 private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
 private interface Condition {boolean ok(JSONObject state) throws Exception;}
 private JSONObject await(StateStore store,Condition condition,long millis)throws Exception {
  long deadline=System.currentTimeMillis()+millis;JSONObject state=new JSONObject();
  while(System.currentTimeMillis()<deadline){state=store.load();if(condition.ok(state))return state;Thread.sleep(50);}
  throw new AssertionError("State condition timed out: "+state);
 }
 private JSONObject latest(JSONObject state)throws Exception {JSONArray tasks=state.optJSONArray("tasks");return tasks==null||tasks.length()==0?new JSONObject():tasks.getJSONObject(tasks.length()-1);}
 @Test public void delegatedFixtureKeepsMainResponsiveAndCompletesOnce()throws Exception {
  Context c=context();new CredentialStore(c).save(new JSONObject().put("authMode","scripted").put("endpoint","https://api.openai.com/v1").put("model","scripted").put("apiKey",""));
  c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(500);
  c.startForegroundService(new Intent(c,AgentService.class).setAction(AgentService.ACTION_START));
  long start=System.currentTimeMillis();RuntimeHost host=null;
  while(System.currentTimeMillis()-start<30000){host=AgentService.peekHost();if(host!=null){try{host.dispatch(new JSONObject().put("action","state")).get(2,TimeUnit.SECONDS);break;}catch(Exception ignored){}}Thread.sleep(100);}
  assertNotNull(host);RuntimeHost h=host;StateStore store=new StateStore(c);
  h.dispatch(new JSONObject().put("action","stop_all")).get(5,TimeUnit.SECONDS);
  await(store,s->!s.optBoolean("active"),10000);
  h.dispatch(new JSONObject().put("action","configure")).get(5,TimeUnit.SECONDS);
  int previous=store.load().optJSONArray("tasks")==null?0:store.load().getJSONArray("tasks").length();
  assertTrue(h.dispatch(new JSONObject().put("action","send").put("text","delegate long fixture delay=2000")).get(5,TimeUnit.SECONDS).optBoolean("ok"));
  JSONObject running=await(store,s->s.optJSONArray("tasks")!=null&&s.getJSONArray("tasks").length()>previous&&latest(s).optString("status").equals("running"),10000);
  String id=latest(running).getString("conversationId");int generation=latest(running).getInt("generation");
  h.dispatch(new JSONObject().put("action","send").put("text","hello while working")).get(5,TimeUnit.SECONDS);
  await(store,s->s.optJSONArray("messages")!=null&&s.getJSONArray("messages").toString().contains("Main is responsive: hello while working")&&latest(s).optString("status").equals("running"),2000);
  JSONObject finished=await(store,s->latest(s).optString("status").equals("completed")&&s.getJSONArray("messages").toString().contains("Worker finished:"),30000);
  assertEquals(id,latest(finished).getString("conversationId"));
  assertTrue(finished.toString(),finished.getJSONObject("workerMessages").getJSONArray(id).toString().contains("STATUS_CLICKED:PocketWorks COUNT:1"));
  int turns=finished.getInt("mainTurns");Thread.sleep(400);assertEquals(turns,store.load().getInt("mainTurns"));
  h.dispatch(new JSONObject().put("action","inspect_worker").put("conversationId",id)).get(5,TimeUnit.SECONDS);
  h.dispatch(new JSONObject().put("action","send").put("conversationId",id).put("text","Follow up delay=100")).get(5,TimeUnit.SECONDS);
  JSONObject resumed=await(store,s->latest(s).optInt("generation")==generation+1&&latest(s).optString("status").equals("completed"),5000);
  assertEquals(id,latest(resumed).getString("conversationId"));
  System.out.println("POCKET_ASYNC_VERIFIED="+new JSONObject().put("workerId",id).put("generation",generation).put("mainResponsive",true).put("completionOnce",true).put("nativeFixtureVerified",true).put("resumedGeneration",generation+1));
  h.dispatch(new JSONObject().put("action","stop_all")).get(5,TimeUnit.SECONDS);store.close();
 }
}
