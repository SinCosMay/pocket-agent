package dev.pocket.agent;

import android.content.Context;
import android.content.res.AssetManager;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Java port of the openWakeWord 0.6 streaming ONNX preprocessor. The three pretrained
 * models execute on one inference thread at 80 ms intervals; no network or LLM runs here.
 */
final class JarvisDetector implements AutoCloseable {
    static final int SAMPLE_RATE = 16000;
    static final int FRAME_SAMPLES = 1280;
    static final float DEFAULT_THRESHOLD = 0.75f;
    static final int FEATURE_COUNT = 16 * 96;

    private final OrtEnvironment environment;
    private final OrtSession mel, embedding, wake;
    private final ArrayDeque<float[]> melHistory = new ArrayDeque<>(76);
    private final ArrayDeque<float[]> features = new ArrayDeque<>(16);
    private final float[] history = new float[1760];
    private float[] recentFeatures = new float[FEATURE_COUNT];
    private int frames;
    private float lastScore;

    JarvisDetector(Context context) throws Exception {
        environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setInterOpNumThreads(1);
            options.setIntraOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            mel = environment.createSession(assetFile(context, "melspectrogram.onnx").getAbsolutePath(), options);
            embedding = environment.createSession(assetFile(context, "embedding_model.onnx").getAbsolutePath(), options);
            wake = environment.createSession(assetFile(context, "hey_jarvis_v0.1.onnx").getAbsolutePath(), options);
        }
        reset();
    }

    void reset() {
        melHistory.clear();
        features.clear();
        frames = 0;
        lastScore = 0;
        Arrays.fill(history, 0f);
        for (int i = 0; i < 76; i++) {
            float[] initial = new float[32];
            Arrays.fill(initial, 1f);
            melHistory.addLast(initial);
        }
        for (int i = 0; i < 16; i++) features.addLast(new float[96]);
    }

    private static File assetFile(Context context, String name) throws Exception {
        File dest = new File(new File(context.getFilesDir(), "wake-models"), name);
        if (dest.exists() && dest.length() > 10000) return dest;
        File directory = dest.getParentFile();
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Model directory unavailable");
        File temp = new File(directory, name + ".copying");
        try (InputStream in = context.getAssets().open("wakeword/" + name);
             FileOutputStream output = new FileOutputStream(temp)) {
            byte[] bytes = new byte[16 * 1024];
            for (int n; (n = in.read(bytes)) != -1;) output.write(bytes, 0, n);
            output.getFD().sync();
        }
        if (!temp.renameTo(dest)) throw new IllegalStateException("Cannot install local wake-word model");
        return dest;
    }

    float process(short[] pcm) throws OrtException {
        if (pcm.length != FRAME_SAMPLES) throw new IllegalArgumentException("Expected 80 ms PCM16");
        System.arraycopy(history, FRAME_SAMPLES, history, 0, history.length - FRAME_SAMPLES);
        for (int i = 0; i < pcm.length; i++) history[history.length - FRAME_SAMPLES + i] = pcm[i];

        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(history),
                new long[]{1, history.length});
             OrtSession.Result result = mel.run(java.util.Collections.singletonMap("input", input))) {
            float[][][][] spec = (float[][][][]) result.get(0).getValue();
            for (int i = 0; i < spec[0][0].length; i++) {
                float[] transformed = new float[32];
                for (int j = 0; j < 32; j++) transformed[j] = spec[0][0][i][j] / 10f + 2f;
                melHistory.addLast(transformed);
                melHistory.removeFirst();
            }
        }

        float[] melInput = new float[76 * 32];
        int i = 0;
        for (float[] row : melHistory) for (float value : row) melInput[i++] = value;
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(melInput),
                new long[]{1, 76, 32, 1});
             OrtSession.Result result = embedding.run(java.util.Collections.singletonMap("input_1", input))) {
            float[][][][] vectors = (float[][][][]) result.get(0).getValue();
            float[] vec = vectors[0][0][0];
            features.addLast(vec.clone());
            features.removeFirst();
        }

        i = 0;
        for (float[] row : features) for (float value : row) recentFeatures[i++] = value;
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(recentFeatures),
                new long[]{1, 16, 96});
             OrtSession.Result result = wake.run(java.util.Collections.singletonMap("x.1", input))) {
            float[][] prediction = (float[][]) result.get(0).getValue();
            lastScore = ++frames <= 5 ? 0 : prediction[0][0];
        }
        return lastScore;
    }

    float[] featureSnapshot() { return recentFeatures.clone(); }
    float lastScore() { return lastScore; }

    @Override public void close() throws OrtException {
        wake.close();
        embedding.close();
        mel.close();
    }
}
