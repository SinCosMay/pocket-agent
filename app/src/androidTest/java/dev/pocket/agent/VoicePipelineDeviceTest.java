package dev.pocket.agent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Intent;
import org.json.JSONObject;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoicePipelineDeviceTest {
    @Test public void agentSpeechIntentIsPrivateValidatedAndKeepsOptionalListening() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject event = new JSONObject().put("id", "f8fcb5b1-7f09-4d38-b28c-d07e7741ec89")
            .put("message", "Hello from a background task").put("listenAfter", false);
        VoiceAnnouncement item = VoiceAnnouncement.fromEvent(event);
        Intent intent = VoiceAnnouncement.intent(context, item);
        assertEquals(context.getPackageName(), intent.getComponent().getPackageName());
        assertEquals(VoiceListeningService.ACTION_ANNOUNCE, intent.getAction());
        VoiceAnnouncement restored = VoiceAnnouncement.fromIntent(intent);
        assertEquals("Hello from a background task", restored.message);
        assertFalse(restored.listenAfter);
        assertTrue(VoiceAnnouncement.fromEvent(new JSONObject().put("id", event.getString("id"))
            .put("message", "Follow up")).listenAfter);
        try {
            VoiceAnnouncement.fromEvent(new JSONObject().put("id", event.getString("id"))
                .put("message", " "));
            fail("Blank speech must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            VoiceAnnouncement.fromEvent(new JSONObject().put("id", "invalid")
                .put("message", "bad request"));
            fail("Invalid ids must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void fluxOnlyDispatchesConfirmedNonemptyTurns() throws Exception {
        assertNull(VoiceTranscript.completedTurn(new JSONObject().put("type", "Connected")));
        assertNull(VoiceTranscript.completedTurn(new JSONObject().put("type", "TurnInfo")
            .put("event", "Update").put("transcript", "I want")));
        assertNull(VoiceTranscript.completedTurn(new JSONObject().put("type", "TurnInfo")
            .put("event", "EagerEndOfTurn").put("transcript", "I want")));
        assertNull(VoiceTranscript.completedTurn(new JSONObject().put("type", "TurnInfo")
            .put("event", "EndOfTurn").put("transcript", "  ")));
        assertEquals("I want a reminder", VoiceTranscript.completedTurn(new JSONObject()
            .put("type", "TurnInfo").put("event", "EndOfTurn").put("transcript", " I want a reminder ")));
    }

    @Test public void emptyConfirmedTurnReleasesSpeechStateWithoutDispatch() throws Exception {
        JSONObject empty = new JSONObject().put("type", "TurnInfo")
            .put("event", "EndOfTurn").put("transcript", "   ");
        assertTrue(VoiceTranscript.emptyCompletedTurn(empty));
        assertNull(VoiceTranscript.completedTurn(empty));
        assertFalse(VoiceTranscript.emptyCompletedTurn(new JSONObject()
            .put("type", "TurnInfo").put("event", "Update").put("transcript", " ")));
        assertFalse(VoiceTranscript.emptyCompletedTurn(new JSONObject()
            .put("type", "TurnInfo").put("event", "EndOfTurn").put("transcript", "voice")));
    }

    @Test public void fluxBargeInOnlyOnConfirmedSpeechStart() throws Exception {
        assertFalse(VoiceTranscript.startedTurn(new JSONObject().put("type", "TurnInfo")
            .put("event", "Update").put("transcript", "hello")));
        assertFalse(VoiceTranscript.startedTurn(new JSONObject().put("type", "TurnInfo")
            .put("event", "EndOfTurn").put("transcript", "hello")));
        assertFalse(VoiceTranscript.startedTurn(new JSONObject().put("type", "Error")
            .put("event", "StartOfTurn")));
        assertTrue(VoiceTranscript.startedTurn(new JSONObject().put("type", "TurnInfo")
            .put("event", "StartOfTurn")));
    }

    @Test public void streamedReplyDoesNotSplitWordsAndDrainsTrailingText() {
        VoiceTextChunker chunker = new VoiceTextChunker();
        assertTrue(chunker.append("Here is your ans").isEmpty());
        assertEquals(List.of("Here is your answer."), chunker.append("wer. Next"));
        assertEquals("Next", chunker.drain());
        assertTrue(chunker.append("").isEmpty());
    }
}
