package dev.pocket.agent;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

/**
 * Half-duplex Deepgram transport. Pi remains the sole conversation/decision engine.
 * The microphone runs only while the system assistant window is visible and listening.
 */
final class DeepgramVoice implements AutoCloseable {
    interface Callback {
        void onUtterance(String text);
        void onInterim(String text);
        void onPlaybackFinished();
        void onFirstAudio();
        void onStatus(String message);
    }

    private static final String STT_URL = "wss://api.deepgram.com/v2/listen?model=flux-general-en&encoding=linear16&sample_rate=16000";
    private static final String TTS_URL = "wss://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=linear16&sample_rate=24000";
    private final Context context;
    private final Callback callback;
    private final OkHttpClient client;
    private final String key;
    private final ExecutorService playerQueue = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PocketVoiceOutput");
        thread.setDaemon(true);
        return thread;
    });
    private final VoiceTextChunker chunker = new VoiceTextChunker();
    private final ArrayDeque<String> speechQueue = new ArrayDeque<>();
    private volatile boolean closed;
    private volatile boolean listening;
    private volatile boolean responseOpen;
    private WebSocket sttSocket, ttsSocket;
    private boolean ttsReady, waitingForFlush, hasSpeech, ttsFailed, firstAudio;
    private AudioRecord microphone;
    private AudioTrack speaker;
    private long totalFrames;
    private int pendingAudioBytes;

    DeepgramVoice(Context context, String key, Callback callback) {
        this.context = context.getApplicationContext();
        this.key = key;
        this.callback = callback;
        this.client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(12, TimeUnit.SECONDS).build();
    }

    void start() {
        if (closed) return;
        connectSpeaker();
        listen();
    }

    private Request request(String url) {
        return new Request.Builder().url(url).header("Authorization", "Token " + key).build();
    }

    private void connectSpeaker() {
        ttsSocket = client.newWebSocket(request(TTS_URL), new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                synchronized (DeepgramVoice.this) {
                    if (closed) { webSocket.close(1000, "closed"); return; }
                    ttsReady = true;
                    while (!speechQueue.isEmpty()) webSocket.send(speechQueue.removeFirst());
                }
            }
            @Override public void onMessage(WebSocket webSocket, ByteString bytes) { handleAudio(bytes.toByteArray()); }
            @Override public void onMessage(WebSocket webSocket, String text) {
                try {
                    if ("Flushed".equals(new JSONObject(text).optString("type"))) finishPlayback();
                } catch (Exception ignored) { }
            }
            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                if (!closed) {
                    callback.onStatus("Speech output unavailable; check Deepgram credentials or network");
                    boolean notify;
                    synchronized (DeepgramVoice.this) {
                        ttsReady = false; ttsFailed = true;
                        notify = waitingForFlush;
                        waitingForFlush = false;
                    }
                    if (notify) callback.onPlaybackFinished();
                }
            }
        });
    }

    synchronized void listen() {
        if (closed || listening || responseOpen || waitingForFlush) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callback.onStatus("Microphone permission required. Open Pocket Agent settings.");
            return;
        }
        listening = true;
        sttSocket = client.newWebSocket(request(STT_URL), new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                if (!closed && listening) startMicrophone(socket);
                else socket.close(1000, "not listening");
            }
            @Override public void onMessage(WebSocket socket, String text) {
                if (!listening || closed) return;
                try {
                    JSONObject packet = new JSONObject(text);
                    String finalText = VoiceTranscript.completedTurn(packet);
                    if (finalText != null) {
                        stopListening();
                        callback.onUtterance(finalText);
                    } else if ("TurnInfo".equals(packet.optString("type"))) {
                        callback.onInterim(packet.optString("transcript", ""));
                    } else if ("Error".equals(packet.optString("type"))) {
                        stopListening(); callback.onStatus("Speech recognition stopped; check Deepgram or the network");
                    }
                } catch (Exception ignored) { }
            }
            @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                if (!closed && listening) { stopListening(); callback.onStatus("Speech recognition unavailable; check network and Deepgram key"); }
            }
        });
        callback.onStatus("Listening");
    }

    private void startMicrophone(WebSocket socket) {
        try {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Microphone permission revoked");
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Microphone unsupported");
            AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 4096));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release(); throw new IllegalStateException("Microphone unavailable");
            }
            synchronized (this) {
                if (closed || !listening) { record.release(); return; }
                microphone = record;
            }
            record.startRecording();
            Thread capture = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                byte[] samples = new byte[2560]; // Flux recommends 80ms of 16 kHz mono PCM16.
                while (listening && !closed) {
                    int size = record.read(samples, 0, samples.length);
                    if (size <= 0) { if (listening) callback.onStatus("Microphone capture interrupted"); break; }
                    if (socket.queueSize() > 256_000) {
                        callback.onStatus("Speech connection is too slow");
                        break;
                    }
                    if (!socket.send(ByteString.of(Arrays.copyOf(samples, size)))) break;
                }
                stopListening();
            }, "PocketVoiceMicrophone");
            capture.setDaemon(true);
            capture.start();
        } catch (Exception error) {
            stopListening();
            if (!closed) callback.onStatus("Microphone is unavailable");
        }
    }

    synchronized void stopListening() {
        if (!listening && microphone == null && sttSocket == null) return;
        listening = false;
        AudioRecord record = microphone;
        microphone = null;
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) { }
            record.release();
        }
        WebSocket socket = sttSocket;
        sttSocket = null;
        if (socket != null) socket.close(1000, "utterance complete");
    }

    synchronized void startReply() {
        if (closed) return;
        stopListening();
        chunker.clear();
        responseOpen = true;
        waitingForFlush = false;
        totalFrames = 0;
        hasSpeech = false;
        firstAudio = false;
    }

    synchronized void appendReply(String text) {
        if (!responseOpen || closed) return;
        for (String clause : chunker.append(text)) sendSpeech(clause);
    }

    private void sendSpeech(String text) {
        if (text.isEmpty()) return;
        hasSpeech = true;
        try { queueTts(new JSONObject().put("type", "Speak").put("text", text).toString()); }
        catch (Exception error) { callback.onStatus("Unable to synthesize this response"); }
    }

    private void queueTts(String json) {
        if (closed) return;
        if (ttsReady && ttsSocket != null) ttsSocket.send(json);
        else if (speechQueue.size() < 64) speechQueue.addLast(json);
        else callback.onStatus("Speech synthesis queue full");
    }

    synchronized void finishReply() {
        if (!responseOpen || closed) return;
        responseOpen = false;
        String tail = chunker.drain();
        if (!tail.isEmpty()) sendSpeech(tail);
        if (!hasSpeech || ttsFailed) {
            // Tool-only turns have no spoken text. Avoid waiting for a nonexistent flush.
            callback.onPlaybackFinished();
            return;
        }
        waitingForFlush = true;
        queueTts("{\"type\":\"Flush\"}");
    }

    private void handleAudio(byte[] audio) {
        if (closed || (!waitingForFlush && !responseOpen)) return;
        synchronized (this) {
            if (!firstAudio) { firstAudio = true; callback.onFirstAudio(); }
        }
        synchronized (this) {
            if (pendingAudioBytes + audio.length > 1_000_000) {
                callback.onStatus("Speech output buffer overrun");
                return;
            }
            pendingAudioBytes += audio.length;
        }
        playerQueue.execute(() -> {
            try {
                if (closed) return;
                synchronized (DeepgramVoice.this) {
                    if (speaker == null) {
                        speaker = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(24000)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(24000)
                            .setTransferMode(AudioTrack.MODE_STREAM).build();
                        if (speaker.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("Speaker unavailable");
                        speaker.play();
                    }
                }
                int offset = 0;
                while (offset < audio.length && !closed) {
                    int count = speaker.write(audio, offset, audio.length - offset, AudioTrack.WRITE_BLOCKING);
                    if (count <= 0) break;
                    offset += count;
                    totalFrames += count / 2;
                }
            } catch (Exception error) {
                if (!closed) callback.onStatus("Speech playback failed");
            } finally {
                synchronized (DeepgramVoice.this) { pendingAudioBytes -= audio.length; }
            }
        });
    }

    private void finishPlayback() {
        playerQueue.execute(() -> {
            AudioTrack track;
            long expected;
            synchronized (this) { if (closed || !waitingForFlush) return; track = speaker; expected = totalFrames; }
            if (track != null) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!closed && Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < expected
                    && System.nanoTime() < deadline) {
                    try { Thread.sleep(20); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
                synchronized (this) {
                    if (speaker == track) {
                        try { track.stop(); } catch (Exception ignored) { }
                        track.release(); speaker = null;
                    }
                }
            }
            synchronized (this) { if (closed) return; waitingForFlush = false; }
            callback.onPlaybackFinished();
        });
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        stopListening();
        if (ttsSocket != null) { ttsSocket.cancel(); ttsSocket = null; }
        ttsReady = false;
        speechQueue.clear();
        playerQueue.shutdownNow();
        if (speaker != null) { speaker.pause(); speaker.flush(); speaker.release(); speaker = null; }
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
