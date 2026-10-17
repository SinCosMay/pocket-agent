package dev.pocket.agent;

import android.content.Context;
import android.content.SharedPreferences;

/** User-tunable arbitration between Android system recognition and Deepgram. */
final class VoiceSttSettings {
    static final int DEFAULT_SHORT_UTTERANCE_MS = 3000;
    static final int MIN_SHORT_UTTERANCE_MS = 500;
    static final int MAX_SHORT_UTTERANCE_MS = 8000;
    static final int SHORT_UTTERANCE_STEP_MS = 250;
    private static final String PREFS = "voice_stt";
    private static final String SHORT_UTTERANCE_KEY = "short_utterance_ms";

    static int shortUtteranceMs(Context context) {
        int value = prefs(context).getInt(SHORT_UTTERANCE_KEY, DEFAULT_SHORT_UTTERANCE_MS);
        return clamp(value);
    }

    static void setShortUtteranceMs(Context context, int value) {
        if (value < MIN_SHORT_UTTERANCE_MS || value > MAX_SHORT_UTTERANCE_MS)
            throw new IllegalArgumentException("Short utterance threshold is out of range");
        int snapped = MIN_SHORT_UTTERANCE_MS
            + Math.round((value - MIN_SHORT_UTTERANCE_MS) / (float) SHORT_UTTERANCE_STEP_MS)
                * SHORT_UTTERANCE_STEP_MS;
        prefs(context).edit().putInt(SHORT_UTTERANCE_KEY, clamp(snapped)).apply();
    }

    static int progressForMs(int value) {
        return (clamp(value) - MIN_SHORT_UTTERANCE_MS) / SHORT_UTTERANCE_STEP_MS;
    }

    static int msForProgress(int progress) {
        int maxProgress = (MAX_SHORT_UTTERANCE_MS - MIN_SHORT_UTTERANCE_MS)
            / SHORT_UTTERANCE_STEP_MS;
        int bounded = Math.max(0, Math.min(maxProgress, progress));
        return MIN_SHORT_UTTERANCE_MS + bounded * SHORT_UTTERANCE_STEP_MS;
    }

    static int maxProgress() {
        return (MAX_SHORT_UTTERANCE_MS - MIN_SHORT_UTTERANCE_MS)
            / SHORT_UTTERANCE_STEP_MS;
    }

    private static int clamp(int value) {
        return Math.max(MIN_SHORT_UTTERANCE_MS, Math.min(MAX_SHORT_UTTERANCE_MS, value));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private VoiceSttSettings() { }
}
