package dev.pocket.agent;

import android.content.Context;
import android.content.Intent;

/** Explicit conversations are separate from assistant/wake-word voice. */
final class SubscriptionVoiceSettings {
    static Intent conversationIntent(Context context) {
        return new Intent(context, SubscriptionVoiceService.class)
            .setAction(SubscriptionVoiceService.ACTION_START_CONVERSATION);
    }
    static Intent wakeIntent(Context context) {
        return new Intent(context, VoiceListeningService.class).setAction(VoiceListeningService.ACTION_START);
    }
    static Intent announcementIntent(Context context, VoiceAnnouncement announcement) {
        if (!SubscriptionVoiceService.isActive()) return VoiceAnnouncement.intent(context, announcement);
        return new Intent(context, SubscriptionVoiceService.class)
            .setAction(SubscriptionVoiceService.ACTION_ANNOUNCE)
            .putExtra("id", announcement.id)
            .putExtra("message", announcement.message)
            .putExtra("listenAfter", announcement.listenAfter);
    }
}
