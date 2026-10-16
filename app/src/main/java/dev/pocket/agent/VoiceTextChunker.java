package dev.pocket.agent;

import java.util.ArrayList;
import java.util.List;

/** Emits complete clauses early, without feeding half-words into streaming TTS. */
final class VoiceTextChunker {
    private final StringBuilder buffer = new StringBuilder();
    List<String> append(String delta) {
        if (delta != null) buffer.append(delta);
        ArrayList<String> ready = new ArrayList<>();
        while (true) {
            int cut = -1;
            for (int i = 0; i < buffer.length(); i++) {
                if (".!?;\n".indexOf(buffer.charAt(i)) >= 0
                    && (i + 1 == buffer.length() || Character.isWhitespace(buffer.charAt(i + 1)))) {
                    cut = i + 1; break;
                }
            }
            if (cut < 0 && buffer.length() >= 120) {
                for (int i = Math.min(buffer.length() - 1, 120); i >= 60; i--) {
                    if (Character.isWhitespace(buffer.charAt(i))) { cut = i + 1; break; }
                }
            }
            if (cut < 0 && buffer.length() >= 256) cut = 256;
            if (cut < 0) break;
            String spoken = buffer.substring(0, cut).trim();
            buffer.delete(0, cut);
            if (!spoken.isEmpty()) ready.add(spoken);
        }
        return ready;
    }
    String drain() { String result = buffer.toString().trim(); buffer.setLength(0); return result; }
    void clear() { buffer.setLength(0); }
}
