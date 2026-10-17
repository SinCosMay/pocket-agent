package dev.pocket.agent;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/**
 * Thin wrapper around Android's system-default SpeechRecognizer.
 *
 * Unlike the hybrid experiment, this recognizer owns the microphone itself. That deliberately
 * matches the standalone Android Default STT Test path instead of injecting Pocket Agent PCM.
 */
final class AndroidDefaultSpeechRecognizer implements AutoCloseable {
    interface Callback {
        void onReady();
        void onSpeechStarted();
        void onSpeechEnded();
        void onPartial(String text);
        void onResult(String text);
        void onError(int error);
    }

    private final Context context;
    private final Callback callback;
    private SpeechRecognizer recognizer;
    private long generation;
    private boolean active;

    AndroidDefaultSpeechRecognizer(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    static boolean supported(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    boolean start() {
        requireMainThread();
        cancel();
        if (!supported(context)) return false;

        long cycle = ++generation;
        try {
            SpeechRecognizer next = SpeechRecognizer.createSpeechRecognizer(context);
            recognizer = next;
            active = true;
            next.setRecognitionListener(new Listener(cycle));

            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            next.startListening(request);
            return true;
        } catch (RuntimeException unavailable) {
            active = false;
            SpeechRecognizer failed = recognizer;
            recognizer = null;
            if (failed != null) {
                try { failed.destroy(); } catch (RuntimeException ignored) { }
            }
            return false;
        }
    }

    boolean isActive() {
        return active;
    }

    void cancel() {
        requireMainThread();
        ++generation;
        active = false;
        SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old != null) {
            try { old.cancel(); } catch (RuntimeException ignored) { }
            try { old.destroy(); } catch (RuntimeException ignored) { }
        }
    }

    @Override public void close() {
        cancel();
    }

    private void finish(long cycle) {
        if (!active || cycle != generation) return;
        active = false;
        SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old != null) {
            try { old.destroy(); } catch (RuntimeException ignored) { }
        }
    }

    private static String firstResult(Bundle results) {
        if (results == null) return "";
        ArrayList<String> values =
            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("SpeechRecognizer must run on the main thread");
    }

    private final class Listener implements RecognitionListener {
        private final long cycle;

        Listener(long cycle) {
            this.cycle = cycle;
        }

        private boolean current() {
            return active && cycle == generation;
        }

        @Override public void onReadyForSpeech(Bundle params) {
            if (current()) callback.onReady();
        }

        @Override public void onBeginningOfSpeech() {
            if (current()) callback.onSpeechStarted();
        }

        @Override public void onRmsChanged(float rmsdB) { }

        @Override public void onBufferReceived(byte[] buffer) { }

        @Override public void onEndOfSpeech() {
            if (current()) callback.onSpeechEnded();
        }

        @Override public void onError(int error) {
            if (!current()) return;
            finish(cycle);
            callback.onError(error);
        }

        @Override public void onResults(Bundle results) {
            if (!current()) return;
            String text = firstResult(results);
            finish(cycle);
            callback.onResult(text);
        }

        @Override public void onPartialResults(Bundle partialResults) {
            if (!current()) return;
            callback.onPartial(firstResult(partialResults));
        }

        @Override public void onEvent(int eventType, Bundle params) { }
    }
}
