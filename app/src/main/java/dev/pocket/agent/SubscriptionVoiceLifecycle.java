package dev.pocket.agent;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * UI/Android-independent coordinator for a subscription realtime voice session.
 * A wake or speak_to_user request opens exactly one transport; all tool work stays
 * with the existing Pi main thread. Does not hold microphone/network resources.
 */
final class SubscriptionVoiceLifecycle {
    interface Port {
        void connect(long epoch, boolean allowMicrophone);
        void close(long epoch);
        void sendToPi(String turnId, String utterance);
        void cancelPi(String turnId);
        void sendSpeech(String delegationId, String text);
        void interruptSpeech();
        void status(String text);
        default void failure(String safeMessage) { }
        void announcementFailed(String id, String reason);
    }

    private static final long IDLE_MS = 3000L;
    // Initial capture may precede the first delayed live transcript fragment.
    private static final long FIRST_UTTERANCE_MS = 12_000L;
    private static final long MAX_UTTERANCE_MS = 60_000L;
    // Allow VAD finalization and delegation.created to arrive after speech stops.
    private static final long POST_SPEECH_MS = 8_000L;
    // Includes a bounded OAuth refresh followed by peer/sideband negotiation.
    private static final long CONNECT_MS = 45000L;
    private static final int MAX_ANNOUNCEMENTS = 8;
    private static final int MAX_OUTPUT_CHARS = 8000;
    enum State { CLOSED, CONNECTING, LISTENING, WAITING_PI, SPEAKING }
    private final Port port;
    private final ArrayDeque<Announcement> announcements = new ArrayDeque<>();
    private final Set<String> delivered = new HashSet<>();
    private State state = State.CLOSED;
    private long epoch, deadline;
    private String turnId, delegationId;
    private StringBuilder output;
    private boolean listenAfterSpeech = true;
    private boolean allowMicrophone;
    private boolean speechActive;
    private String activeAnnouncementId;

    private static final class Announcement {
        final String id;
        final String text;
        final boolean listenAfter;
        Announcement(String id, String text, boolean listenAfter) {
            this.id = id;
            this.text = text;
            this.listenAfter = listenAfter;
        }
    }

    SubscriptionVoiceLifecycle(Port port) { this.port = port; }
    State state() { return state; }
    long epoch() { return epoch; }
    boolean microphoneAllowed() { return allowMicrophone; }

    void wake(long nowMs) {
        // A speech-only connection has no input track. A later explicit wake must
        // establish a new microphone-authorized peer, never silently record.
        if (state != State.CLOSED && !allowMicrophone) close();
        if (state == State.CLOSED) connect(nowMs, true);
        else if (state == State.LISTENING) deadline = nowMs + IDLE_MS;
    }

    boolean announce(String id, String text, boolean listenAfter, long nowMs) {
        if (id == null || id.isEmpty() || text == null || text.trim().isEmpty() ||
                text.length() > 800 || announcements.size() >= MAX_ANNOUNCEMENTS ||
                (state != State.CLOSED && !allowMicrophone && listenAfter)) return false;
        announcements.addLast(new Announcement(id, text.trim(), listenAfter));
        if (state == State.CLOSED) connect(nowMs, listenAfter);
        else if (state == State.LISTENING) playAnnouncement();
        return true;
    }

    private void connect(long nowMs, boolean microphone) {
        state = State.CONNECTING;
        epoch++;
        allowMicrophone = microphone;
        speechActive = false;
        deadline = nowMs + CONNECT_MS;
        delivered.clear();
        port.status("Connecting subscription voice");
        port.connect(epoch, microphone);
    }

    void connected(long id, long nowMs) {
        if (state != State.CONNECTING || id != epoch) return;
        state = State.LISTENING;
        deadline = nowMs + (allowMicrophone ? FIRST_UTTERANCE_MS : IDLE_MS);
        port.status(allowMicrophone ? "Ready · speak after the cue" : "Preparing announcement");
        playAnnouncement();
        if (!allowMicrophone && announcements.isEmpty() && state == State.LISTENING) close();
    }

    /** GPT-Live transcript fragments are meaningful speech evidence, not PCM silence. */
    void transcriptActivity(long id, String text, long nowMs) {
        if (id != epoch || !allowMicrophone || state == State.CLOSED ||
                text == null || text.isBlank()) return;
        if (state == State.SPEAKING) inputInterrupted(id, nowMs);
        if (state == State.LISTENING)
            deadline = speechActive ? Math.max(deadline, nowMs + POST_SPEECH_MS)
                : nowMs + POST_SPEECH_MS;
    }

    /** Called for new or continuing input transcript and voice activity. */
    void activity(long id, long nowMs) {
        if (id != epoch || state == State.CLOSED) return;
        if (state == State.LISTENING && !speechActive) deadline = nowMs + IDLE_MS;
    }

    void speechStarted(long id, long nowMs) {
        if (id != epoch || !allowMicrophone || state == State.CLOSED) return;
        speechActive = true;
        if (state == State.LISTENING) deadline = nowMs + MAX_UTTERANCE_MS;
    }

    void speechStopped(long id, long nowMs) {
        if (id != epoch || !speechActive) return;
        speechActive = false;
        if (state == State.LISTENING) deadline = nowMs + POST_SPEECH_MS;
    }

    void delegated(long id, String requestId, String transcript) {
        if (id != epoch || state == State.CLOSED || state == State.CONNECTING ||
                requestId == null || requestId.isEmpty() ||
                transcript == null || transcript.trim().isEmpty() ||
                !delivered.add(requestId)) return;
        if (state == State.SPEAKING) {
            port.interruptSpeech();
            if (activeAnnouncementId != null) {
                port.announcementFailed(activeAnnouncementId, "Announcement interrupted by new voice request");
                activeAnnouncementId = null;
            }
        }
        if (turnId != null) port.cancelPi(turnId);
        delegationId = requestId;
        // Pi accepts only UUID-shaped voice session IDs for scoped cancellation.
        turnId = UUID.randomUUID().toString();
        output = new StringBuilder();
        state = State.WAITING_PI;
        deadline = Long.MAX_VALUE;
        port.status("Pi is working");
        port.sendToPi(turnId, transcript.trim());
    }

    void piDelta(String id, String text) {
        if (state != State.WAITING_PI || !matches(id) || text == null || output == null) return;
        if (output.length() + text.length() <= MAX_OUTPUT_CHARS) output.append(text);
    }

    void piFinished(String id) {
        if (state != State.WAITING_PI || !matches(id)) return;
        String spoken = output == null ? "" : output.toString().trim();
        String delegation = delegationId;
        turnId = null;
        delegationId = null;
        output = null;
        listenAfterSpeech = true;
        state = State.SPEAKING;
        port.status("Speaking");
        port.sendSpeech(delegation, spoken.isEmpty()
            ? "I couldn't complete that request." : spoken);
    }

    private boolean matches(String id) { return id != null && id.equals(turnId); }

    private void playAnnouncement() {
        if (state != State.LISTENING || announcements.isEmpty()) return;
        Announcement announcement = announcements.removeFirst();
        activeAnnouncementId = announcement.id;
        state = State.SPEAKING;
        deadline = Long.MAX_VALUE;
        listenAfterSpeech = announcement.listenAfter;
        port.status("Speaking announcement");
        port.sendSpeech(null, announcement.text);
    }

    void playbackFinished(long id, long nowMs) {
        if (id != epoch || state != State.SPEAKING) return;
        activeAnnouncementId = null; // This announcement reached the playback-complete event.
        if (!announcements.isEmpty()) {
            state = State.LISTENING;
            playAnnouncement();
        } else if (!listenAfterSpeech) close();
        else {
            state = State.LISTENING;
            deadline = nowMs + IDLE_MS;
            port.status("Listening for follow-up");
        }
    }

    void inputInterrupted(long id, long nowMs) {
        if (id != epoch || state == State.CLOSED || state == State.CONNECTING) return;
        if (state == State.SPEAKING) {
            port.interruptSpeech();
            if (activeAnnouncementId != null) {
                port.announcementFailed(activeAnnouncementId, "Announcement interrupted before playback completion");
                activeAnnouncementId = null;
            }
            state = State.LISTENING;
            deadline = nowMs + (speechActive ? MAX_UTTERANCE_MS : IDLE_MS);
        }
        if (state == State.WAITING_PI && turnId != null) {
            port.cancelPi(turnId);
            turnId = null;
            output = null;
            delegationId = null;
            state = State.LISTENING;
            deadline = nowMs + IDLE_MS;
        }
    }

    void tick(long nowMs) {
        if (state != State.CLOSED && nowMs >= deadline) {
            if (state == State.CONNECTING) failed(epoch, "Voice connection timed out");
            else close();
        }
    }

    void disconnected(long id) {
        if (id == epoch && state != State.CLOSED)
            failed(id, "Voice connection ended unexpectedly");
    }

    void failed(long id, String safeMessage) {
        if (id != epoch || state == State.CLOSED) return;
        close();
        // Make the actionable failure the final state, after generic disconnect cleanup.
        port.failure(safeMessage);
    }

    void close() {
        if (state == State.CLOSED) return;
        long oldEpoch = epoch;
        state = State.CLOSED;
        deadline = 0;
        // Stop only the voice-scoped main turn. Other Pi workers must survive.
        if (turnId != null) port.cancelPi(turnId);
        turnId = null;
        delegationId = null;
        output = null;
        if (activeAnnouncementId != null) {
            port.announcementFailed(activeAnnouncementId, "Subscription speech ended before playback completed");
            activeAnnouncementId = null;
        }
        while (!announcements.isEmpty()) {
            port.announcementFailed(announcements.removeFirst().id, "Subscription speech queue closed before playback");
        }
        delivered.clear();
        speechActive = false;
        port.close(oldEpoch);
        port.status("Voice disconnected");
    }
}
