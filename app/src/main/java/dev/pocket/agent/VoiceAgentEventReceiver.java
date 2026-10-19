package dev.pocket.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Private asynchronous bridge from the main agent process into the crash-isolated voice process. */
public final class VoiceAgentEventReceiver extends BroadcastReceiver {
    static final String ACTION_AGENT_EVENT="dev.pocket.agent.VOICE_AGENT_EVENT";
    static final String EXTRA_EVENT="event";
    @Override public void onReceive(Context context,Intent intent) {
        if(intent==null||!ACTION_AGENT_EVENT.equals(intent.getAction()))return;
        String event=intent.getStringExtra(EXTRA_EVENT);
        if(event==null||event.length()>1_000_000)return;
        VoiceListeningService.deliverAgentEvent(event);
    }
}
