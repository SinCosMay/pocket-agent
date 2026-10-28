package dev.pocket.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Exact user-requested alarms may start foreground execution even from Doze. */
public final class AutonomyAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        if(!AutonomyStore.enabled(context))return;
        if(intent.getBooleanExtra("exact",false)) {
            try {
                context.startForegroundService(new Intent(context,AutonomyAlarmService.class));
                return;
            } catch(RuntimeException denied) {
                android.util.Log.w("PocketAutonomy","Alarm foreground start denied; using durable worker",denied);
            }
        }
        PendingResult pending=goAsync();
        AutonomyScheduler.execute(()->{
            try{AutonomyScheduler.run(context.getApplicationContext());}
            catch(Exception error){android.util.Log.e("PocketAutonomy","Alarm worker enqueue failed",error);AutonomyScheduler.arm(context);}
            finally{pending.finish();}
        });
    }
}
