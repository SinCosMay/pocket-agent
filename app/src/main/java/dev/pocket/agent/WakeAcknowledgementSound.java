package dev.pocket.agent;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

/** Short local earcon used to confirm that the Hey Jarvis wake handoff succeeded. */
final class WakeAcknowledgementSound implements AutoCloseable {
    private SoundPool pool;
    private final int soundId;
    private volatile boolean loaded;

    WakeAcknowledgementSound(Context context) {
        AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
        pool = new SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attributes)
            .build();
        pool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            if (status == 0) loaded = true;
            else Log.w("JarvisWake", "Wake acknowledgement sound failed to load: " + status);
        });
        soundId = pool.load(context, R.raw.jarvis_ack, 1);
    }

    boolean isLoaded() {
        return loaded;
    }

    boolean play() {
        SoundPool current = pool;
        if (!loaded || current == null) return false;
        try {
            int streamId = current.play(soundId, 1f, 1f, 1, 0, 1f);
            if (streamId == 0) {
                Log.w("JarvisWake", "Wake acknowledgement sound could not start");
                return false;
            }
            Log.i("JarvisWake", "Wake acknowledgement sound played");
            return true;
        } catch (RuntimeException unavailable) {
            Log.w("JarvisWake", "Wake acknowledgement sound unavailable", unavailable);
            return false;
        }
    }

    @Override public void close() {
        SoundPool current = pool;
        pool = null;
        loaded = false;
        if (current != null) current.release();
    }
}
