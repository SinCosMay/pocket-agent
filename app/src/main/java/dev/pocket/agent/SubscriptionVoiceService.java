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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Opt-in, standalone Codex-subscription voice foreground session. Pi remains the
 * sole agent harness; this service never exposes device tools to the voice model.
 * Only the worker-side SubscriptionVoiceTransport owns network/audio resources.
 */
public final class SubscriptionVoiceService extends Service implements AgentService.Observer {
    static final String ACTION_WAKE = "dev.pocket.agent.SUBSCRIPTION_VOICE_WAKE";
    static final String ACTION_ANNOUNCE = "dev.pocket.agent.SUBSCRIPTION_VOICE_ANNOUNCE";
    static final String ACTION_STOP = "dev.pocket.agent.SUBSCRIPTION_VOICE_STOP";
    private static final int NOTIFICATION_ID = 34;
    private static final String CHANNEL = "voice_subscription";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService credentialWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "PocketVoiceCredentials");
        thread.setDaemon(true);
        return thread;
    });
    private SubscriptionVoiceLifecycle lifecycle;
    private SubscriptionVoiceTransport transport;
    private boolean started;
    private static volatile boolean active;
    static boolean isActive() { return active; }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!started || lifecycle == null) return;
            lifecycle.tick(SystemClock.elapsedRealtime());
            if (started && lifecycle.state() != SubscriptionVoiceLifecycle.State.CLOSED)
                main.postDelayed(this, 250);
        }
    };
    private final SubscriptionVoiceTransport.Callback callbacks = new SubscriptionVoiceTransport.Callback() {
        @Override public void onConnected(long epoch) {
            main.post(() -> { if (started) lifecycle.connected(epoch, SystemClock.elapsedRealtime()); });
        }
        @Override public void onDelegation(long epoch, String id, String text) {
            main.post(() -> { if (started) lifecycle.delegated(epoch, id, text); });
        }
        @Override public void onPlaybackFinished(long epoch) {
            main.post(() -> { if (started) lifecycle.playbackFinished(epoch, SystemClock.elapsedRealtime()); });
        }
        @Override public void onActivity(long epoch) {
            main.post(() -> { if (started) lifecycle.activity(epoch, SystemClock.elapsedRealtime()); });
        }
        @Override public void onError(long epoch, String safeMessage) {
            main.post(() -> {
                if (!started || lifecycle.epoch() != epoch) return;
                Log.w("PocketSubscriptionVoice", "Subscription voice transport unavailable");
                notifyStatus("Voice connection failed · check ChatGPT sign-in");
                lifecycle.disconnected(epoch);
            });
        }
        @Override public void onDisconnected(long epoch) {
            main.post(() -> { if (started) lifecycle.disconnected(epoch); });
        }
        @Override public void onInterrupted(long epoch) {
            main.post(() -> {
                if (started) lifecycle.inputInterrupted(epoch, SystemClock.elapsedRealtime());
            });
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "ChatGPT subscription voice", NotificationManager.IMPORTANCE_LOW));
        transport = new SubscriptionVoiceTransport(getApplicationContext(), callbacks);
        lifecycle = new SubscriptionVoiceLifecycle(new SubscriptionVoiceLifecycle.Port() {
            @Override public void connect(long epoch) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    notifyStatus("Allow microphone access for subscription voice");
                    lifecycle.disconnected(epoch);
                    return;
                }
                // Token refresh can require HTTPS and must never block Android's main thread.
                credentialWorker.execute(() -> {
                    SubscriptionVoiceCredentials.Token token = null;
                    try {
                        token = SubscriptionVoiceCredentials.load(getApplicationContext());
                    } catch (Exception ignored) {
                        // OAuth errors can include secret-bearing response metadata.
                    }
                    final SubscriptionVoiceCredentials.Token ready = token;
                    main.post(() -> {
                        if (!started || lifecycle == null || lifecycle.epoch() != epoch
                                || lifecycle.state() != SubscriptionVoiceLifecycle.State.CONNECTING)
                            return;
                        if (ready == null) {
                            notifyStatus("ChatGPT voice sign-in expired; sign in again");
                            lifecycle.disconnected(epoch);
                            return;
                        }
                        try { transport.connect(epoch, ready.access, ready.account); }
                        catch (RuntimeException invalid) {
                            notifyStatus("Unable to start ChatGPT voice");
                            lifecycle.disconnected(epoch);
                        }
                    });
                });
            }
            @Override public void close(long epoch) { transport.close(epoch); }
            @Override public void sendToPi(String turnId, String utterance) {
                try {
                    sendPi(new JSONObject().put("action", "send")
                        .put("voiceSessionId", turnId).put("text", utterance));
                } catch (Exception rejected) {
                    lifecycle.close();
                }
            }
            @Override public void cancelPi(String turnId) {
                try {
                    sendPi(new JSONObject().put("action", "stop_main")
                        .put("voiceSessionId", turnId));
                } catch (Exception rejected) {
                    Log.w("PocketSubscriptionVoice", "Scoped Pi stop not submitted");
                }
            }
            @Override public void sendSpeech(String delegationId, String text) {
                transport.sendSpeech(delegationId, text);
            }
            @Override public void interruptSpeech() { transport.interruptSpeech(); }
            @Override public void status(String status) {
                notifyStatus(status);
                if (lifecycle != null && lifecycle.state() == SubscriptionVoiceLifecycle.State.CLOSED)
                    main.post(() -> { if (started) stopSelf(); });
            }
        });
        started = true;
        active = true;
        AgentService.observe(this);
    }

    private void sendPi(JSONObject command) {
        try {
            startService(new Intent(this, AgentService.class)
                .setAction(AgentService.ACTION_COMMAND).putExtra("command", command.toString()));
        } catch (Exception rejected) {
            Log.w("PocketSubscriptionVoice", "Unable to submit Pi command");
            lifecycle.close();
        }
    }

    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 1,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
            new Intent(this, SubscriptionVoiceService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Pocket Agent · ChatGPT voice")
            .setContentText(message).setOngoing(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Stop voice", stop).build()).build();
    }

    private void notifyStatus(String message) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) { lifecycle.close(); stopSelf(); return START_NOT_STICKY; }
        if (!ACTION_WAKE.equals(intent.getAction()) && !ACTION_ANNOUNCE.equals(intent.getAction()))
            return START_NOT_STICKY;
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification("Preparing ChatGPT voice"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION_ID, notification("Preparing ChatGPT voice"));
        } catch (RuntimeException denied) {
            Log.w("PocketSubscriptionVoice", "Microphone foreground service denied");
            stopSelf();
            return START_NOT_STICKY;
        }
        // Release the wake detector's microphone before native WebRTC takes it.
        // Assistant-gesture and speak_to_user entry points did not already pause wake.
        WakeWordService.pauseForConversation();
        if (!AgentService.isRunning())
            startForegroundService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_START));
        if (ACTION_WAKE.equals(intent.getAction())) {
            // Native WebRTC starts a new microphone; wake preroll belongs to legacy PCM.
            byte[] preroll = intent.getByteArrayExtra(WakeWordService.EXTRA_WAKE_PREROLL);
            if (preroll != null) java.util.Arrays.fill(preroll, (byte) 0);
            lifecycle.wake(SystemClock.elapsedRealtime());
        } else {
            try {
                VoiceAnnouncement announce = VoiceAnnouncement.fromIntent(intent);
                if (!lifecycle.announce(announce.message, announce.listenAfter,
                        SystemClock.elapsedRealtime()))
                    AgentService.reportVoiceFailure(this, announce.id, "Speech request could not be queued");
            } catch (RuntimeException invalid) {
                Log.w("PocketSubscriptionVoice", "Invalid subscription speech request");
            }
        }
        main.removeCallbacks(tick);
        main.postDelayed(tick, 250);
        return START_NOT_STICKY;
    }

    @Override public void onAgentEvent(JSONObject event) {
        if (!started || lifecycle == null) return;
        String turn = event.optString("sessionId", "");
        switch (event.optString("type", "")) {
            case "voice_delta":
                lifecycle.piDelta(turn, event.optString("text"));
                break;
            case "voice_end":
                lifecycle.piFinished(turn);
                break;
            case "voice_error":
                lifecycle.piDelta(turn, "The phone assistant could not complete that request.");
                lifecycle.piFinished(turn);
                break;
            case "stopped":
                lifecycle.close();
                break;
        }
    }

    @Override public void onDestroy() {
        started = false;
        active = false;
        main.removeCallbacks(tick);
        AgentService.unobserve(this);
        if (lifecycle != null) lifecycle.close();
        if (transport != null) transport.close();
        credentialWorker.shutdownNow();
        WakeWordService.resumeAfterConversation(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
