package dev.pocket.agent;

public final class SubscriptionVoicePlaybackGateSimulator {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        SubscriptionVoicePlaybackGate gate = new SubscriptionVoicePlaybackGate();
        gate.audio(1000);
        check(!gate.ready(4600, 3500), "Long audio pause ended an active response");
        gate.audio(6000);
        check(!gate.ready(12000, 3500), "Later audio fragment was cut off");
        gate.finishAssistant();
        check(!gate.ready(9000, 3500), "Response finished before playout margin");
        check(gate.ready(9500, 3500), "Completed response did not release playback");

        gate.reset();
        check(!gate.ready(20000, 3500), "Stale completion survived reset");
        gate.finishAssistant();
        check(!gate.ready(20000, 3500), "No-audio response completed playback");
        gate.audio(21000);
        check(!gate.ready(23000, 3500), "Audio after completion lacked playout margin");
        check(gate.ready(24500, 3500), "Audio after completion did not drain");
        System.out.println("Subscription voice playback gate: 2 scenarios PASSED");
    }
}
