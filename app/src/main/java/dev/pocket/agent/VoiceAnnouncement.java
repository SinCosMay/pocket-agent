package dev.pocket.agent;

import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;
import java.util.UUID;

/** An internal one-shot request. It is never an exported broadcast or model command. */
final class VoiceAnnouncement {
    final String id;
    final String message;
    final boolean listenAfter;

    private VoiceAnnouncement(String id, String message, boolean listenAfter) {
        this.id = id;
        this.message = message;
        this.listenAfter = listenAfter;
    }

    static VoiceAnnouncement fromEvent(JSONObject event) {
        String id = event.optString("id", "");
        String message = event.optString("message", "").trim();
        UUID.fromString(id);
        if (message.isEmpty() || message.length() > 800)
            throw new IllegalArgumentException("Spoken text must contain 1..800 characters");
        return new VoiceAnnouncement(id, message, event.optBoolean("listenAfter", true));
    }

    static Intent intent(Context context, VoiceAnnouncement request) {
        return new Intent(context, VoiceListeningService.class)
            .setAction(VoiceListeningService.ACTION_ANNOUNCE)
            .putExtra("id", request.id)
            .putExtra("message", request.message)
            .putExtra("listenAfter", request.listenAfter);
    }

    static VoiceAnnouncement fromIntent(Intent intent) {
        String id = intent.getStringExtra("id");
        String message = intent.getStringExtra("message");
        if (id == null || message == null) throw new IllegalArgumentException("Missing speech request");
        UUID.fromString(id);
        if (message.trim().isEmpty() || message.length() > 800)
            throw new IllegalArgumentException("Invalid speech request length");
        return new VoiceAnnouncement(id, message.trim(), intent.getBooleanExtra("listenAfter", true));
    }
}
