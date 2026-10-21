package dev.pocket.agent;

/**
 * Completion guard for GPT-Live reflected audio: a pause between chunks is
 * not the end of a response until the assistant's turn has finished.
 */
final class SubscriptionVoicePlaybackGate {
    private boolean hasAudio;
    private boolean assistantFinished;
    private long lastAudioMs;

    void reset() {
        hasAudio = false;
        assistantFinished = false;
        lastAudioMs = 0;
    }

    void audio(long nowMs) {
        hasAudio = true;
        lastAudioMs = nowMs;
    }

    void finishAssistant() {
        assistantFinished = true;
    }

    boolean hasAudio() { return hasAudio; }
    boolean assistantFinished() { return assistantFinished; }

    boolean ready(long nowMs, long quietMs) {
        return hasAudio && assistantFinished && nowMs - lastAudioMs >= quietMs;
    }

    long remaining(long nowMs, long quietMs) {
        return Math.max(0L, quietMs - (nowMs - lastAudioMs));
    }
}
