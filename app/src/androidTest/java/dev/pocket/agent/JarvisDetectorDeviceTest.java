package dev.pocket.agent;

import android.content.Context;
import android.content.ContextWrapper;
import java.io.File;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Executes the actual Android ONNX pipeline on device, not a Java score stub. */
@RunWith(AndroidJUnit4.class)
public final class JarvisDetectorDeviceTest {
    private float peak(JarvisDetector engine, String sample) throws Exception {
        Context instrumentation = InstrumentationRegistry.getInstrumentation().getContext();
        byte[] wav;
        try (InputStream stream = instrumentation.getAssets().open(sample)) {
            wav = stream.readAllBytes();
        }
        float max = 0;
        short[] quiet = new short[1280];
        for(int i=0;i<16;i++) max=Math.max(max,engine.process(quiet));
        for (int i=0; i+2559<wav.length; i+=2560) {
            short[] pcm=new short[1280];
            for(int k=0;k<1280;k++) {
                int lo=wav[i+2*k]&255,hi=wav[i+2*k+1];
                pcm[k]=(short)((hi<<8)|lo);
            }
            max=Math.max(max,engine.process(pcm));
        }
        return max;
    }

    @Test public void heyJarvisCrosses75PercentAndOrdinarySpeechDoesNot() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(JarvisDetector engine=new JarvisDetector(context)) {
            float detected=peak(engine,"hey_jarvis.pcm");
            assertTrue("Local ONNX should detect Hey Jarvis; got "+detected,detected>=0.75f);
        }
        try(JarvisDetector engine=new JarvisDetector(context)) {
            float unrelated=peak(engine,"ordinary_speech.pcm");
            assertTrue("Ordinary speech should not trigger; got "+unrelated,unrelated<0.75f);
        }
    }

    @Test public void personalizedVoiceProfileSeparatesSamples() throws Exception {
        Context modelContext=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File testDirectory = new File(modelContext.getCacheDir(), "jarvis-test-enrollment");
        Context storage = new ContextWrapper(modelContext) {
            @Override public File getFilesDir() { return testDirectory; }
        };
        List<float[]> positive=new ArrayList<>(),negative=new ArrayList<>();
        for(int i=0;i<4;i++) {
            try(JarvisDetector engine=new JarvisDetector(modelContext)) {
                peak(engine,"hey_jarvis.pcm");
                positive.add(engine.featureSnapshot());
            }
        }
        for(int i=0;i<2;i++) {
            try(JarvisDetector engine=new JarvisDetector(modelContext)) {
                peak(engine,"ordinary_speech.pcm");
                negative.add(engine.featureSnapshot());
            }
        }
        try {
            JarvisProfile.Profile profile=JarvisProfile.train(storage,positive,negative);
            assertTrue(profile.adjust(positive.get(0),0.95f)>=0.75f);
            assertEquals(0f,profile.adjust(negative.get(0),0.95f),0.0001f);
        } finally { JarvisProfile.delete(storage); }
    }
}
