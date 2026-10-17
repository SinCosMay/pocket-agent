package dev.pocket.agent;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class VoiceSttSettingsDeviceTest {
    @Test public void defaultsToDeepgramAndPersistsAndroidDefault() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("voice_stt", Context.MODE_PRIVATE);
        Object previous = prefs.getAll().get("engine");
        try {
            prefs.edit().clear().commit();
            assertEquals(VoiceSttSettings.Engine.DEEPGRAM, VoiceSttSettings.engine(context));
            VoiceSttSettings.setEngine(context, VoiceSttSettings.Engine.ANDROID_DEFAULT);
            assertEquals(VoiceSttSettings.Engine.ANDROID_DEFAULT, VoiceSttSettings.engine(context));
        } finally {
            SharedPreferences.Editor restore = prefs.edit().clear();
            if (previous instanceof String) restore.putString("engine", (String) previous);
            restore.commit();
        }
    }

    @Test public void unknownStoredValueFallsBackToDeepgram() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("voice_stt", Context.MODE_PRIVATE);
        Object previous = prefs.getAll().get("engine");
        try {
            prefs.edit().putString("engine", "future_engine").commit();
            assertEquals(VoiceSttSettings.Engine.DEEPGRAM, VoiceSttSettings.engine(context));
        } finally {
            SharedPreferences.Editor restore = prefs.edit().remove("engine");
            if (previous instanceof String) restore.putString("engine", (String) previous);
            restore.commit();
        }
    }
}
