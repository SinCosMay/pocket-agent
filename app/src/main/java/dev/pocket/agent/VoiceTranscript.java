package dev.pocket.agent;

import org.json.JSONObject;

/** Flux emits speculative turn updates; only a confirmed EndOfTurn is a new user message. */
final class VoiceTranscript {
    static boolean startedTurn(JSONObject packet) {
        return "TurnInfo".equals(packet.optString("type"))
            && "StartOfTurn".equals(packet.optString("event"));
    }

    static String completedTurn(JSONObject packet) {
        if (!"TurnInfo".equals(packet.optString("type"))
                || !"EndOfTurn".equals(packet.optString("event"))) return null;
        String utterance = packet.optString("transcript", "").trim();
        return utterance.isEmpty() ? null : utterance;
    }
}
