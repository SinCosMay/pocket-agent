package dev.pocket.agent;

import android.content.Context;
import android.util.AtomicFile;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** Optional user-specific verifier; no recordings leave the device. */
final class JarvisProfile {
    static final class Profile {
        final float[] positive, negative;
        final float threshold;
        Profile(float[] positive, float[] negative, float threshold) {
            this.positive = positive; this.negative = negative; this.threshold = threshold;
        }
        float adjust(float[] features, float baseScore) {
            float margin = similarity(features, positive) - similarity(features, negative);
            return margin >= threshold ? baseScore : 0f;
        }
    }

    private static File path(Context context) {
        return new File(context.getFilesDir(), "hey-jarvis-profile.bin");
    }

    static Profile read(Context context) {
        File file = path(context);
        if (!file.exists()) return null;
        try (DataInputStream in = new DataInputStream(new AtomicFile(file).openRead())) {
            if (in.readInt() != 0x4A415256 || in.readInt() != JarvisDetector.FEATURE_COUNT)
                return null;
            float threshold = in.readFloat();
            float[] positive = new float[JarvisDetector.FEATURE_COUNT];
            float[] negative = new float[JarvisDetector.FEATURE_COUNT];
            for (int i = 0; i < positive.length; i++) positive[i] = in.readFloat();
            for (int i = 0; i < negative.length; i++) negative[i] = in.readFloat();
            return new Profile(positive, negative, threshold);
        } catch (IOException error) { return null; }
    }

    static Profile train(Context context, List<float[]> positives, List<float[]> negatives) throws IOException {
        if (positives.size() < 4 || negatives.size() < 2)
            throw new IllegalArgumentException("Record at least 4 wake phrases and 2 unrelated phrases");
        float[] positive = centroid(positives);
        float[] negative = centroid(negatives);
        float minimumPositive = Float.POSITIVE_INFINITY;
        float maximumNegative = Float.NEGATIVE_INFINITY;
        for (float[] v : positives)
            minimumPositive = Math.min(minimumPositive, similarity(v, positive) - similarity(v, negative));
        for (float[] v : negatives)
            maximumNegative = Math.max(maximumNegative, similarity(v, positive) - similarity(v, negative));
        if (!(minimumPositive > maximumNegative))
            throw new IllegalArgumentException("Samples overlap; retake the phrases in a quieter environment");
        Profile profile = new Profile(positive, negative, (minimumPositive + maximumNegative) * 0.5f);
        File profilePath = path(context);
        File directory = profilePath.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs()))
            throw new IOException("Cannot create wake-word profile directory");
        AtomicFile file = new AtomicFile(profilePath);
        java.io.FileOutputStream output = file.startWrite();
        try {
            DataOutputStream data = new DataOutputStream(output);
            data.writeInt(0x4A415256);
            data.writeInt(JarvisDetector.FEATURE_COUNT);
            data.writeFloat(profile.threshold);
            for (float v : positive) data.writeFloat(v);
            for (float v : negative) data.writeFloat(v);
            data.flush();
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
        return profile;
    }

    static void delete(Context context) { new AtomicFile(path(context)).delete(); }

    private static float[] centroid(List<float[]> inputs) {
        float[] average = new float[JarvisDetector.FEATURE_COUNT];
        for (float[] row : inputs) {
            if (row.length != average.length) throw new IllegalArgumentException("Invalid embedding");
            float norm = norm(row);
            if (norm <= 0) throw new IllegalArgumentException("Recordings contain no speech");
            for (int i = 0; i < average.length; i++) average[i] += row[i] / norm / inputs.size();
        }
        return average;
    }

    private static float norm(float[] values) {
        double total = 0;
        for (float value : values) total += value * value;
        return (float) Math.sqrt(total);
    }

    private static float similarity(float[] sample, float[] template) {
        double product = 0;
        for (int i = 0; i < sample.length; i++) product += sample[i] * template[i];
        float denom = norm(sample) * norm(template);
        return denom == 0 ? 0f : (float) (product / denom);
    }

    private JarvisProfile() { }
}
