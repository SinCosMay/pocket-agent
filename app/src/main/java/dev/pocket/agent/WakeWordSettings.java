package dev.pocket.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;

/** Wake-word tuning is independent from the Deepgram/provider credentials. */
final class WakeWordSettings {
    static final int DEFAULT_PERCENT = 75;
    static final int MIN_PERCENT = 1;
    static final int MAX_PERCENT = 99;
    static final int SOURCE_VOICE_RECOGNITION = 0;
    static final int SOURCE_STANDARD_MIC = 1;
    private static final String PREFS = "wakeword";
    private static final String THRESHOLD_KEY = "threshold_percent";
    private static final String AUDIO_SOURCE_KEY = "audio_source";

    static int thresholdPercent(Context context) {
        int value = prefs(context).getInt(THRESHOLD_KEY, DEFAULT_PERCENT);
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, value));
    }

    static float threshold(Context context) {
        return thresholdPercent(context) / 100f;
    }

    static void setThresholdPercent(Context context, int value) {
        if (value < MIN_PERCENT || value > MAX_PERCENT)
            throw new IllegalArgumentException("Wake threshold must be between 1 and 99 percent");
        prefs(context).edit().putInt(THRESHOLD_KEY, value).apply();
        WakeWordService.updateThreshold(value / 100f);
    }

    static int audioSource(Context context) {
        return prefs(context).getInt(AUDIO_SOURCE_KEY, SOURCE_VOICE_RECOGNITION) == SOURCE_STANDARD_MIC
            ? MediaRecorder.AudioSource.MIC : MediaRecorder.AudioSource.VOICE_RECOGNITION;
    }

    static boolean standardMicrophone(Context context) {
        return prefs(context).getInt(AUDIO_SOURCE_KEY, SOURCE_VOICE_RECOGNITION) == SOURCE_STANDARD_MIC;
    }

    static void setStandardMicrophone(Context context, boolean standard) {
        prefs(context).edit().putInt(AUDIO_SOURCE_KEY,
            standard ? SOURCE_STANDARD_MIC : SOURCE_VOICE_RECOGNITION).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private WakeWordSettings() { }
}
