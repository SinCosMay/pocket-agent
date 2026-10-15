package dev.pocket.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Deepgram may finalize multiple text segments before marking an utterance complete. */
final class VoiceTranscript {
    private final StringBuilder complete = new StringBuilder();

    String accept(JSONObject packet) {
        if (!"Results".equals(packet.optString("type"))) return null;
        JSONObject channel = packet.optJSONObject("channel");
        JSONArray options = channel == null ? null : channel.optJSONArray("alternatives");
        JSONObject choice = options == null ? null : options.optJSONObject(0);
        String part = choice == null ? "" : choice.optString("transcript", "").trim();
        boolean finalized = packet.optBoolean("is_final");
        if (finalized && !part.isEmpty()) {
            if (complete.length() > 0) complete.append(' ');
            complete.append(part);
        }
        if (packet.optBoolean("speech_final")) {
            String result = complete.toString().trim();
            if (!finalized && !part.isEmpty()) result = result.isEmpty() ? part : result + " " + part;
            complete.setLength(0);
            return result.isEmpty() ? null : result;
        }
        return null;
    }

    void reset() { complete.setLength(0); }
}
