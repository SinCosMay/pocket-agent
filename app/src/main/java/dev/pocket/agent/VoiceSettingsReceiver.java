package dev.pocket.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Keeps process-local voice setting caches coherent with writes made in the UI process. */
public final class VoiceSettingsReceiver extends BroadcastReceiver {
    static final String ACTION="dev.pocket.agent.VOICE_SETTINGS_CHANGED";
    static final String EXTRA_WAKE_THRESHOLD="wake_threshold_percent";
    static final String EXTRA_STANDARD_MIC="standard_mic";
    static final String EXTRA_STT_MODE="stt_mode";
    static final String EXTRA_SHORT_MS="short_ms";

    static Intent intent(Context context) {
        return new Intent(context,VoiceSettingsReceiver.class).setAction(ACTION);
    }
    @Override public void onReceive(Context context,Intent intent) {
        if(intent==null||!ACTION.equals(intent.getAction()))return;
        if(intent.hasExtra(EXTRA_WAKE_THRESHOLD)) {
            int value=intent.getIntExtra(EXTRA_WAKE_THRESHOLD,WakeWordSettings.DEFAULT_PERCENT);
            WakeWordSettings.applyProcessThreshold(value);
            WakeWordService.updateThreshold(value/100f);
        }
        if(intent.hasExtra(EXTRA_STANDARD_MIC))
            WakeWordSettings.applyProcessStandardMic(intent.getBooleanExtra(EXTRA_STANDARD_MIC,false));
        if(intent.hasExtra(EXTRA_STT_MODE))
            VoiceSttSettings.applyProcessMode(intent.getStringExtra(EXTRA_STT_MODE));
        if(intent.hasExtra(EXTRA_SHORT_MS))
            VoiceSttSettings.applyProcessShortMs(intent.getIntExtra(EXTRA_SHORT_MS,VoiceSttSettings.DEFAULT_SHORT_UTTERANCE_MS));
    }
}
