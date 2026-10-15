package dev.pocket.agent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoicePipelineDeviceTest {
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
