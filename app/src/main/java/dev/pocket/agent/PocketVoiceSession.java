package dev.pocket.agent;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.UUID;

/** UI lifetime controls voice I/O only; dismissing it never cancels ongoing Pi workers. */
public final class PocketVoiceSession extends VoiceInteractionSession implements AgentService.Observer {
    private DeepgramVoice voice;
    private String sessionId;
    private TextView status, heard, response;
    private boolean visible, awaitingReply;
    private long utteranceAtMs, firstTokenAtMs;

    PocketVoiceSession(Context context) { super(context); }

    @Override public View onCreateContentView() {
        Context context = getContext();
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, pad);
        TextView heading = new TextView(context);
        heading.setText("Pocket Agent");
        heading.setTypeface(null, Typeface.BOLD);
        heading.setTextSize(22);
        panel.addView(heading);
        status = new TextView(context);
        status.setText("Connecting voice");
        status.setPadding(0, pad / 2, 0, pad / 2);
        panel.addView(status);
        heard = new TextView(context);
        heard.setTextSize(17);
        panel.addView(heard);
        response = new TextView(context);
        response.setTextSize(17);
        response.setPadding(0, pad / 2, 0, pad / 2);
        panel.addView(response);
        Button close = new Button(context);
        close.setText("Close voice");
        close.setOnClickListener(v -> finish());
        panel.addView(close);
        Button openSettings = new Button(context);
        openSettings.setText("Open Pocket Agent settings");
        openSettings.setOnClickListener(v -> {
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
        panel.addView(openSettings);
        return panel;
    }

    @Override public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        if (visible) return;
        visible = true;
        awaitingReply = false;
        sessionId = UUID.randomUUID().toString();
        AgentService.observe(this);
        getContext().startForegroundService(new Intent(getContext(), AgentService.class)
            .setAction(AgentService.ACTION_START));
        try {
            if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                setStatus("Allow microphone access in Pocket Agent settings");
                return;
            }
            String key = new VoiceCredentialStore(getContext()).read();
            if (key.isEmpty()) {
                setStatus("Add a Deepgram API key in Pocket Agent settings");
                return;
            }
            voice = new DeepgramVoice(getContext(), key, new DeepgramVoice.Callback() {
                @Override public void onUtterance(String text) {
                    getContext().getMainExecutor().execute(() -> sendUtterance(text));
                }
                @Override public void onInterim(String text) {
                    getContext().getMainExecutor().execute(() -> {
                        if (visible && !awaitingReply && heard != null) heard.setText(text);
                    });
                }
                @Override public void onFirstAudio() {
                    getContext().getMainExecutor().execute(() -> {
                        if (visible && utteranceAtMs > 0) {
                            long firstAudioMs = android.os.SystemClock.elapsedRealtime() - utteranceAtMs;
                            long tokenMs = firstTokenAtMs == 0 ? -1 : firstTokenAtMs - utteranceAtMs;
                            android.util.Log.i("PocketVoiceLatency", "first_token_ms=" + tokenMs + " first_audio_ms=" + firstAudioMs);
                            setStatus("Speaking · first audio " + firstAudioMs + " ms");
                        }
                    });
                }
                @Override public void onPlaybackFinished() {
                    getContext().getMainExecutor().execute(() -> {
                        if (!visible || voice == null) return;
                        awaitingReply = false;
                        setStatus("Listening");
                        voice.listen();
                    });
                }
                @Override public void onStatus(String message) {
                    getContext().getMainExecutor().execute(() -> { if (visible) setStatus(message); });
                }
            });
            voice.start();
        } catch (Exception error) {
            setStatus("Voice setup failed; check microphone and Deepgram settings");
        }
    }

    private void setStatus(String message) { if (status != null) status.setText(message); }

    private void sendUtterance(String text) {
        if (!visible || awaitingReply || voice == null || text == null || text.trim().isEmpty()) return;
        awaitingReply = true;
        utteranceAtMs = android.os.SystemClock.elapsedRealtime();
        firstTokenAtMs = 0;
        heard.setText("You: " + text);
        response.setText("");
        setStatus("Thinking");
        voice.startReply();
        try {
            JSONObject command = new JSONObject().put("action", "send")
                .put("voiceSessionId", sessionId).put("text", text);
            getContext().startForegroundService(new Intent(getContext(), AgentService.class)
                .setAction(AgentService.ACTION_COMMAND).putExtra("command", command.toString()));
        } catch (Exception error) {
            awaitingReply = false;
            setStatus("Unable to submit the spoken request");
            voice.finishReply();
        }
    }

    @Override public void onAgentEvent(JSONObject event) {
        if (!visible || !sessionId.equals(event.optString("sessionId"))) return;
        if ("voice_delta".equals(event.optString("type"))) {
            if (firstTokenAtMs == 0) firstTokenAtMs = android.os.SystemClock.elapsedRealtime();
            String delta = event.optString("text");
            if (response != null) response.append(delta);
            if (voice != null && awaitingReply) { voice.appendReply(delta); setStatus("Responding"); }
        } else if ("voice_end".equals(event.optString("type"))) {
            if (voice != null && awaitingReply) voice.finishReply();
        } else if ("voice_error".equals(event.optString("type"))) {
            setStatus("Voice request rejected; try speaking again");
            if (response != null) response.setText("Request could not be submitted");
            if (voice != null && awaitingReply) voice.finishReply();
        }
    }

    @Override public void onHide() {
        closeVoice();
        super.onHide();
    }

    @Override public void onDestroy() {
        closeVoice();
        super.onDestroy();
    }

    @Override public void onBackPressed() { finish(); }

    private void closeVoice() {
        if (!visible) return;
        visible = false;
        AgentService.unobserve(this);
        if (voice != null) { voice.close(); voice = null; }
        awaitingReply = false;
    }
}
