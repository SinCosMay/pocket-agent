package dev.pocket.stttest;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity implements RecognitionListener {
    private static final int AUDIO_PERMISSION_REQUEST = 41;

    private SpeechRecognizer recognizer;
    private Button startButton;
    private TextView stateView;
    private TextView resultView;
    private TextView partialView;
    private TextView candidatesView;
    private TextView timingView;
    private boolean listening;
    private long listenStartedAt;
    private long speechStartedAt;
    private long speechEndedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        showRecognizerInfo();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 247, 250));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView eyebrow = text("ANDROID DEFAULT RECOGNIZER", 12, Color.rgb(68, 79, 101));
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        eyebrow.setLetterSpacing(0.08f);
        root.addView(eyebrow);

        TextView title = text("Short speech test", 30, Color.rgb(20, 28, 43));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, margins(0, dp(8), 0, dp(8)));

        TextView intro = text(
                "This app talks directly to Android's default SpeechRecognizer. No Deepgram, no 3-second cutoff, and no Pocket Agent audio pipeline.",
                16, Color.rgb(68, 79, 101));
        intro.setLineSpacing(0f, 1.18f);
        root.addView(intro, margins(0, 0, 0, dp(22)));

        stateView = pill("Checking recognizer…");
        root.addView(stateView, margins(0, 0, 0, dp(18)));

        startButton = new Button(this);
        startButton.setText("Start speaking");
        startButton.setTextSize(17);
        startButton.setAllCaps(false);
        startButton.setMinHeight(dp(56));
        startButton.setOnClickListener(v -> {
            if (listening) {
                recognizer.stopListening();
                state("Finishing…");
            } else {
                ensurePermissionAndStart();
            }
        });
        root.addView(startButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        root.addView(sectionLabel("FINAL RESULT"), margins(0, dp(28), 0, dp(8)));
        resultView = text("—", 27, Color.rgb(20, 28, 43));
        resultView.setTypeface(Typeface.DEFAULT_BOLD);
        resultView.setTextIsSelectable(true);
        root.addView(resultView);

        root.addView(sectionLabel("LIVE PARTIAL"), margins(0, dp(24), 0, dp(7)));
        partialView = text("—", 17, Color.rgb(68, 79, 101));
        partialView.setTextIsSelectable(true);
        root.addView(partialView);

        root.addView(sectionLabel("ALTERNATIVES"), margins(0, dp(24), 0, dp(7)));
        candidatesView = text("—", 15, Color.rgb(68, 79, 101));
        candidatesView.setTextIsSelectable(true);
        root.addView(candidatesView);

        root.addView(sectionLabel("TIMING"), margins(0, dp(24), 0, dp(7)));
        timingView = text("—", 15, Color.rgb(68, 79, 101));
        root.addView(timingView);

        TextView note = text(
                "Test the same phrases in Gboard and here, especially names like “call Tanmay”. If Gboard wins while this app misses, the difference is Gboard's pipeline rather than Pocket Agent's cutoff logic.",
                14, Color.rgb(92, 100, 116));
        note.setLineSpacing(0f, 1.15f);
        root.addView(note, margins(0, dp(28), 0, 0));

        return scroll;
    }

    private void showRecognizerInfo() {
        boolean available = SpeechRecognizer.isRecognitionAvailable(this);
        String secureDefault = Settings.Secure.getString(
                getContentResolver(), "voice_recognition_service");

        ResolveInfo resolved = getPackageManager().resolveService(
                new Intent(RecognitionService.SERVICE_INTERFACE),
                PackageManager.MATCH_DEFAULT_ONLY);
        String resolvedName = resolved == null || resolved.serviceInfo == null
                ? "not exposed by PackageManager"
                : resolved.serviceInfo.packageName + "/" + resolved.serviceInfo.name;

        String info = available ? "Default recognizer available" : "No default recognizer available";
        info += "\nSettings: " + (secureDefault == null ? "not exposed" : secureDefault);
        info += "\nResolved service: " + resolvedName;
        stateView.setText(info);
        startButton.setEnabled(available);
    }

    private void ensurePermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    AUDIO_PERMISSION_REQUEST);
            return;
        }
        startRecognition();
    }

    private void startRecognition() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
        }

        resultView.setText("Listening…");
        partialView.setText("—");
        candidatesView.setText("—");
        timingView.setText("—");

        listenStartedAt = SystemClock.elapsedRealtime();
        speechStartedAt = 0L;
        speechEndedAt = 0L;
        listening = true;
        startButton.setText("Stop");

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        recognizer.startListening(intent);
        state("Starting default recognizer…");
    }

    @Override public void onReadyForSpeech(Bundle params) { state("Ready — speak now"); }

    @Override
    public void onBeginningOfSpeech() {
        speechStartedAt = SystemClock.elapsedRealtime();
        state("Speech detected");
    }

    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}

    @Override
    public void onEndOfSpeech() {
        speechEndedAt = SystemClock.elapsedRealtime();
        state("Speech ended — waiting for final result");
        updateTiming(0L);
    }

    @Override
    public void onError(int error) {
        listening = false;
        startButton.setText("Start speaking");
        resultView.setText("No final result");
        state("Error " + error + ": " + errorName(error));
        updateTiming(SystemClock.elapsedRealtime());
    }

    @Override
    public void onResults(Bundle results) {
        listening = false;
        startButton.setText("Start speaking");

        ArrayList<String> texts =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        float[] confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);

        if (texts == null || texts.isEmpty()) {
            resultView.setText("No result text");
            candidatesView.setText("—");
        } else {
            resultView.setText(texts.get(0));
            candidatesView.setText(formatCandidates(texts, confidence));
        }

        partialView.setText("—");
        long now = SystemClock.elapsedRealtime();
        state("Final result received");
        updateTiming(now);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> texts =
                partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts != null && !texts.isEmpty()) {
            partialView.setText(texts.get(0));
        }
    }

    @Override public void onEvent(int eventType, Bundle params) {}

    private String formatCandidates(ArrayList<String> texts, float[] scores) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(i + 1).append(". ").append(texts.get(i));
            if (scores != null && i < scores.length && scores[i] >= 0f) {
                out.append(String.format(Locale.US, "  (%.0f%%)", scores[i] * 100f));
            }
        }
        return out.toString();
    }

    private void updateTiming(long finalAt) {
        StringBuilder out = new StringBuilder();
        if (speechStartedAt > 0L && speechEndedAt > 0L) {
            out.append("Speech callback duration: ")
                    .append(speechEndedAt - speechStartedAt).append(" ms");
        } else {
            out.append("Speech callback duration: unavailable");
        }
        if (listenStartedAt > 0L && speechEndedAt > 0L) {
            out.append("\nStart → end-of-speech: ")
                    .append(speechEndedAt - listenStartedAt).append(" ms");
        }
        if (finalAt > 0L) {
            out.append("\nStart → final result: ")
                    .append(finalAt - listenStartedAt).append(" ms");
            if (speechEndedAt > 0L) {
                out.append("\nEnd-of-speech → final: ")
                        .append(finalAt - speechEndedAt).append(" ms");
            }
        }
        timingView.setText(out.toString());
    }

    private String errorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network timeout";
            case SpeechRecognizer.ERROR_NETWORK: return "network error";
            case SpeechRecognizer.ERROR_AUDIO: return "audio error";
            case SpeechRecognizer.ERROR_SERVER: return "server error";
            case SpeechRecognizer.ERROR_CLIENT: return "client error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no speech input";
            case SpeechRecognizer.ERROR_NO_MATCH: return "no match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "recognizer busy";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "microphone permission denied";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: return "too many requests";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "server disconnected";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "language not supported";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "language unavailable";
            default: return "unknown recognizer error";
        }
    }

    private void state(String message) { stateView.setText(message); }

    private TextView sectionLabel(String value) {
        TextView view = text(value, 12, Color.rgb(68, 79, 101));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setLetterSpacing(0.08f);
        return view;
    }

    private TextView pill(String value) {
        TextView view = text(value, 13, Color.rgb(38, 52, 78));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackgroundColor(Color.rgb(231, 236, 247));
        view.setLineSpacing(0f, 1.1f);
        return view;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecognition();
            } else {
                state("Microphone permission is required");
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        super.onDestroy();
    }
}
