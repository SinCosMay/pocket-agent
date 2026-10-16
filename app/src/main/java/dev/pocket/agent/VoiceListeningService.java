package dev.pocket.agent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.UUID;
import java.util.Arrays;
import org.json.JSONObject;

/**
 * The microphone belongs to this foreground service, not an Activity or assistant window.
 * The user explicitly launches it with the assistant gesture and can stop it from the
 * notification; it never silently resurrects microphone capture after process death.
 */
public final class VoiceListeningService extends Service implements AgentService.Observer {
    static final String ACTION_START = "dev.pocket.agent.VOICE_START";
    static final String ACTION_STOP = "dev.pocket.agent.VOICE_STOP";
    private static final int NOTIFICATION_ID = 32;
    private static final String CHANNEL = "voice";
    private static final long IDLE_TIMEOUT_MS = 6000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean active;
    static boolean isActive() { return active; }
    private DeepgramVoice voice;
    private String activeTurn;
    private boolean responding, userSpeaking, started, streamingReady, wakeStarted;
    private long utteranceAt, firstTokenAt;
    private final Runnable idleStop = () -> {
        if (started && !responding && !userSpeaking) {
            Log.i("PocketVoice", "No speech for " + IDLE_TIMEOUT_MS + " ms; stopping microphone");
            stopSelf();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Voice listening", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (started) {
            if (streamingReady && !responding && !userSpeaking) resetIdle();
            return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String key;
        try { key = new VoiceCredentialStore(this).read(); }
        catch (Exception error) { stopSelf(); return START_NOT_STICKY; }
        if (key.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification("Listening for your voice"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else startForeground(NOTIFICATION_ID, notification("Listening for your voice"));
        } catch (RuntimeException denied) {
            Log.w("PocketVoice", "Android denied microphone foreground service", denied);
            stopSelf();
            return START_NOT_STICKY;
        }
        WakeWordService.pauseForConversation();
        wakeStarted = intent.hasExtra(WakeWordService.EXTRA_WAKE_PREROLL);
        started = true;
        active = true;
        AgentService.observe(this);
        voice = new DeepgramVoice(this, key, new DeepgramVoice.Callback() {
            @Override public void onUtterance(String text) {
                main.post(() -> sendUtterance(text));
            }
            @Override public void onSpeechStarted() {
                main.post(VoiceListeningService.this::speechStarted);
            }
            @Override public void onEmptyTurn() {
                main.post(() -> {
                    if (!started) return;
                    userSpeaking = false;
                    if (!responding) {
                        showStatus("Listening for follow-up");
                        resetIdle();
                    }
                });
            }
            @Override public void onInterim(String text) {
                main.post(() -> {
                    if (started && !text.isBlank()) {
                        userSpeaking = true;
                        main.removeCallbacks(idleStop);
                    }
                });
            }
            @Override public void onFirstAudio() {
                main.post(() -> {
                    if (!started || !responding) return;
                    long firstAudioMs = SystemClock.elapsedRealtime() - utteranceAt;
                    Log.i("PocketVoiceLatency", "first_token_ms=" +
                        (firstTokenAt == 0 ? -1 : firstTokenAt - utteranceAt) +
                        " first_audio_ms=" + firstAudioMs);
                    showStatus("Speaking · listening for interruption");
                });
            }
            @Override public void onPlaybackFinished() {
                main.post(() -> {
                    if (!started || !responding) return;
                    responding = false;
                    activeTurn = null;
                    showStatus("Listening for follow-up");
                    if (!userSpeaking) resetIdle();
                });
            }
            @Override public void onStreamReady() {
                main.post(() -> {
                    if (!started) return;
                    streamingReady = true;
                    if (!responding && !userSpeaking) {
                        showStatus("Listening");
                        resetIdle();
                    }
                });
            }
            @Override public void onStatus(String status) {
                main.post(() -> {
                    if (!started) return;
                    showStatus(status);
                    if (status.startsWith("Microphone") || status.startsWith("Speech recognition")) stopSelf();
                });
            }
        });
        byte[] wakePreroll = intent.getByteArrayExtra(WakeWordService.EXTRA_WAKE_PREROLL);
        if (wakePreroll != null && wakePreroll.length > 0) {
            try { voice.primeWakePcm(wakePreroll); }
            catch (RuntimeException invalid) { Log.w("PocketVoice", "Invalid wake audio preroll", invalid); }
            Arrays.fill(wakePreroll, (byte) 0);
        }
        voice.start();
        startForegroundService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_START));
        return START_NOT_STICKY;
    }

    private Notification notification(String status) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 20,
            new Intent(this, VoiceListeningService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Pocket Agent · Microphone active")
            .setContentText(status).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "Stop listening", stop).build())
            .build();
    }

    private void showStatus(String status) {
        if (started) getSystemService(NotificationManager.class)
            .notify(NOTIFICATION_ID, notification(status));
    }

    private void resetIdle() {
        main.removeCallbacks(idleStop);
        main.postDelayed(idleStop, IDLE_TIMEOUT_MS);
    }

    private void speechStarted() {
        if (!started) return;
        userSpeaking = true;
        main.removeCallbacks(idleStop);
        if (responding) {
            String interruptedTurn = activeTurn;
            responding = false;
            activeTurn = null;
            voice.interruptReply();
            try {
                if (interruptedTurn != null) command(new JSONObject()
                    .put("action", "stop_main").put("voiceSessionId", interruptedTurn));
            }
            catch (Exception error) { Log.w("PocketVoice", "Could not abort previous response", error); }
            showStatus("Interrupted · listening to you");
        } else showStatus("Hearing you");
    }

    private void sendUtterance(String text) {
        if (!started || voice == null || text == null || text.isBlank()) return;
        if (wakeStarted) {
            text = text.replaceFirst("(?i)^\\s*hey[,]?\\s+jarvis[,!?.\\s]*", "").trim();
            if (text.isEmpty()) {
                userSpeaking = false;
                if (!responding) resetIdle();
                return;
            }
        }
        if (responding) speechStarted(); // Handle missing StartOfTurn before EndOfTurn.
        userSpeaking = false;
        main.removeCallbacks(idleStop);
        responding = true;
        activeTurn = UUID.randomUUID().toString();
        utteranceAt = SystemClock.elapsedRealtime();
        firstTokenAt = 0;
        voice.startReply();
        showStatus("Thinking · listening for interruption");
        try {
            command(new JSONObject().put("action", "send")
                .put("voiceSessionId", activeTurn).put("text", text));
        } catch (Exception failure) {
            responding = false;
            activeTurn = null;
            voice.interruptReply();
            showStatus("Unable to submit voice request");
            resetIdle();
        }
    }

    private void command(JSONObject command) {
        startService(new Intent(this, AgentService.class)
            .setAction(AgentService.ACTION_COMMAND).putExtra("command", command.toString()));
    }

    @Override public void onAgentEvent(JSONObject event) {
        if (!started || activeTurn == null || !activeTurn.equals(event.optString("sessionId"))) return;
        switch (event.optString("type")) {
            case "voice_delta":
                if (!responding || voice == null) return;
                if (firstTokenAt == 0) firstTokenAt = SystemClock.elapsedRealtime();
                voice.appendReply(event.optString("text"));
                break;
            case "voice_end":
                if (voice != null && responding) voice.finishReply();
                break;
            case "voice_error":
                if (voice != null && responding) voice.finishReply();
                showStatus("Speech request failed");
                break;
        }
    }

    @Override public void onDestroy() {
        started = false;
        wakeStarted = false;
        active = false;
        streamingReady = false;
        main.removeCallbacks(idleStop);
        AgentService.unobserve(this);
        if (voice != null) { voice.close(); voice = null; }
        WakeWordService.resumeAfterConversation(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
