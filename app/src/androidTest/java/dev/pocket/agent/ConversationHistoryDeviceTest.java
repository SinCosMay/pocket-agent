package dev.pocket.agent;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ConversationHistoryDeviceTest {
    @Test public void mainAndWorkerHistorySurviveResetByteCheckpointAndReopen() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-instrumentation.db";context.deleteDatabase(name);
        JSONObject user=new JSONObject().put("role","user").put("timestamp",101).put("content",
            new JSONArray().put(new JSONObject().put("type","text").put("text","Discuss the basalt motherboard return.")));
        JSONObject assistant=new JSONObject().put("role","assistant").put("timestamp",102).put("content",
            new JSONArray().put(new JSONObject().put("type","text").put("text","The basalt warranty receipt is ready.")));
        JSONObject worker=new JSONObject().put("role","assistant").put("timestamp",103).put("content",
            new JSONArray().put(new JSONObject().put("type","text").put("text","Verified the basalt receipt in downloads.")));
        JSONObject checkpoint=new JSONObject().put("mainThreadId","11111111-1111-4111-8111-111111111111")
            .put("mainMessages",new JSONArray().put(user).put(assistant))
            .put("workerMessages",new JSONObject().put("worker-1",new JSONArray().put(worker)));
        try(StateStore state=new StateStore(context,name)) {
            state.saveDurable(checkpoint);
            state.saveDurable(checkpoint);
            assertEquals(3,state.searchHistory("basalt",10).length());
            assertEquals(1,state.searchHistory("downloads",10).length());
            assertEquals(1,state.searchHistory("downloads",10,"worker-1").length());
            assertEquals(0,state.searchHistory("downloads",10,"worker-2").length());
            assertEquals(2,state.searchHistory("basalt",10,"worker-2").length());
            JSONObject next=new JSONObject().put("mainThreadId","22222222-2222-4222-8222-222222222222")
                .put("mainMessages",new JSONArray().put(new JSONObject().put("role","user")
                    .put("timestamp",104).put("content","Fresh basalt thread.")));
            state.saveDurableBytes(next.toString().getBytes(StandardCharsets.UTF_8));
            assertEquals(4,state.searchHistory("basalt",10).length());
            assertEquals(1,state.searchHistory("warranty",10).length());
            long id=state.searchHistory("downloads",10).getJSONObject(0).getLong("id");
            assertTrue(state.readHistory(id,"worker-1").getString("text").contains("downloads"));
            assertEquals(0,state.readHistory(id,"worker-2").length());
            assertEquals(4,state.searchHistory("",10).length());
            assertEquals("Fresh basalt thread.",state.loadDurable().getJSONArray("mainMessages").getJSONObject(0).getString("content"));
        }
        try(StateStore reopened=new StateStore(context,name)) {
            JSONArray found=reopened.searchHistory("motherboard",10);
            assertEquals(1,found.length());
            assertEquals("main",found.getJSONObject(0).getString("owner"));
            assertEquals(4,reopened.searchHistory("basalt",10).length());
        } finally {context.deleteDatabase(name);}
    }
}
