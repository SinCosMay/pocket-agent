package dev.pocket.agent;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists the user's selected speech-to-text engine. */
final class VoiceSttSettings {
    enum Engine {
        DEEPGRAM("deepgram"),
        ANDROID_DEFAULT("android_default");

        final String storedValue;

        Engine(String storedValue) {
            this.storedValue = storedValue;
        }
    }

    private static final String PREFS = "voice_stt";
    private static final String ENGINE_KEY = "engine";

    static Engine engine(Context context) {
        String saved = prefs(context).getString(ENGINE_KEY, Engine.DEEPGRAM.storedValue);
        if (Engine.ANDROID_DEFAULT.storedValue.equals(saved) || "android".equals(saved))
            return Engine.ANDROID_DEFAULT;
        return Engine.DEEPGRAM;
    }

    static void setEngine(Context context, Engine engine) {
        if (engine == null) throw new IllegalArgumentException("Speech-to-text engine is required");
        prefs(context).edit().putString(ENGINE_KEY, engine.storedValue).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private VoiceSttSettings() { }
}
