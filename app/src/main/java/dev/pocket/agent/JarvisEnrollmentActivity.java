package dev.pocket.agent;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Short on-device recording flow for an optional personalized wake-word verifier. */
public final class JarvisEnrollmentActivity extends Activity {
    private static final String[] PROMPTS = {
        "Hey Jarvis", "Hey Jarvis", "Hey Jarvis", "Hey Jarvis", "Hey Jarvis",
        "Today I am testing this voice assistant.",
        "The weather outside looks quite pleasant.",
        "Please remind me to buy groceries later."
    };
    private final float[][] samples = new float[PROMPTS.length][];
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView prompt, info;
    private Button record, back, next, train;
    private int position;
    private boolean busy;
    private volatile AudioRecord activeRecorder;
    private volatile boolean destroying;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        WakeWordService.pauseForEnrollment();
        if (saved != null) {
            position = Math.max(0, Math.min(PROMPTS.length-1, saved.getInt("position")));
            for (int i=0; i<samples.length; i++) samples[i] = saved.getFloatArray("sample"+i);
        }
        if (VoiceListeningService.isActive())
            stopService(new android.content.Intent(this, VoiceListeningService.class));
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (22 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        scroll.addView(layout);
        TextView title = new TextView(this);
        title.setText("Hey Jarvis · voice enrollment");
        title.setTextSize(25);
        layout.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Speak only when the screen says Speak now. The first second warms up the microphone and model. This calibration is not speaker identity verification. Raw audio is not retained.");
        layout.addView(subtitle);
        prompt = new TextView(this);
        prompt.setTextSize(23);
        prompt.setPadding(0, pad, 0, pad);
        layout.addView(prompt);
        info = new TextView(this);
        layout.addView(info);
        record = new Button(this); record.setText("Record phrase"); layout.addView(record);
        back = new Button(this); back.setText("Previous"); layout.addView(back);
        next = new Button(this); next.setText("Next"); layout.addView(next);
        train = new Button(this); train.setText("Train wake-phrase filter"); layout.addView(train);
        Button reset = new Button(this); reset.setText("Remove wake-phrase calibration"); layout.addView(reset);
        record.setOnClickListener(v -> startRecording());
        back.setOnClickListener(v -> { if (!busy) { position=Math.max(0,position-1);render(); }});
        next.setOnClickListener(v -> { if (!busy) { position=Math.min(PROMPTS.length-1,position+1);render(); }});
        train.setOnClickListener(v -> train());
        reset.setOnClickListener(v -> {
            if (!busy) { JarvisProfile.delete(this); info.setText("Phrase calibration removed. Generic detection remains available."); }
        });
        setContentView(scroll);
        render();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 31);
    }

    private void render() {
        int count = 0, positives = 0, negatives = 0;
        for (int i=0; i<samples.length; i++) {
            if (samples[i] != null) {
                count++;
                if (i<5) positives++; else negatives++;
            }
        }
        prompt.setText("Sample "+(position+1)+"/"+PROMPTS.length+
            (position<5?" · Hey Jarvis":" · Normal speech (NOT wake word)")+"\n\n"+PROMPTS[position]);
        info.setText(count+" of 8 recorded locally"+(samples[position]!=null?" · this sample complete":""));
        record.setText(samples[position]==null?"Record this phrase":"Retake this phrase");
        back.setEnabled(!busy&&position>0);
        next.setEnabled(!busy&&position<PROMPTS.length-1);
        record.setEnabled(!busy);
        train.setEnabled(!busy&&positives>=4&&negatives>=2);
    }

    private void startRecording() {
        if (busy) return;
        if (VoiceListeningService.isActive()) {
            info.setText("Please wait for the active voice session to finish before recording.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            info.setText("Allow microphone permission first");return;
        }
        busy=true;render();info.setText("Get ready to speak...");
        final int captureSlot=position;
        worker.execute(() -> {
            AudioRecord audio=null;
            try (JarvisDetector detector=new JarvisDetector(this)) {
                int size=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
                if(size<0)throw new IllegalStateException("16 kHz recording unavailable");
                if (destroying || Thread.currentThread().isInterrupted()) return;
                if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                    throw new SecurityException("Microphone permission revoked");
                audio=new AudioRecord(WakeWordSettings.audioSource(this),16000,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(size,4096));
                if(audio.getState()!=AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("Microphone unavailable");
                activeRecorder = audio;
                if (destroying || Thread.currentThread().isInterrupted()) return;
                audio.startRecording();
                if(audio.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                    throw new IllegalStateException("Microphone is busy");
                runOnUiThread(()->info.setText("Preparing microphone… speak when prompted"));
                short[] frame=new short[1280];int pending=0;
                long startedAt=SystemClock.elapsedRealtime();
                long warmupUntil=startedAt+1200;
                long deadline=warmupUntil+(captureSlot<5?4200:5200);
                float peak=0;float[] best=null;int frames=0;double energy=0;
                int speechSamples=0;
                double loudestSpeechFrame=0;
                boolean prompted=false;
                while(SystemClock.elapsedRealtime()<deadline&&!Thread.currentThread().isInterrupted()) {
                    int count=audio.read(frame,pending,frame.length-pending);
                    if(count<=0)throw new IllegalStateException("Microphone stopped");
                    pending+=count;
                    if(pending<frame.length)continue;
                    pending=0;frames++;
                    float score=detector.process(frame);
                    if (SystemClock.elapsedRealtime()<warmupUntil) continue;
                    if (!prompted) {
                        prompted=true;
                        runOnUiThread(()->info.setText("Speak now — "+PROMPTS[captureSlot]));
                    }
                    double frameEnergy=0;
                    for(short value:frame)frameEnergy+=((double)value/32768)*((double)value/32768);
                    energy+=frameEnergy;
                    speechSamples+=frame.length;
                    if (score>peak) { peak=score; if(captureSlot<5) best=detector.featureSnapshot(); }
                    if (captureSlot>=5 && frameEnergy>loudestSpeechFrame) {
                        loudestSpeechFrame=frameEnergy;
                        best=detector.featureSnapshot();
                    }
                }
                double rms=speechSamples==0?0:Math.sqrt(energy/speechSamples);
                if(frames<15||speechSamples<16000||rms<0.003)
                    throw new IllegalStateException("Microphone captured very little speech (level " +
                        Math.round(rms*1000)/10.0 + "%). Check mic source, distance, and permission.");
                if(best==null)throw new IllegalStateException("Could not extract voice features");
                float[] retained=best;
                float confidence=peak;
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed())return;
                    samples[captureSlot]=retained;
                    busy=false;
                    if(position==captureSlot&&position<PROMPTS.length-1)position++;
                    render();
                    info.setText("Generic confidence: "+Math.round(confidence*100)+"% · mic RMS level: "+
                        String.format(java.util.Locale.ROOT,"%.1f%%",rms*100)+
                        (captureSlot<5 && confidence<0.15f
                        ? " · Low model match despite captured speech. Try the other microphone source, repeat, or test a lower threshold cautiously."
                        : " · Recording complete."));
                });
            } catch(Exception problem) {
                String message=problem.getMessage();
                runOnUiThread(()->{busy=false;render();info.setText("Recording failed: "+message);});
            } finally {
                if(audio!=null){try{audio.stop();}catch(Exception ignored){}try{audio.release();}catch(RuntimeException ignored){}}
                activeRecorder = null;
            }
        });
    }

    private void train() {
        if(busy)return;
        List<float[]> positives=new ArrayList<>(),negatives=new ArrayList<>();
        for(int i=0;i<samples.length;i++) {
            if(samples[i]==null)continue;
            (i<5?positives:negatives).add(samples[i]);
        }
        if(positives.size()<4||negatives.size()<2) {
            info.setText("Record at least four wake phrases and two other-speech phrases first");return;
        }
        busy=true;render();
        worker.execute(()->{
            try {
                JarvisProfile.train(this,positives,negatives);
                runOnUiThread(()->{busy=false;render();info.setText("Wake-phrase calibration saved locally. It is not secure speaker identification. Exit to test activation.");});
            }catch(Exception issue){
                runOnUiThread(()->{busy=false;render();info.setText("Training failed: "+issue.getMessage());});
            }
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("position", position);
        for (int i=0; i<samples.length; i++) if (samples[i]!=null)
            out.putFloatArray("sample"+i, samples[i]);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        destroying = true;
        worker.shutdownNow();
        AudioRecord held = activeRecorder;
        if (held != null) {
            try { held.stop(); } catch (RuntimeException ignored) { }
        }
        Thread cleanup = new Thread(() -> {
            boolean terminated=false;
            try {
                terminated=worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!terminated)
                    android.util.Log.e("JarvisEnrollment","Enrollment worker did not stop; wake-word remains paused to prevent microphone/model overlap");
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if(terminated)WakeWordService.resumeAfterEnrollment();
        }, "JarvisEnrollmentCleanup");
        cleanup.setDaemon(true);
        cleanup.start();
        super.onDestroy();
    }
}
