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
        subtitle.setText("Calibrate the wake phrase locally to reduce accidental triggers. This is not speaker identity verification. Say each prompted phrase, then try waking Pocket Agent. Raw audio is not retained.");
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
                audio=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(size,4096));
                if(audio.getState()!=AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("Microphone unavailable");
                activeRecorder = audio;
                if (destroying || Thread.currentThread().isInterrupted()) return;
                audio.startRecording();
                if(audio.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                    throw new IllegalStateException("Microphone is busy");
                runOnUiThread(()->info.setText("Listening now — "+PROMPTS[captureSlot]));
                short[] frame=new short[1280];int pending=0;
                long deadline=SystemClock.elapsedRealtime()+(captureSlot<5?3400:4800);
                float peak=0;float[] best=null;int frames=0;double energy=0;
                double loudestSpeechFrame = 0;
                while(SystemClock.elapsedRealtime()<deadline&&!Thread.currentThread().isInterrupted()) {
                    int count=audio.read(frame,pending,frame.length-pending);
                    if(count<=0)throw new IllegalStateException("Microphone stopped");
                    for(int i=pending;i<pending+count;i++)energy+=((double)frame[i]/32768)*((double)frame[i]/32768);
                    pending+=count;
                    if(pending<frame.length)continue;
                    pending=0;frames++;
                    float score=detector.process(frame);
                    if (score>peak) { peak=score; if(captureSlot<5) best=detector.featureSnapshot(); }
                    if (captureSlot>=5 && frames>=15) {
                        double frameEnergy=0;
                        for (short value:frame) frameEnergy += ((double)value/32768)*((double)value/32768);
                        if (frameEnergy>loudestSpeechFrame) {
                            loudestSpeechFrame=frameEnergy;
                            best=detector.featureSnapshot();
                        }
                    }
                }
                if(frames<15||energy<1)throw new IllegalStateException("Not enough audible speech; retake the phrase");
                if(captureSlot<5&&peak<0.15f)
                    throw new IllegalStateException("Hey Jarvis was not recognized; please retake clearly");
                if(best==null)throw new IllegalStateException("Could not extract voice features");
                float[] retained=best;
                float confidence=peak;
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed())return;
                    samples[captureSlot]=retained;
                    busy=false;
                    render();
                    info.setText("Recorded. Generic confidence: "+Math.round(confidence*100)+"%");
                    if(position==captureSlot&&position<PROMPTS.length-1){position++;render();}
                });
            } catch(Exception problem) {
                String message=problem.getMessage();
                runOnUiThread(()->{busy=false;render();info.setText("Recording failed: "+message);});
            } finally {
                if(audio!=null){try{audio.stop();}catch(Exception ignored){}audio.release();}
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
            try {
                while (!worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) { }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finally { WakeWordService.resumeAfterEnrollment(); }
        }, "JarvisEnrollmentCleanup");
        cleanup.setDaemon(true);
        cleanup.start();
        super.onDestroy();
    }
}
