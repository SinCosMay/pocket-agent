package dev.pocket.agent;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class InstantVoiceCaptureDeviceTest {
    @Test public void queuedAudioRetainsFirstFrameOrderAndClearsAfterStop() {
        PcmCaptureQueue frames = new PcmCaptureQueue(6);
        byte[] first = {1, 2, 3};
        assertTrue(frames.offer(first, 3));
        first[0] = 9;
        assertTrue(frames.offer(new byte[]{4, 5}, 2));
        assertFalse(frames.offer(new byte[]{6, 7}, 2));
        assertArrayEquals(new byte[]{1, 2, 3}, frames.peek());
        frames.removeFirst();
        assertArrayEquals(new byte[]{4, 5}, frames.peek());
        assertEquals(2, frames.queuedBytes());
        frames.clear();
        assertEquals(0, frames.queuedBytes());
        assertTrue(frames.isEmpty());
        assertTrue(frames.offer(new byte[]{8}, 1));
        assertArrayEquals(new byte[]{8}, frames.peek());
    }

    @Test public void microphoneStartsBeforeBackendAndBacklogReplaysWhenBackendOpens() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        requireMicrophonePermission(context);
        FakeConnector network = new FakeConnector();
        DeepgramVoice voice = new DeepgramVoice(context, "test-only", new SilentCallback(),
            network, "wss://api.deepgram.com/v2/listen");
        network.voice = voice;
        try {
            voice.listen();
            assertTrue("AudioRecord must already be recording when socket is requested", network.wasRecordingAtConnect);
            assertTrue("Microphone must not wait for network", voice.isCapturing());
            waitUntil(() -> voice.bufferedInputBytes() >= 5120, 5000);
            int bufferedBeforeOpen = voice.bufferedInputBytes();
            assertTrue("Audio is retained while backend is disconnected", bufferedBeforeOpen >= 5120);
            assertEquals(0, network.socket.audio.size());
            // Inject a unique frame into the offline capture backlog. Counting sent bytes
            // alone would incorrectly pass if everything before onOpen were discarded.
            byte[] offlineMarker = new byte[2560];
            for (int i = 0; i < offlineMarker.length; i++) {
                offlineMarker[i] = (byte) (i * 31 + 17);
            }
            java.lang.reflect.Field queueField = DeepgramVoice.class.getDeclaredField("captured");
            queueField.setAccessible(true);
            synchronized (voice) {
                PcmCaptureQueue queued = (PcmCaptureQueue) queueField.get(voice);
                assertTrue("Marker must be buffered before connecting",
                    queued.offer(offlineMarker, offlineMarker.length));
            }
            network.accept();
            waitUntil(() -> network.socket.sentBytes.get() >= bufferedBeforeOpen + offlineMarker.length, 5000);
            boolean replayedOfflineMarker = false;
            synchronized (network.socket.audio) {
                for (byte[] frame : network.socket.audio) {
                    assertTrue(frame.length > 0 && frame.length <= 2560);
                    if (java.util.Arrays.equals(frame, offlineMarker)) replayedOfflineMarker = true;
                }
            }
            assertTrue("The unique pre-connection PCM frame must be replayed",
                replayedOfflineMarker);
            voice.stopListening();
            assertEquals("Stop discards stale audio", 0, voice.bufferedInputBytes());
            int sent = network.socket.audio.size();
            network.accept(); // Late or duplicate callbacks must not replay stale sessions.
            Thread.sleep(120);
            assertEquals(sent, network.socket.audio.size());
        } finally {
            voice.close();
        }
    }

    @Test public void failedHandshakeRetriesWithoutLosingRecordedAudio() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        requireMicrophonePermission(context);
        FakeConnector network = new FakeConnector();
        DeepgramVoice voice = new DeepgramVoice(context, "test-only", new SilentCallback(),
            network, "wss://api.deepgram.com/v2/listen");
        network.voice = voice;
        try {
            voice.listen();
            waitUntil(() -> voice.bufferedInputBytes() > 2560, 5000);
            int recordedBeforeFailure = voice.bufferedInputBytes();
            network.fail();
            assertTrue("Capture must continue across failed handshakes", voice.isCapturing());
            assertTrue(voice.bufferedInputBytes() >= recordedBeforeFailure);
            waitUntil(() -> network.connections.get() >= 2, 5000);
            network.accept();
            waitUntil(() -> network.socket.sentBytes.get() >= recordedBeforeFailure, 5000);
            assertTrue(voice.isCapturing());
        } finally {
            voice.close();
        }
    }

    @Test public void streamReadinessWaitsForOutgoingSocketToDrain() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        requireMicrophonePermission(context);
        FakeConnector network = new FakeConnector();
        SilentCallback callback = new SilentCallback();
        DeepgramVoice voice = new DeepgramVoice(context, "test-only", callback,
            network, "wss://api.deepgram.com/v2/listen");
        network.voice = voice;
        try {
            voice.listen();
            waitUntil(() -> voice.bufferedInputBytes() >= 5120, 5000);
            network.socket.forcedQueueSize = 64_000;
            network.accept();
            waitUntil(() -> network.socket.sentBytes.get() >= 5120, 5000);
            Thread.sleep(160);
            assertEquals("Socket still holds undelivered PCM: idle timer must not start",
                0, callback.readyCount.get());
            network.socket.forcedQueueSize = 0;
            waitUntil(() -> callback.readyCount.get() > 0, 5000);
        } finally {
            voice.close();
        }
    }

    @Test public void cancelBeforeConnectionDiscardsAudioAndRejectsLateOpen() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        requireMicrophonePermission(context);
        FakeConnector network = new FakeConnector();
        DeepgramVoice voice = new DeepgramVoice(context, "test-only", new SilentCallback(),
            network, "wss://api.deepgram.com/v2/listen");
        network.voice = voice;
        try {
            voice.listen();
            waitUntil(() -> voice.bufferedInputBytes() > 0, 5000);
            voice.close();
            assertEquals(0, voice.bufferedInputBytes());
            network.accept();
            assertTrue(network.socket.closed);
            assertEquals(0, network.socket.audio.size());
        } finally {
            voice.close();
        }
    }

    private static void requireMicrophonePermission(Context context) {
        assertEquals("Grant microphone permission in emulator setup before running these tests; " +
            "instrumentation must never change the app's persistent permission state",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
    }

    private static void waitUntil(Check check, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        while (!check.ok() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue("Timed out waiting for audio pipeline", check.ok());
    }

    private interface Check { boolean ok(); }

    private static final class FakeConnector implements DeepgramVoice.SocketConnector {
        volatile FakeSocket socket = new FakeSocket();
        final AtomicInteger connections = new AtomicInteger();
        WebSocketListener listener;
        Request request;
        boolean wasRecordingAtConnect;
        DeepgramVoice voice;

        @Override public WebSocket open(Request request, WebSocketListener listener) {
            this.listener = listener;
            this.request = request;
            this.socket = new FakeSocket();
            connections.incrementAndGet();
            wasRecordingAtConnect = voice != null && voice.isCapturing();
            return socket;
        }

        void accept() {
            listener.onOpen(socket, new Response.Builder().request(request)
                .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build());
        }

        void fail() {
            listener.onFailure(socket, new IOException("Simulated offline backend"), null);
        }
    }

    private static final class FakeSocket implements WebSocket {
        final List<byte[]> audio = java.util.Collections.synchronizedList(new ArrayList<>());
        final AtomicLong sentBytes = new AtomicLong();
        volatile boolean closed;
        volatile long forcedQueueSize;
        @Override public Request request() { return new Request.Builder()
            .url("https://api.deepgram.com").build(); }
        @Override public long queueSize() { return forcedQueueSize; }
        @Override public boolean send(String text) { return !closed; }
        @Override public boolean send(ByteString bytes) {
            if (closed) return false;
            audio.add(bytes.toByteArray());
            sentBytes.addAndGet(bytes.size());
            return true;
        }
        @Override public boolean close(int code, String reason) { closed = true; return true; }
        @Override public void cancel() { closed = true; }
    }

    private static final class SilentCallback implements DeepgramVoice.Callback {
        final AtomicInteger readyCount = new AtomicInteger();
        @Override public void onUtterance(String text) { }
        @Override public void onSpeechStarted() { }
        @Override public void onEmptyTurn() { }
        @Override public void onInterim(String text) { }
        @Override public void onPlaybackFinished() { }
        @Override public void onFirstAudio() { }
        @Override public void onStatus(String message) { }
        @Override public void onStreamReady() { readyCount.incrementAndGet(); }
    }
}
