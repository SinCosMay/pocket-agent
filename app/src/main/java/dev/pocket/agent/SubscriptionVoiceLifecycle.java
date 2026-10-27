package dev.pocket.agent;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.UUID;

/** Coordinates voice delivery. Pi owns accepted tasks independently of the call. */
final class SubscriptionVoiceLifecycle {
    interface Port {
        void connect(long epoch, boolean allowMicrophone);
        void close(long epoch);
        void sendToPi(String turnId, String utterance);
        void sendSpeech(String delegationId, String text);
        default void connectionReadyCue() { }
        void status(String text);
        default boolean startInitialCapture(long epoch) { return false; }
        default void setMicrophoneEnabled(boolean enabled) { }
        default void sendUserContext(String text) { }
        default void failure(String safeMessage) { }
        void announcementFailed(String id, String reason);
    }

    private static final long CONNECT_MS = 45_000L;
    private static final int MAX_ANNOUNCEMENTS = 8;
    private static final int MAX_PENDING_TURNS = 16;
    private static final int MAX_OUTPUT_CHARS = 8000;
    enum State { CLOSED, CONNECTING, LISTENING, WAITING_PI, SPEAKING }
    private final Port port;
    private final ArrayDeque<Announcement> announcements = new ArrayDeque<>();
    private final ArrayDeque<PiTurn> turns = new ArrayDeque<>();
    private final LinkedHashSet<String> delivered = new LinkedHashSet<>();
    private State state = State.CLOSED;
    private long epoch, connectionDeadline;
    private boolean transportReady, microphoneReady, capturingInitial;
    private final ArrayDeque<String> pendingUserContext = new ArrayDeque<>();
    private boolean explicitConversation;
    private boolean listenAfterSpeech = true;
    private boolean allowMicrophone;
    private String activeAnnouncementId;

    private static final class PiTurn {
        final String id = UUID.randomUUID().toString();
        final String delegationId;
        final StringBuilder output = new StringBuilder();
        boolean finished;
        PiTurn(String delegationId) { this.delegationId = delegationId; }
    }

    private static final class Announcement {
        final String id, text;
        final boolean listenAfter;
        Announcement(String id, String text, boolean listenAfter) {
            this.id = id; this.text = text; this.listenAfter = listenAfter;
        }
    }

    SubscriptionVoiceLifecycle(Port port) { this.port = port; }
    State state() { return state; }
    long epoch() { return epoch; }
    boolean microphoneAllowed() { return allowMicrophone; }
    boolean initialCaptureActive() { return capturingInitial; }
    boolean microphoneStarted() { return microphoneReady; }
    boolean connecting(long id) { return id == epoch && state != State.CLOSED && !transportReady; }

    void wake(long nowMs) {
        if (state != State.CLOSED && !allowMicrophone) close();
        if (state == State.CLOSED) connect(nowMs, true, true);
        explicitConversation = state != State.CLOSED;
    }

    boolean announce(String id, String text, boolean listenAfter, long nowMs) {
        if (id == null || id.isEmpty() || text == null || text.trim().isEmpty() ||
                text.length() > 800 || announcements.size() >= MAX_ANNOUNCEMENTS ||
                (state != State.CLOSED && !allowMicrophone && listenAfter)) return false;
        announcements.addLast(new Announcement(id, text.trim(), listenAfter));
        if (state == State.CLOSED) connect(nowMs, listenAfter, false);
        else drain();
        return true;
    }

    private void connect(long nowMs, boolean microphone, boolean captureFirst) {
        state = State.CONNECTING;
        epoch++;
        allowMicrophone = microphone;
        transportReady = false;
        microphoneReady = false;
        pendingUserContext.clear();
        connectionDeadline = nowMs + CONNECT_MS;
        delivered.clear();
        port.status("Connecting subscription voice");
        // Start Android recognition before OAuth/SDP work. Native WebRTC recording
        // remains disabled until this recognizer releases the microphone.
        capturingInitial = captureFirst && port.startInitialCapture(epoch);
        port.connect(epoch, microphone);
    }

    void connected(long id, long nowMs) {
        if (!connecting(id)) return;
        transportReady = true;
        if (state == State.CONNECTING) state = State.LISTENING;
        sendUserContext();
        port.setMicrophoneEnabled(allowMicrophone && !capturingInitial);
        drain();
    }

    void initialCaptured(long id, String text) {
        if (id != epoch || state == State.CLOSED || !capturingInitial) return;
        capturingInitial = false;
        if (text != null && !text.trim().isEmpty()) {
            String transcript = text.trim();
            // Dispatch immediately, even if the voice connection is still starting.
            // AgentService durably queues this like an ordinary chat request.
            submit(null, transcript);
            if (state != State.CLOSED) pendingUserContext.addLast(transcript);
        }
        sendUserContext();
        if (transportReady) port.setMicrophoneEnabled(allowMicrophone);
        drain();
    }

    void initialCaptureUnavailable(long id) { initialCaptured(id, null); }

    void microphoneReady(long id) {
        if (id != epoch || state == State.CLOSED || !transportReady
                || !allowMicrophone || capturingInitial || microphoneReady) return;
        microphoneReady = true;
        port.connectionReadyCue();
        drain();
    }

    private void sendUserContext() {
        while (transportReady && !pendingUserContext.isEmpty())
            port.sendUserContext(pendingUserContext.removeFirst());
    }

    /** Typed requests use the same main-thread delivery as spoken requests. */
    boolean typed(String text) {
        if (state == State.CLOSED || !explicitConversation || text == null || text.trim().isEmpty()) return false;
        if (!submit(null, text.trim())) return false;
        pendingUserContext.addLast(text.trim());
        sendUserContext();
        drain();
        return true;
    }

    void delegated(long id, String requestId, String transcript) {
        if (id != epoch || !transportReady || state == State.CLOSED ||
                requestId == null || requestId.isEmpty() ||
                transcript == null || transcript.trim().isEmpty() ||
                !delivered.add(requestId)) return;
        if (delivered.size() > 512) delivered.remove(delivered.iterator().next());
        submit(requestId, transcript.trim());
        drain();
    }

    private boolean submit(String delegationId, String text) {
        if (turns.size() >= MAX_PENDING_TURNS) {
            failed(epoch, "Voice request queue is full; use the chat to continue");
            return false;
        }
        PiTurn turn = new PiTurn(delegationId);
        turns.addLast(turn);
        if (state != State.SPEAKING) state = State.WAITING_PI;
        port.status(transportReady ? "Pi is working · voice stays connected"
            : "Pi is working · connecting voice");
        port.sendToPi(turn.id, text);
        return state != State.CLOSED;
    }

    void piDelta(String id, String text) {
        PiTurn turn = findTurn(id);
        if (turn == null || turn.finished || text == null) return;
        if (turn.output.length() + text.length() <= MAX_OUTPUT_CHARS) turn.output.append(text);
    }

    void piFinished(String id) {
        PiTurn turn = findTurn(id);
        if (turn == null || turn.finished) return;
        turn.finished = true;
        drain();
    }

    private PiTurn findTurn(String id) {
        if (id == null) return null;
        for (PiTurn turn : turns) if (turn.id.equals(id)) return turn;
        return null;
    }

    private void drain() {
        if (state == State.CLOSED || !transportReady) return;
        if (capturingInitial) {
            port.status("Listening to your first request");
            return;
        }
        if (allowMicrophone && !microphoneReady) {
            port.status("Starting voice microphone");
            return;
        }
        if (allowMicrophone) {
            // The native live session owns speech and turn-taking. Forward each
            // completed result even if another result is still being spoken;
            // do not wait for audio deltas, silence, VAD, or local playback IDs.
            for (PiTurn turn : new java.util.ArrayList<>(turns)) {
                if (!turn.finished) continue;
                turns.remove(turn);
                String text = turn.output.toString().trim();
                port.sendSpeech(turn.delegationId, text.isEmpty()
                    ? "I couldn't complete that request." : text);
                if (state == State.CLOSED) return;
            }
            while (!announcements.isEmpty()) {
                Announcement announcement = announcements.removeFirst();
                port.sendSpeech(null, announcement.text);
                if (state == State.CLOSED) return;
            }
            state = turns.isEmpty() ? State.LISTENING : State.WAITING_PI;
            port.status(turns.isEmpty() ? "Listening · you can speak during replies"
                : "Pi is working · you can keep talking");
            return;
        }
        // Output-only system TTS has real utterance-completion callbacks.
        if (state == State.SPEAKING) return;
        if (!turns.isEmpty() && turns.peekFirst().finished) {
            PiTurn turn = turns.removeFirst();
            String text = turn.output.toString().trim();
            state = State.SPEAKING;
            listenAfterSpeech = true;
            port.status("Speaking");
            port.sendSpeech(turn.delegationId, text.isEmpty()
                ? "I couldn't complete that request." : text);
        } else if (!announcements.isEmpty()) {
            Announcement announcement = announcements.removeFirst();
            activeAnnouncementId = announcement.id;
            state = State.SPEAKING;
            listenAfterSpeech = announcement.listenAfter;
            port.status("Speaking announcement");
            port.sendSpeech(null, announcement.text);
        } else {
            state = turns.isEmpty() ? State.LISTENING : State.WAITING_PI;
            port.status(turns.isEmpty() ? "Listening · Disconnect voice to finish"
                : "Pi is working · voice stays connected");
        }
    }

    void playbackFinished(long id, long nowMs) {
        if (id != epoch || state != State.SPEAKING) return;
        activeAnnouncementId = null;
        state = State.LISTENING;
        if (!explicitConversation && !listenAfterSpeech && announcements.isEmpty() && turns.isEmpty()) close();
        else drain();
    }

    void tick(long nowMs) {
        if (state != State.CLOSED && !transportReady && nowMs >= connectionDeadline)
            failed(epoch, "Voice connection timed out");
    }

    void disconnected(long id) {
        if (id == epoch && state != State.CLOSED)
            failed(id, "Voice connection ended unexpectedly");
    }

    void failed(long id, String safeMessage) {
        if (id != epoch || state == State.CLOSED) return;
        close();
        port.failure(safeMessage);
    }

    void close() {
        if (state == State.CLOSED) return;
        long oldEpoch = epoch;
        state = State.CLOSED;
        transportReady = false;
        microphoneReady = false;
        capturingInitial = false;
        pendingUserContext.clear();
        explicitConversation = false;
        // Detach voice delivery. Accepted Pi work continues in AgentService and
        // its results remain in the main chat. Only explicit task controls stop it.
        turns.clear();
        if (activeAnnouncementId != null) {
            port.announcementFailed(activeAnnouncementId, "Subscription speech ended before playback completed");
            activeAnnouncementId = null;
        }
        while (!announcements.isEmpty())
            port.announcementFailed(announcements.removeFirst().id, "Subscription speech queue closed before playback");
        delivered.clear();
        port.close(oldEpoch);
        port.status("Voice disconnected · tasks continue in chat");
    }
}
