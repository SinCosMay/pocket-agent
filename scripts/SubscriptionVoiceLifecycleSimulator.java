package dev.pocket.agent;

import java.util.ArrayList;
import java.util.List;

public final class SubscriptionVoiceLifecycleSimulator {
    private static final class FakePort implements SubscriptionVoiceLifecycle.Port {
        final List<String> calls = new ArrayList<>();
        @Override public void connect(long epoch) { calls.add("connect:" + epoch); }
        @Override public void close(long epoch) { calls.add("close:" + epoch); }
        @Override public void sendToPi(String turnId, String text) { calls.add("pi:" + turnId + ":" + text); }
        @Override public void cancelPi(String turnId) { calls.add("cancel:" + turnId); }
        @Override public void sendSpeech(String id, String text) { calls.add("speech:" + id + ":" + text); }
        @Override public void interruptSpeech() { calls.add("interrupt-speech"); }
        @Override public void status(String text) { }
        boolean has(String fragment) { return calls.stream().anyMatch(c -> c.contains(fragment)); }
        long count(String prefix) { return calls.stream().filter(c -> c.startsWith(prefix)).count(); }
        String lastPi() { return calls.stream().filter(c -> c.startsWith("pi:")).reduce((a,b)->b).orElse(""); }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void normalWakeAndPiHandoff() {
        FakePort p = new FakePort();
        SubscriptionVoiceLifecycle s = new SubscriptionVoiceLifecycle(p);
        s.wake(100); s.wake(200);
        check(p.count("connect:") == 1, "duplicate wake opened twice");
        s.connected(999, 210);
        check(s.state() == SubscriptionVoiceLifecycle.State.CONNECTING, "stale connect accepted");
        s.connected(1, 300);
        check(s.state() == SubscriptionVoiceLifecycle.State.LISTENING, "not listening");
        s.delegated(1, "delegate-1", "Open the browser");
        check(s.state() == SubscriptionVoiceLifecycle.State.WAITING_PI && p.has("Open the browser"), "Pi dispatch missing");
        s.tick(200000);
        check(s.state() == SubscriptionVoiceLifecycle.State.WAITING_PI, "idle stopped active Pi");
        String turn = p.lastPi().split(":")[1];
        s.delegated(1, "delegate-1", "duplicate");
        check(p.count("pi:") == 1, "duplicate request dispatched");
        s.piDelta("wrong-id", "not spoken");
        s.piDelta(turn, "Done ");
        s.piDelta(turn, "opening browser.");
        s.piFinished("wrong-id");
        check(p.count("speech:") == 0, "stale Pi end spoke");
        s.piFinished(turn);
        check(p.has("speech:delegate-1:Done opening browser."), "voice did not speak Pi output");
        s.playbackFinished(1, 200500);
        check(s.state() == SubscriptionVoiceLifecycle.State.LISTENING, "follow-up not listening");
        s.tick(203499);
        check(s.state() == SubscriptionVoiceLifecycle.State.LISTENING, "early idle disconnect");
        s.tick(203500);
        check(s.state() == SubscriptionVoiceLifecycle.State.CLOSED && p.count("close:") == 1, "idle did not close transport");
    }

    private static void announcementsAndShutdown() {
        FakePort p = new FakePort();
        SubscriptionVoiceLifecycle s = new SubscriptionVoiceLifecycle(p);
        check(!s.announce(" ", true, 0), "empty speech accepted");
        check(s.announce("Hello user", true, 100), "speech rejected");
        check(p.count("connect:") == 1, "on-demand speech didn't connect");
        s.connected(1, 200);
        check(p.has("speech:null:Hello user"), "announcement didn't speak");
        s.announce("Goodbye", false, 201);
        s.playbackFinished(1, 300);
        check(p.has("speech:null:Goodbye"), "announcement queue was not drained");
        s.playbackFinished(1, 400);
        check(s.state() == SubscriptionVoiceLifecycle.State.CLOSED, "no-listen announcement remained connected");
        s.wake(450);
        check(s.epoch() == 2, "epoch not renewed");
        s.playbackFinished(1, 600);
        check(s.state() == SubscriptionVoiceLifecycle.State.CONNECTING, "stale playback mutated reopened session");
        s.tick(15600);
        check(s.state() == SubscriptionVoiceLifecycle.State.CLOSED, "connect timeout did not cleanup");
    }

    private static void interruptionAndDisconnect() {
        FakePort p = new FakePort();
        SubscriptionVoiceLifecycle s = new SubscriptionVoiceLifecycle(p);
        s.wake(0); s.connected(1, 100);
        s.delegated(1, "a", "Please inspect the screen");
        String turn = p.lastPi().split(":")[1];
        s.inputInterrupted(1, 200);
        check(p.has("cancel:" + turn), "barge-in didn't stop main");
        s.piDelta(turn, "old");
        s.piFinished(turn);
        check(p.count("speech:") == 0, "stale Pi output spoke");
        s.delegated(1, "b", "Do another task");
        check(p.count("pi:") == 2, "new delegation not dispatched");
        s.disconnected(1);
        check(s.state() == SubscriptionVoiceLifecycle.State.CLOSED, "transport failure not closed");
        check(p.count("cancel:") == 2, "active main turn not cancelled on failure");
        s.connected(1, 900);
        check(s.state() == SubscriptionVoiceLifecycle.State.CLOSED, "late ready resurrected closed service");
        s.announce("Speaking while user interrupts", true, 1000);
        s.connected(2, 1050);
        s.inputInterrupted(2, 1100);
        check(p.count("interrupt-speech") == 1, "speech playback was not interrupted");
        check(s.state() == SubscriptionVoiceLifecycle.State.LISTENING, "interruption did not resume listening");
        s.tick(4099);
        check(s.state() == SubscriptionVoiceLifecycle.State.LISTENING, "interruption idle expired early");
        s.tick(4100);
        check(s.state() == SubscriptionVoiceLifecycle.State.CLOSED, "interruption idle failed to close");
    }

    public static void main(String[] args) {
        normalWakeAndPiHandoff();
        announcementsAndShutdown();
        interruptionAndDisconnect();
        System.out.println("Subscription voice lifecycle simulator: 3 scenarios PASSED");
    }
}
