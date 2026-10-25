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
    @Test public void versionTwoArchiveGainsProgressWithoutDuplicatingHistory() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-v2-instrumentation.db";context.deleteDatabase(name);
        try {
            try(StateStore state=new StateStore(context,name)) {
                state.saveDurable(checkpoint("Legacy sapphire receipt.",199));
                state.getWritableDatabase().execSQL("DROP TABLE conversation_history_progress");
                state.getWritableDatabase().setVersion(2);
            }
            try(StateStore migrated=new StateStore(context,name)) {
                assertEquals(3,migrated.getReadableDatabase().getVersion());
                assertEquals(1,migrated.searchHistory("sapphire",10).length());
                migrated.saveDurable(migrated.loadDurable());
                assertEquals(1,migrated.searchHistory("sapphire",10).length());
            }
        } finally {context.deleteDatabase(name);}
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
                assertEquals(3,migrated.getReadableDatabase().getVersion());
                assertEquals(old.toString(),migrated.loadDurable().toString());
                assertEquals(7,migrated.load().getInt("mainTurns"));
                assertEquals(1,migrated.recentEvents().length());
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
            catch(android.database.sqlite.SQLiteConstraintException expected) {assertTrue(expected.getMessage().contains("synthetic checkpoint failure"));}
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
            assertEquals(0,state.searchHistory("the",10).length());
            assertEquals(0,state.searchHistory("!!!",10).length());
            assertEquals("Fresh basalt thread.",state.loadDurable().getJSONArray("mainMessages").getJSONObject(0).getString("content"));
        }
        try(StateStore reopened=new StateStore(context,name)) {
            JSONArray found=reopened.searchHistory("motherboard",10);
            assertEquals(1,found.length());
            assertEquals("main",found.getJSONObject(0).getString("owner"));
            assertEquals(4,reopened.searchHistory("basalt",10).length());
        } finally {context.deleteDatabase(name);}
    }
    @Test public void longEscapedMessagesPageWithoutLossAndByteSavesSkipUnrelatedPayloads() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-page-instrumentation.db";context.deleteDatabase(name);
        String text="start\u0000"+"\u0001😀".repeat(18000);
        JSONObject saved=checkpoint(text,401).put("unrelated",new JSONObject().put("large","x".repeat(1600000)));
        try(StateStore state=new StateStore(context,name)) {
            state.saveDurableBytes(saved.toString().getBytes(StandardCharsets.UTF_8));
            long id=state.searchHistory("",10).getJSONObject(0).getLong("id");
            StringBuilder joined=new StringBuilder();int offset=0;
            while(true) {
                JSONObject page=state.readHistory(id,"main",offset,4000);
                assertTrue("Escaped page must leave room in the tool envelope",page.toString().length()<31000);
                joined.append(page.getString("text"));
                if(page.isNull("nextOffset"))break;
                assertTrue(page.getInt("nextOffset")>offset);offset=page.getInt("nextOffset");
            }
            assertEquals(text.trim(),joined.toString());
            assertEquals(1600000,state.loadDurable().getJSONObject("unrelated").getString("large").length());
        } finally {context.deleteDatabase(name);}
    }
    @Test public void shutdownNeutralizationDoesNotDependOnArchiveHealth() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-shutdown-instrumentation.db";context.deleteDatabase(name);
        try(StateStore state=new StateStore(context,name)) {
            JSONObject saved=checkpoint("Preserve the topaz transcript.",501).put("busy",true)
                .put("inbox",new JSONArray().put(new JSONObject().put("type","user").put("text","pending")));
            state.saveDurable(saved);
            state.getWritableDatabase().execSQL("CREATE TRIGGER reject_archive BEFORE INSERT ON conversation_history BEGIN SELECT RAISE(ABORT,'synthetic archive failure'); END");
            state.markDurableInterrupted();
            JSONObject neutralized=state.loadDurable();
            assertFalse(neutralized.getBoolean("busy"));assertTrue(neutralized.getBoolean("mainPaused"));
            assertEquals(0,neutralized.getJSONArray("inbox").length());
            assertEquals(saved.getJSONArray("mainMessages").toString(),neutralized.getJSONArray("mainMessages").toString());
            assertEquals(1,state.searchHistory("topaz",10).length());
        } finally {context.deleteDatabase(name);}
    }
    @Test public void archiveCursorSkipsOldWritesAndInvalidatesAfterTranscriptTrimming() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-progress-instrumentation.db";context.deleteDatabase(name);
        try(StateStore state=new StateStore(context,name)) {
            JSONObject saved=checkpoint("First emerald receipt.",601);state.saveDurable(saved);
            state.getWritableDatabase().execSQL("CREATE TABLE archive_attempts(n INTEGER)");
            state.getWritableDatabase().execSQL("INSERT INTO archive_attempts VALUES(0)");
            state.getWritableDatabase().execSQL("CREATE TRIGGER count_archive BEFORE INSERT ON conversation_history BEGIN UPDATE archive_attempts SET n=n+1; END");
            state.saveDurable(saved);
            try(android.database.Cursor count=state.getReadableDatabase().rawQuery("SELECT n FROM archive_attempts",null)){assertTrue(count.moveToFirst());assertEquals(0,count.getInt(0));}
            saved.getJSONArray("mainMessages").put(new JSONObject().put("role","assistant").put("timestamp",602).put("content","Second emerald receipt."));
            state.saveDurable(saved);
            try(android.database.Cursor count=state.getReadableDatabase().rawQuery("SELECT n FROM archive_attempts",null)){assertTrue(count.moveToFirst());assertEquals(1,count.getInt(0));}
            saved.getJSONArray("mainMessages").remove(0);state.saveDurable(saved);
            assertEquals(2,state.searchHistory("emerald",10).length());
            saved.put("workerMessages",new JSONObject().put("abc",new JSONArray().put(new JSONObject().put("role","assistant").put("timestamp",603).put("content","Private garnet receipt."))))
                .put("workerHistoryOwners",new JSONObject().put("abc","abc@first"));
            state.saveDurable(saved);
            assertEquals(1,state.searchHistory("garnet",10,"abc@first").length());
            assertEquals(0,state.searchHistory("garnet",10,"abc@second").length());
        } finally {context.deleteDatabase(name);}
    }
}
