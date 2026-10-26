package dev.pocket.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reconcile persisted UTC deadlines when the phone's wall clock changes or it reboots. */
public final class AutonomyClockReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        PendingResult pending=goAsync();AutonomyScheduler.armFromBroadcast(context,pending::finish);
    }
}
