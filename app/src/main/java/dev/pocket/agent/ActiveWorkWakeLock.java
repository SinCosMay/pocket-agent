package dev.pocket.agent;

import android.content.Context;
import android.os.PowerManager;

/** Keep CPU execution alive across screen-off, never keep the display lit or idle runtime awake. */
final class ActiveWorkWakeLock {
    private final PowerManager.WakeLock lock;
    private long renewAfter;
    ActiveWorkWakeLock(Context context) {
        lock=context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PocketAgent:ActiveWork");
        lock.setReferenceCounted(false);
    }
    synchronized void setActive(boolean active) {
        if(active) {
            // A hung isolate cannot hold the CPU indefinitely. Activity events renew this lease.
            long now=android.os.SystemClock.elapsedRealtime();
            if(!lock.isHeld()||now>=renewAfter) {
                lock.acquire(10*60_000L);renewAfter=now+60_000L;
            }
        } else {
            if(lock.isHeld())lock.release();
            renewAfter=0;
        }
    }
    synchronized boolean isHeld(){return lock.isHeld();}
}
