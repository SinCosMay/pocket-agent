package dev.pocket.agent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoicePipelineDeviceTest {
    @Test public void collectsFinalizedSegmentsOnlyOnce() throws Exception {
        VoiceTranscript transcript = new VoiceTranscript();
        assertNull(transcript.accept(new JSONObject("{\"type\":\"Results\",\"is_final\":false,\"channel\":{\"alternatives\":[{\"transcript\":\"I want\"}]}}")));
        assertNull(transcript.accept(new JSONObject("{\"type\":\"Results\",\"is_final\":true,\"channel\":{\"alternatives\":[{\"transcript\":\"I want\"}]}}")));
        assertEquals("I want a reminder", transcript.accept(new JSONObject("{\"type\":\"Results\",\"is_final\":true,\"speech_final\":true,\"channel\":{\"alternatives\":[{\"transcript\":\"a reminder\"}]}}")));
        assertNull(transcript.accept(new JSONObject("{\"type\":\"Results\",\"speech_final\":true,\"channel\":{\"alternatives\":[{\"transcript\":\"\"}]}}")));
    }

    @Test public void streamedReplyDoesNotSplitWordsAndDrainsTrailingText() {
        VoiceTextChunker chunker = new VoiceTextChunker();
        assertTrue(chunker.append("Here is your ans").isEmpty());
        assertEquals(List.of("Here is your answer."), chunker.append("wer. Next"));
        assertEquals("Next", chunker.drain());
        assertTrue(chunker.append("").isEmpty());
    }
}
