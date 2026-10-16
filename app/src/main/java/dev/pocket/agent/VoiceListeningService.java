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
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.json.JSONObject;

/** User-started assistant capture plus on-demand announcements from the running agent. */
public final class VoiceListeningService extends Service implements AgentService.Observer {
    static final String ACTION_START = "dev.pocket.agent.VOICE_START";
    static final String ACTION_STOP = "dev.pocket.agent.VOICE_STOP";
    static final String ACTION_ANNOUNCE = "dev.pocket.agent.VOICE_ANNOUNCE";
    private static final int NOTIFICATION_ID = 32;
    private static final String CHANNEL = "voice";
    private static final long IDLE_TIMEOUT_MS = 3000;
    private static final int MAX_PENDING_ANNOUNCEMENTS = 8;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<VoiceAnnouncement> pendingAnnouncements = new ArrayDeque<>();
    private final LinkedHashSet<String> acceptedIds = new LinkedHashSet<>();
    private DeepgramVoice voice;
    private VoiceAnnouncement currentAnnouncement;
    private String activeTurn;
    private boolean responding, userSpeaking, started, keepListening, microphoneEnabled;
    private long utteranceAt, firstTokenAt;
    private final Runnable idleStop = () -> {
        if (started && !responding && !userSpeaking && pendingAnnouncements.isEmpty()) {
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
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        if (!ACTION_START.equals(action) && !ACTION_ANNOUNCE.equals(action)) return START_NOT_STICKY;
        VoiceAnnouncement request = null;
        if (ACTION_ANNOUNCE.equals(action)) {
            try { request = VoiceAnnouncement.fromIntent(intent); }
            catch (RuntimeException invalid) { Log.w("PocketVoice", "Invalid speech request", invalid); return START_NOT_STICKY; }
            if (acceptedIds.contains(request.id)) return START_NOT_STICKY;
            if (pendingAnnouncements.size() >= MAX_PENDING_ANNOUNCEMENTS) {
                Log.w("PocketVoice", "Announcement queue is full; rejecting request");
                return START_NOT_STICKY;
            }
            acceptedIds.add(request.id);
            if (acceptedIds.size() > 64) acceptedIds.remove(acceptedIds.iterator().next());
        }
        if (!started && !startOutput()) return START_NOT_STICKY;
        if (ACTION_START.equals(action)) {
            if (!enableMicrophone()) { stopSelf(); return START_NOT_STICKY; }
            keepListening = true;
            if (!responding && !userSpeaking) resetIdle();
        } else {
            if (request.listenAfter && enableMicrophone()) keepListening = true;
            pendingAnnouncements.addLast(request);
            main.removeCallbacks(idleStop);
            playNextAnnouncement();
        }
        return START_NOT_STICKY;
    }

    private boolean startOutput() {
        String key;
        try { key = new VoiceCredentialStore(this).read(); }
        catch (Exception error) { stopSelf(); return false; }
        if (key.isEmpty()) {
            Log.w("PocketVoice", "Speech requires configured Deepgram credentials");
            stopSelf(); return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification("Preparing speech"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION_ID, notification("Preparing speech"));
        } catch (RuntimeException denied) {
            Log.w("PocketVoice", "Android denied speech foreground service", denied);
            stopSelf(); return false;
        }
        started = true;
        AgentService.observe(this);
        if (!AgentService.isRunning())
            startForegroundService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_START));
        voice = new DeepgramVoice(this, key, new DeepgramVoice.Callback() {
            @Override public void onUtterance(String text) { main.post(() -> sendUtterance(text)); }
            @Override public void onSpeechStarted() { main.post(VoiceListeningService.this::speechStarted); }
            @Override public void onEmptyTurn() {
                main.post(() -> {
                    if (!started) return;
                    userSpeaking = false;
                    if (!responding) afterPlayback();
                });
            }
            @Override public void onInterim(String text) {
                main.post(() -> {
                    if (started && !text.isBlank()) { userSpeaking = true; main.removeCallbacks(idleStop); }
                });
            }
            @Override public void onFirstAudio() {
                main.post(() -> {
                    if (!started || !responding) return;
                    if (currentAnnouncement == null) {
                        long firstAudioMs = SystemClock.elapsedRealtime() - utteranceAt;
                        Log.i("PocketVoiceLatency", "first_token_ms=" +
                            (firstTokenAt == 0 ? -1 : firstTokenAt - utteranceAt) +
                            " first_audio_ms=" + firstAudioMs);
                    }
                    showStatus(microphoneEnabled ? "Speaking · listening for interruption" : "Speaking");
                });
            }
            @Override public void onPlaybackFinished() {
                main.post(() -> {
                    if (!started || !responding) return;
                    responding = false;
                    activeTurn = null;
                    currentAnnouncement = null;
                    afterPlayback();
                });
            }
            @Override public void onStatus(String status) {
                main.post(() -> {
                    if (!started) return;
                    showStatus(status);
                    if (status.startsWith("Microphone") || status.startsWith("Speech recognition")) {
                        microphoneEnabled = false;
                        userSpeaking = false;
                        keepListening = false;
                        if (!responding) afterPlayback();
                    }
                });
            }
        });
        voice.startOutput();
        return true;
    }

    private boolean enableMicrophone() {
        if (microphoneEnabled) return true;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("PocketVoice", "Cannot listen without microphone permission");
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification("Listening for your voice"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            microphoneEnabled = true;
            voice.listen();
            return true;
        } catch (RuntimeException denied) {
            Log.w("PocketVoice", "Android denied microphone reactivation", denied);
            return false;
        }
    }

    private void playNextAnnouncement() {
        if (!started || responding || userSpeaking || pendingAnnouncements.isEmpty()) return;
        currentAnnouncement = pendingAnnouncements.removeFirst();
        main.removeCallbacks(idleStop);
        responding = true;
        activeTurn = null;
        voice.startReply();
        voice.appendReply(currentAnnouncement.message);
        voice.finishReply();
        showStatus("Speaking agent announcement");
        Log.i("PocketVoice", "Queued agent announcement for playback");
    }

    private void afterPlayback() {
        if (!started || responding) return;
        if (!pendingAnnouncements.isEmpty() && !userSpeaking) { playNextAnnouncement(); return; }
        if (keepListening && microphoneEnabled) {
            showStatus("Listening for follow-up");
            if (!userSpeaking) resetIdle();
        } else if (!userSpeaking) stopSelf();
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
            .setContentTitle(microphoneEnabled ? "Pocket Agent · Microphone active" : "Pocket Agent · Speaking")
            .setContentText(status).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "Stop voice", stop).build())
            .build();
    }

    private void showStatus(String status) {
        if (started) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(status));
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
            currentAnnouncement = null;
            voice.interruptReply();
            try {
                if (interruptedTurn != null) command(new JSONObject()
                    .put("action", "stop_main").put("voiceSessionId", interruptedTurn));
            } catch (Exception error) { Log.w("PocketVoice", "Could not abort previous response", error); }
            showStatus("Interrupted · listening to you");
        } else showStatus("Hearing you");
    }

    private void sendUtterance(String text) {
        if (!started || voice == null || text == null || text.isBlank()) return;
        if (responding) speechStarted();
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
            afterPlayback();
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
        main.removeCallbacks(idleStop);
        AgentService.unobserve(this);
        pendingAnnouncements.clear();
        acceptedIds.clear();
        if (voice != null) { voice.close(); voice = null; }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
