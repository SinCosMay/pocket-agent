package dev.pocket.agent;

/**
 * Chooses exactly one transcript for a spoken turn.
 *
 * Android system recognition owns turns that end strictly before the configured short-turn
 * threshold. Deepgram owns turns at or beyond the threshold, and remains the fallback whenever
 * system recognition is unavailable, fails, or does not finish within a bounded grace period.
 */
final class HybridTranscriptArbiter {
    static final long DEFAULT_SHORT_UTTERANCE_MS = 3000L;

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
    private long shortUtteranceMs;
    private boolean systemEnabled;
    private long speechStartedAt = -1L;
    private long systemSpeechEndedAt = -1L;
    private String pendingDeepgram;
    private boolean deepgramTurnOpen;
    private boolean deepgramEnded;
    private boolean resolved;
    private boolean waitingForMatchingDeepgramFinal;

    HybridTranscriptArbiter(boolean systemEnabled) {
        this(systemEnabled, DEFAULT_SHORT_UTTERANCE_MS);
    }

    HybridTranscriptArbiter(boolean systemEnabled, long shortUtteranceMs) {
        reset(1L, systemEnabled, shortUtteranceMs);
    }

    synchronized void reset(long generation, boolean enabled) {
        reset(generation, enabled, shortUtteranceMs);
    }

    synchronized void reset(long generation, boolean enabled, long shortUtteranceMs) {
        if (shortUtteranceMs <= 0L)
            throw new IllegalArgumentException("Short utterance threshold must be positive");
        this.generation = generation;
        this.shortUtteranceMs = shortUtteranceMs;
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
        return Math.max(0L, shortUtteranceMs - (nowMs - speechStartedAt));
    }

    synchronized boolean systemSpeechEnded(long gen, long nowMs) {
        if (gen != generation || !systemEnabled || resolved) return false;
        systemSpeechEndedAt = nowMs;
        if (speechStartedAt < 0L) {
            systemEnabled = false;
            return false;
        }
        if (systemSpeechEndedAt - speechStartedAt >= shortUtteranceMs) {
            systemEnabled = false;
            return false;
        }
        return true;
    }

    synchronized Choice systemFinal(long gen, String text, long nowMs) {
        if (gen != generation || resolved || !systemEnabled || text == null || text.trim().isEmpty()) return null;
        long effectiveEnd = systemSpeechEndedAt >= 0L ? systemSpeechEndedAt : nowMs;
        if (speechStartedAt < 0L || effectiveEnd - speechStartedAt >= shortUtteranceMs) {
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
            && (systemSpeechEndedAt - speechStartedAt < shortUtteranceMs);
        boolean systemEndedLong = systemSpeechEndedAt >= 0L && speechStartedAt >= 0L
            && (systemSpeechEndedAt - speechStartedAt >= shortUtteranceMs);

        if (!systemEnabled || systemEndedLong) {
            resolved = true;
            return new Choice(text.trim(), Source.DEEPGRAM);
        }

        // Do not let Deepgram's wall-clock completion time preempt Android. Android and
        // Deepgram are fed the same PCM concurrently; while Android is still evaluating the
        // utterance, keep Deepgram's final buffered. A bounded grace timeout releases it if
        // the Android recognizer stalls.
        if (systemSpeechEndedAt < 0L || systemEndedShort) {
            pendingDeepgram = text.trim();
            return null;
        }

        resolved = true;
        return new Choice(text.trim(), Source.DEEPGRAM);
    }

    synchronized Choice thresholdReached(long gen, long nowMs) {
        if (gen != generation || resolved || !systemEnabled || speechStartedAt < 0L
                || nowMs - speechStartedAt < shortUtteranceMs) return null;
        if (systemSpeechEndedAt >= 0L && systemSpeechEndedAt - speechStartedAt < shortUtteranceMs) {
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
