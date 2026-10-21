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
        void connect(long epoch);
        void close(long epoch);
        void sendToPi(String turnId, String utterance);
        void cancelPi(String turnId);
        void sendSpeech(String delegationId, String text);
        void interruptSpeech();
        void status(String text);
    }

    private static final long IDLE_MS = 3000L;
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

    private static final class Announcement {
        final String text;
        final boolean listenAfter;
        Announcement(String text, boolean listenAfter) {
            this.text = text;
            this.listenAfter = listenAfter;
        }
    }

    SubscriptionVoiceLifecycle(Port port) { this.port = port; }
    State state() { return state; }
    long epoch() { return epoch; }

    void wake(long nowMs) {
        if (state == State.CLOSED) connect(nowMs);
        else if (state == State.LISTENING) deadline = nowMs + IDLE_MS;
    }

    boolean announce(String text, boolean listenAfter, long nowMs) {
        if (text == null || text.trim().isEmpty() || text.length() > 800 ||
                announcements.size() >= MAX_ANNOUNCEMENTS) return false;
        announcements.addLast(new Announcement(text.trim(), listenAfter));
        if (state == State.CLOSED) connect(nowMs);
        else if (state == State.LISTENING) playAnnouncement();
        return true;
    }

    private void connect(long nowMs) {
        state = State.CONNECTING;
        epoch++;
        deadline = nowMs + CONNECT_MS;
        delivered.clear();
        port.status("Connecting subscription voice");
        port.connect(epoch);
    }

    void connected(long id, long nowMs) {
        if (state != State.CONNECTING || id != epoch) return;
        state = State.LISTENING;
        deadline = nowMs + IDLE_MS;
        port.status("Listening");
        playAnnouncement();
    }

    /** Called for new or continuing input transcript and voice activity. */
    void activity(long id, long nowMs) {
        if (id != epoch || state == State.CLOSED) return;
        if (state == State.LISTENING) deadline = nowMs + IDLE_MS;
    }

    void delegated(long id, String requestId, String transcript) {
        if (id != epoch || state == State.CLOSED || state == State.CONNECTING ||
                requestId == null || requestId.isEmpty() ||
                transcript == null || transcript.trim().isEmpty() ||
                !delivered.add(requestId)) return;
        if (state == State.SPEAKING) port.interruptSpeech();
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
        state = State.SPEAKING;
        deadline = Long.MAX_VALUE;
        listenAfterSpeech = announcement.listenAfter;
        port.status("Speaking announcement");
        port.sendSpeech(null, announcement.text);
    }

    void playbackFinished(long id, long nowMs) {
        if (id != epoch || state != State.SPEAKING) return;
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
            state = State.LISTENING;
            deadline = nowMs + IDLE_MS;
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
        if (state != State.CLOSED && nowMs >= deadline) close();
    }

    void disconnected(long id) { if (id == epoch) close(); }

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
        announcements.clear();
        delivered.clear();
        port.close(oldEpoch);
        port.status("Voice disconnected");
    }
}
