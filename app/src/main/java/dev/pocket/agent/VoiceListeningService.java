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
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.SpeechRecognizer;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.Arrays;
import org.json.JSONObject;

/** User-started assistant capture plus on-demand announcements from the running agent. */
public final class VoiceListeningService extends Service implements AgentService.Observer {
    static final String ACTION_START = "dev.pocket.agent.VOICE_START";
    static final String ACTION_STOP = "dev.pocket.agent.VOICE_STOP";
    static final String ACTION_ANNOUNCE = "dev.pocket.agent.VOICE_ANNOUNCE";
    private static final int NOTIFICATION_ID = 32;
    private static final String CHANNEL = "voice";
    private static final long IDLE_TIMEOUT_MS = 3000;
    private static final long SYSTEM_FINAL_TIMEOUT_MS = 5000;
    private static final int MAX_PENDING_ANNOUNCEMENTS = 8;
    private static volatile VoiceListeningService runningService;
    static boolean isRunning() { return runningService != null; }
    private static volatile boolean active;
    static boolean isActive() { return active; }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<VoiceAnnouncement> pendingAnnouncements = new ArrayDeque<>();
    private final LinkedHashSet<String> acceptedIds = new LinkedHashSet<>();
    private DeepgramVoice voice;
    private AndroidDefaultSpeechRecognizer systemSpeech;
    private VoiceSttSettings.Engine sttEngine = VoiceSttSettings.Engine.DEEPGRAM;
    private VoiceAnnouncement currentAnnouncement;
    private String activeTurn;
    private boolean responding, userSpeaking, started, keepListening, microphoneEnabled, streamingReady, wakeStarted;
    private boolean systemWakeReadyCuePending;
    private long utteranceAt, firstTokenAt;
    private final Runnable idleStop = () -> {
        if (started && microphoneEnabled && streamingReady && !responding && !userSpeaking && pendingAnnouncements.isEmpty()) {
            Log.i("PocketVoice", "No speech for " + IDLE_TIMEOUT_MS + " ms; stopping microphone");
            stopSelf();
        }
    };
    private final Runnable restartSystemSpeech = () -> {
        if (!started || sttEngine != VoiceSttSettings.Engine.ANDROID_DEFAULT
                || !microphoneEnabled || responding || systemSpeech == null) return;
        if (!startSystemRecognition()) stopSelf();
    };
    private final Runnable systemFinalTimeout = () -> {
        if (!started || sttEngine != VoiceSttSettings.Engine.ANDROID_DEFAULT
                || responding || systemSpeech == null || !systemSpeech.isActive()) return;
        systemSpeech.cancel();
        streamingReady = false;
        userSpeaking = false;
        scheduleSystemRecognitionRestart("Android speech result timed out · listening again");
    };

    @Override public void onCreate() {
        super.onCreate();
        runningService = this;
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Voice listening", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        if (!ACTION_START.equals(action) && !ACTION_ANNOUNCE.equals(action)) return START_NOT_STICKY;
        if (ACTION_START.equals(action) && intent.hasExtra(WakeWordService.EXTRA_WAKE_PREROLL)
                && !WakeWordService.enabled(this)) {
            if (!started) stopSelf();
            return START_NOT_STICKY;
        }
        VoiceAnnouncement request = null;
        if (ACTION_ANNOUNCE.equals(action)) {
            try { request = VoiceAnnouncement.fromIntent(intent); }
            catch (RuntimeException invalid) { Log.w("PocketVoice", "Invalid speech request", invalid); return START_NOT_STICKY; }
            if (acceptedIds.contains(request.id)) return START_NOT_STICKY;
            if (pendingAnnouncements.size() >= MAX_PENDING_ANNOUNCEMENTS) {
                Log.w("PocketVoice", "Announcement queue is full; rejecting request");
                AgentService.reportVoiceFailure(this, request.id, "Too many pending voice announcements");
                return START_NOT_STICKY;
            }
            acceptedIds.add(request.id);
            if (acceptedIds.size() > 64) acceptedIds.remove(acceptedIds.iterator().next());
        }
        if (!started && !startOutput(request == null ? "" : request.id)) return START_NOT_STICKY;
        if (ACTION_START.equals(action)) {
            byte[] wakePreroll = intent.getByteArrayExtra(WakeWordService.EXTRA_WAKE_PREROLL);
            if (wakePreroll != null && wakePreroll.length > 0 && !microphoneEnabled) {
                wakeStarted = true;
                try {
                    if (sttEngine == VoiceSttSettings.Engine.DEEPGRAM) {
                        voice.primeWakePcm(wakePreroll);
                    } else {
                        // SpeechRecognizer owns its microphone and cannot accept the wake PCM.
                        // Cue the user once the system recognizer is actually ready instead of
                        // silently losing words spoken during the microphone handoff.
                        systemWakeReadyCuePending = true;
                    }
                } catch (RuntimeException invalid) {
                    Log.w("PocketVoice", "Invalid wake audio preroll", invalid);
                } finally { Arrays.fill(wakePreroll, (byte) 0); }
            }
            keepListening = true;
            if (!enableMicrophone()) {
                keepListening = false;
                stopSelf();
                return START_NOT_STICKY;
            }
            if (streamingReady && !responding && !userSpeaking) resetIdle();
        } else {
            if (request.listenAfter) {
                keepListening = true;
                if (!enableMicrophone()) keepListening = false;
            }
            voice.startOutput();
            pendingAnnouncements.addLast(request);
            main.removeCallbacks(idleStop);
            playNextAnnouncement();
        }
        return START_NOT_STICKY;
    }

    private boolean startOutput(String requestId) {
        String key;
        try { key = new VoiceCredentialStore(this).read(); }
        catch (Exception error) {
            AgentService.reportVoiceFailure(this, requestId, "Saved Deepgram voice key is unreadable");
            stopSelf(); return false;
        }
        if (key.isEmpty()) {
            Log.w("PocketVoice", "Speech requires configured Deepgram credentials");
            AgentService.reportVoiceFailure(this, requestId, "Configure a Deepgram voice key in Pocket Agent Settings");
            stopSelf(); return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification("Preparing speech"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION_ID, notification("Preparing speech"));
        } catch (RuntimeException denied) {
            Log.w("PocketVoice", "Android denied speech foreground service", denied);
            AgentService.reportVoiceFailure(this, requestId, "Android denied speech in the background");
            stopSelf(); return false;
        }
        sttEngine = VoiceSttSettings.engine(this);
        started = true;
        AgentService.observe(this);
        if (!AgentService.isRunning())
            startForegroundService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_START));
        voice = new DeepgramVoice(this, key, new DeepgramVoice.Callback() {
            @Override public void onUtterance(String text) {
                main.post(() -> {
                    if (sttEngine == VoiceSttSettings.Engine.DEEPGRAM) sendUtterance(text);
                });
            }
            @Override public void onSpeechStarted() {
                main.post(() -> {
                    if (sttEngine == VoiceSttSettings.Engine.DEEPGRAM) speechStarted();
                });
            }
            @Override public void onEmptyTurn() {
                main.post(() -> {
                    if (!started || sttEngine != VoiceSttSettings.Engine.DEEPGRAM) return;
                    userSpeaking = false;
                    if (!responding) afterPlayback();
                });
            }
            @Override public void onInterim(String text) {
                main.post(() -> {
                    if (started && sttEngine == VoiceSttSettings.Engine.DEEPGRAM
                            && !text.trim().isEmpty()) {
                        userSpeaking = true;
                        main.removeCallbacks(idleStop);
                    }
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
                    boolean canInterrupt = microphoneEnabled
                        && sttEngine == VoiceSttSettings.Engine.DEEPGRAM;
                    showStatus(canInterrupt ? "Speaking · listening for interruption" : "Speaking");
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
            @Override public void onStreamReady() {
                main.post(() -> {
                    if (!started || !microphoneEnabled
                            || sttEngine != VoiceSttSettings.Engine.DEEPGRAM) return;
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
                    if (sttEngine == VoiceSttSettings.Engine.DEEPGRAM
                            && (status.startsWith("Microphone") || status.startsWith("Speech recognition"))) {
                        microphoneEnabled = false;
                        active = false;
                        streamingReady = false;
                        userSpeaking = false;
                        keepListening = false;
                        if (!responding) afterPlayback();
                    }
                });
            }
        });
        if (sttEngine == VoiceSttSettings.Engine.ANDROID_DEFAULT) {
            systemSpeech = new AndroidDefaultSpeechRecognizer(this,
                new AndroidDefaultSpeechRecognizer.Callback() {
                    @Override public void onReady() { systemRecognitionReady(); }
                    @Override public void onSpeechStarted() { systemSpeechStarted(); }
                    @Override public void onSpeechEnded() { systemSpeechEnded(); }
                    @Override public void onPartial(String text) { systemPartial(text); }
                    @Override public void onResult(String text) { systemUtterance(text); }
                    @Override public void onError(int error) { systemRecognitionError(error); }
                });
        }
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
            WakeWordService.pauseForConversation();
            microphoneEnabled = true;
            active = true;
            streamingReady = false;
            voice.startOutput();
            if (sttEngine == VoiceSttSettings.Engine.DEEPGRAM) {
                voice.listen();
                return true;
            }
            if (systemSpeech == null || !AndroidDefaultSpeechRecognizer.supported(this)) {
                microphoneEnabled = false;
                active = false;
                return false;
            }
            if (startSystemRecognition()) return true;
            microphoneEnabled = false;
            active = false;
            return false;
        } catch (RuntimeException denied) {
            Log.w("PocketVoice", "Android denied microphone reactivation", denied);
            microphoneEnabled = false;
            active = false;
            return false;
        }
    }

    private void playNextAnnouncement() {
        if (!started || responding || userSpeaking || pendingAnnouncements.isEmpty()) return;
        currentAnnouncement = pendingAnnouncements.removeFirst();
        main.removeCallbacks(idleStop);
        pauseSystemRecognitionForOutput();
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
            if (!userSpeaking) {
                if (sttEngine == VoiceSttSettings.Engine.ANDROID_DEFAULT) {
                    if (!startSystemRecognition()) stopSelf();
                } else if (streamingReady) {
                    resetIdle();
                }
            }
        } else if (!userSpeaking) stopSelf();
    }

    private boolean startSystemRecognition() {
        if (!started || sttEngine != VoiceSttSettings.Engine.ANDROID_DEFAULT
                || !microphoneEnabled || responding || systemSpeech == null) return false;
        main.removeCallbacks(restartSystemSpeech);
        main.removeCallbacks(systemFinalTimeout);
        streamingReady = false;
        boolean recognitionStarted = systemSpeech.start();
        if (recognitionStarted) showStatus("Starting Android default speech recognition");
        return recognitionStarted;
    }

    private void pauseSystemRecognitionForOutput() {
        if (sttEngine != VoiceSttSettings.Engine.ANDROID_DEFAULT) return;
        main.removeCallbacks(restartSystemSpeech);
        main.removeCallbacks(systemFinalTimeout);
        main.removeCallbacks(idleStop);
        streamingReady = false;
        if (systemSpeech != null && systemSpeech.isActive()) systemSpeech.cancel();
    }

    private void systemRecognitionReady() {
        if (!started || sttEngine != VoiceSttSettings.Engine.ANDROID_DEFAULT
                || !microphoneEnabled || responding) return;
        streamingReady = true;
        if (!userSpeaking) {
            showStatus("Listening · Android default");
            if (systemWakeReadyCuePending) {
                systemWakeReadyCuePending = false;
                playSystemReadyCue();
            }
            resetIdle();
        }
    }

    private void playSystemReadyCue() {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
            main.postDelayed(tone::release, 300L);
        } catch (RuntimeException unavailable) {
            Log.w("PocketVoice", "Could not play Android STT ready cue", unavailable);
        }
    }

    private void systemSpeechStarted() {
        if (!started || sttEngine != VoiceSttSettings.Engine.ANDROID_DEFAULT) return;
        speechStarted();
    }

    private void systemSpeechEnded() {
        if (!started || sttEngine != VoiceSttSettings.Engine.ANDROID_DEFAULT) return;
        streamingReady = false;
        main.removeCallbacks(idleStop);
        main.removeCallbacks(systemFinalTimeout);
        main.postDelayed(systemFinalTimeout, SYSTEM_FINAL_TIMEOUT_MS);
        showStatus("Finalizing speech");
    }

    private void systemPartial(String text) {
        if (!started || sttEngine != VoiceSttSettings.Engine.ANDROID_DEFAULT
                || text == null || text.trim().isEmpty()) return;
        userSpeaking = true;
        main.removeCallbacks(idleStop);
    }

    private void systemUtterance(String text) {
        if (!started || sttEngine != VoiceSttSettings.Engine.ANDROID_DEFAULT) return;
        main.removeCallbacks(systemFinalTimeout);
        streamingReady = false;
        if (text == null || text.trim().isEmpty()) {
            userSpeaking = false;
            scheduleSystemRecognitionRestart("No speech match · listening again");
            return;
        }
        sendUtterance(text);
    }

    private void systemRecognitionError(int error) {
        if (!started || sttEngine != VoiceSttSettings.Engine.ANDROID_DEFAULT) return;
        main.removeCallbacks(systemFinalTimeout);
        streamingReady = false;
        userSpeaking = false;
        main.removeCallbacks(idleStop);
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                || error == SpeechRecognizer.ERROR_CLIENT) {
            microphoneEnabled = false;
            active = false;
            keepListening = false;
            showStatus("Android speech recognition stopped (error " + error + ")");
            if (!responding) afterPlayback();
            return;
        }
        if (!responding && keepListening && microphoneEnabled)
            scheduleSystemRecognitionRestart("Android speech recognition ended · retrying");
    }

    private void scheduleSystemRecognitionRestart(String status) {
        if (!started || sttEngine != VoiceSttSettings.Engine.ANDROID_DEFAULT
                || !keepListening || !microphoneEnabled || responding) return;
        showStatus(status);
        main.removeCallbacks(restartSystemSpeech);
        main.postDelayed(restartSystemSpeech, 250L);
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
        if (!started || voice == null || text == null || text.trim().isEmpty()) return;
        if (wakeStarted) {
            text = text.replaceFirst("(?i)^\\s*hey[,]?\\s+jarvis[,!?.\\s]*", "").trim();
            if (text.isEmpty()) {
                userSpeaking = false;
                if (!responding) afterPlayback();
                return;
            }
        }
        if (responding) speechStarted();
        userSpeaking = false;
        main.removeCallbacks(idleStop);
        pauseSystemRecognitionForOutput();
        responding = true;
        activeTurn = UUID.randomUUID().toString();
        utteranceAt = SystemClock.elapsedRealtime();
        firstTokenAt = 0;
        voice.startReply();
        showStatus(sttEngine == VoiceSttSettings.Engine.ANDROID_DEFAULT
            ? "Thinking" : "Thinking · listening for interruption");
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
        if (runningService == this) runningService = null;
        started = false;
        active = false;
        streamingReady = false;
        wakeStarted = false;
        systemWakeReadyCuePending = false;
        main.removeCallbacks(idleStop);
        main.removeCallbacks(restartSystemSpeech);
        main.removeCallbacks(systemFinalTimeout);
        AgentService.unobserve(this);
        pendingAnnouncements.clear();
        acceptedIds.clear();
        if (systemSpeech != null) { systemSpeech.close(); systemSpeech = null; }
        if (voice != null) { voice.close(); voice = null; }
        WakeWordService.resumeAfterConversation(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
