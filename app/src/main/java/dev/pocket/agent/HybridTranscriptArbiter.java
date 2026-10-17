package dev.pocket.agent;

/**
 * Chooses exactly one transcript for a spoken turn.
 *
 * Android system recognition owns turns that end strictly before three seconds.
 * Deepgram owns turns that reach the three-second boundary, and remains the fallback
 * whenever system recognition is unavailable or fails.
 */
final class HybridTranscriptArbiter {
    static final long SHORT_UTTERANCE_MS = 3000L;

    enum Source { SYSTEM, DEEPGRAM }

    static final class Choice {
        final String text;
        final Source source;

        Choice(String text, Source source) {
            this.text = text;
            this.source = source;
        }
    }

    private long generation;
    private boolean systemEnabled;
    private long speechStartedAt = -1L;
    private long systemSpeechEndedAt = -1L;
    private String pendingDeepgram;
    private boolean deepgramTurnOpen;
    private boolean deepgramEnded;
    private boolean resolved;
    private boolean waitingForMatchingDeepgramFinal;

    HybridTranscriptArbiter(boolean systemEnabled) {
        reset(1L, systemEnabled);
    }

    synchronized void reset(long generation, boolean enabled) {
        this.generation = generation;
        systemEnabled = enabled;
        speechStartedAt = -1L;
        systemSpeechEndedAt = -1L;
        pendingDeepgram = null;
        deepgramTurnOpen = false;
        deepgramEnded = false;
        resolved = false;
        waitingForMatchingDeepgramFinal = false;
    }

    synchronized long generation() {
        return generation;
    }

    synchronized long speechStarted(long gen, long nowMs) {
        if (gen != generation || resolved) return -1L;
        if (speechStartedAt < 0L || nowMs < speechStartedAt) speechStartedAt = nowMs;
        return Math.max(0L, SHORT_UTTERANCE_MS - (nowMs - speechStartedAt));
    }

    synchronized boolean systemSpeechEnded(long gen, long nowMs) {
        if (gen != generation || !systemEnabled || resolved) return false;
        systemSpeechEndedAt = nowMs;
        if (speechStartedAt < 0L) {
            systemEnabled = false;
            return false;
        }
        if (systemSpeechEndedAt - speechStartedAt >= SHORT_UTTERANCE_MS) {
            systemEnabled = false;
            return false;
        }
        return true;
    }

    synchronized Choice systemFinal(long gen, String text, long nowMs) {
        if (gen != generation || resolved || !systemEnabled || text == null || text.trim().isEmpty()) return null;
        long effectiveEnd = systemSpeechEndedAt >= 0L ? systemSpeechEndedAt : nowMs;
        if (speechStartedAt < 0L || effectiveEnd - speechStartedAt >= SHORT_UTTERANCE_MS) {
            systemEnabled = false;
            return choosePendingDeepgram();
        }
        resolved = true;
        waitingForMatchingDeepgramFinal = deepgramTurnOpen && pendingDeepgram == null && !deepgramEnded;
        pendingDeepgram = null;
        return new Choice(text.trim(), Source.SYSTEM);
    }

    synchronized void deepgramSpeechStarted(long gen) {
        if (gen != generation || deepgramEnded) return;
        deepgramTurnOpen = true;
    }

    synchronized Choice deepgramFinal(long gen, String text, long nowMs) {
        if (gen != generation || text == null || text.trim().isEmpty()) return null;
        deepgramTurnOpen = false;
        deepgramEnded = true;
        if (waitingForMatchingDeepgramFinal) {
            waitingForMatchingDeepgramFinal = false;
            return null;
        }
        if (resolved) return null;

        boolean systemEndedShort = systemSpeechEndedAt >= 0L && speechStartedAt >= 0L
            && (systemSpeechEndedAt - speechStartedAt < SHORT_UTTERANCE_MS);

        if (systemEndedShort) {
            pendingDeepgram = text.trim();
            return null;
        }

        boolean longTurn = speechStartedAt >= 0L
            && nowMs - speechStartedAt >= SHORT_UTTERANCE_MS;
        boolean systemEndedLong = systemSpeechEndedAt >= 0L && speechStartedAt >= 0L
            && systemSpeechEndedAt - speechStartedAt >= SHORT_UTTERANCE_MS;
        if (!systemEnabled || longTurn || systemEndedLong) {
            resolved = true;
            return new Choice(text.trim(), Source.DEEPGRAM);
        }
        pendingDeepgram = text.trim();
        return null;
    }

    synchronized Choice thresholdReached(long gen, long nowMs) {
        if (gen != generation || resolved || !systemEnabled || speechStartedAt < 0L
                || nowMs - speechStartedAt < SHORT_UTTERANCE_MS) return null;
        if (systemSpeechEndedAt >= 0L && systemSpeechEndedAt - speechStartedAt < SHORT_UTTERANCE_MS) {
            return null;
        }
        systemEnabled = false;
        return choosePendingDeepgram();
    }

    synchronized Choice systemFailed(long gen) {
        if (gen != generation || resolved) return null;
        systemEnabled = false;
        Choice fallback = choosePendingDeepgram();
        if (fallback == null && deepgramEnded) resolved = true;
        return fallback;
    }

    synchronized void deepgramEmptyTurn(long gen) {
        if (gen != generation) return;
        deepgramTurnOpen = false;
        deepgramEnded = true;
        if (waitingForMatchingDeepgramFinal) waitingForMatchingDeepgramFinal = false;
        else if (!resolved && !systemEnabled) resolved = true;
    }

    synchronized Choice systemWaitExpired(long gen) {
        if (gen != generation || resolved) return null;
        systemEnabled = false;
        Choice fallback = choosePendingDeepgram();
        if (fallback == null && deepgramEnded) resolved = true;
        return fallback;
    }

    synchronized boolean systemEligible() {
        return systemEnabled && !resolved;
    }

    synchronized boolean turnSettled() {
        return resolved && !waitingForMatchingDeepgramFinal;
    }

    synchronized boolean waitingForMatchingDeepgramFinal() {
        return resolved && waitingForMatchingDeepgramFinal;
    }

    synchronized boolean deepgramTurnOpen() {
        return deepgramTurnOpen;
    }

    synchronized boolean matchingDeepgramWaitExpired(long gen) {
        if (gen != generation || !resolved || !waitingForMatchingDeepgramFinal) return false;
        waitingForMatchingDeepgramFinal = false;
        return true;
    }

    private Choice choosePendingDeepgram() {
        if (pendingDeepgram == null) return null;
        String text = pendingDeepgram;
        pendingDeepgram = null;
        resolved = true;
        waitingForMatchingDeepgramFinal = false;
        return new Choice(text, Source.DEEPGRAM);
    }
}
