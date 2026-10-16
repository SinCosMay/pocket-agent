package dev.pocket.agent;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Session-local, memory-only microphone frames. A full queue is an error rather than
 * silently dropping the beginning of an utterance. Only the publisher removes frames.
 * Callers hold their session lock while reading or writing this queue.
 */
final class PcmCaptureQueue {
    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();
    private final int limitBytes;
    private int queuedBytes;

    PcmCaptureQueue(int limitBytes) {
        if (limitBytes <= 0) throw new IllegalArgumentException("limitBytes");
        this.limitBytes = limitBytes;
    }

    boolean offer(byte[] samples, int length) {
        if (length <= 0 || length > samples.length) throw new IllegalArgumentException("length");
        if (length > limitBytes - queuedBytes) return false;
        frames.addLast(Arrays.copyOf(samples, length));
        queuedBytes += length;
        return true;
    }

    byte[] peek() { return frames.peekFirst(); }

    void removeFirst() {
        byte[] first = frames.removeFirst();
        queuedBytes -= first.length;
    }

    boolean isEmpty() { return frames.isEmpty(); }
    int queuedBytes() { return queuedBytes; }

    void clear() {
        frames.clear();
        queuedBytes = 0;
    }
}
