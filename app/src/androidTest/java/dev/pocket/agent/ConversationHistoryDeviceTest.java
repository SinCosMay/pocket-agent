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
    private JSONObject checkpoint(String text,long timestamp) throws Exception {
        return new JSONObject().put("mainThreadId","33333333-3333-4333-8333-333333333333")
            .put("mainMessages",new JSONArray().put(new JSONObject().put("role","user")
                .put("timestamp",timestamp).put("content",text)));
    }
    @Test public void versionOneMigrationPreservesAndArchivesExistingCheckpoint() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-migration-instrumentation.db";context.deleteDatabase(name);
        JSONObject old=checkpoint("Legacy obsidian warranty receipt.",201);old.remove("mainThreadId");
        try {
            try(android.database.sqlite.SQLiteOpenHelper legacy=new android.database.sqlite.SQLiteOpenHelper(context,name,null,1) {
                @Override public void onCreate(android.database.sqlite.SQLiteDatabase db) {
                    db.execSQL("CREATE TABLE snapshot (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)");
                    db.execSQL("CREATE TABLE runtime_state (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)");
                    db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, json TEXT NOT NULL)");
                }
                @Override public void onUpgrade(android.database.sqlite.SQLiteDatabase db,int from,int to) {throw new AssertionError();}
            }) {
                legacy.getWritableDatabase().execSQL("INSERT INTO runtime_state VALUES(1,?)",new Object[]{old.toString()});
                legacy.getWritableDatabase().execSQL("INSERT INTO snapshot VALUES(1,?)",new Object[]{"{\"mainTurns\":7}"});
                legacy.getWritableDatabase().execSQL("INSERT INTO events(at,json) VALUES(1,?)",new Object[]{"{\"type\":\"ready\"}"});
            }
            try(StateStore migrated=new StateStore(context,name)) {
                assertEquals(2,migrated.getReadableDatabase().getVersion());
                assertEquals(old.toString(),migrated.loadDurable().toString());
                assertEquals(7,migrated.load().getInt("mainTurns"));
                assertEquals(1,migrated.recentEvents().length());
                migrated.saveDurable(migrated.loadDurable());
                JSONArray hits=migrated.searchHistory("obsidian",10);
                assertEquals(1,hits.length());
                assertEquals("00000000-0000-4000-8000-000000000000",hits.getJSONObject(0).getString("threadId"));
            }
        } finally {context.deleteDatabase(name);}
    }
    @Test public void failedCheckpointRollsBackArchiveAndSearchIndex() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-rollback-instrumentation.db";context.deleteDatabase(name);
        try(StateStore state=new StateStore(context,name)) {
            JSONObject original=checkpoint("Committed quartz receipt.",301);state.saveDurable(original);
            state.getWritableDatabase().execSQL("CREATE TRIGGER reject_checkpoint BEFORE INSERT ON runtime_state BEGIN SELECT RAISE(ABORT,'synthetic checkpoint failure'); END");
            try {state.saveDurable(checkpoint("Uncommitted zircon receipt.",302));fail("Failed checkpoint was accepted");}
            catch(RuntimeException expected) {}
            assertEquals(original.toString(),state.loadDurable().toString());
            assertEquals(1,state.searchHistory("",10).length());
            assertEquals(0,state.searchHistory("zircon",10).length());
            state.getWritableDatabase().execSQL("DROP TRIGGER reject_checkpoint");
            state.saveDurable(checkpoint("Uncommitted zircon receipt.",302));
            assertEquals(1,state.searchHistory("zircon",10).length());
        } finally {context.deleteDatabase(name);}
    }
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
