package dev.pocket.agent;

import android.content.Context;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/** OS-owned timers never keep the Node engine awake. All times are best effort. */
final class AutonomyScheduler {
    static void arm(Context context) {
        try(AutonomyStore store=new AutonomyStore(context)) {
            long due=store.nextDue();WorkManager manager=WorkManager.getInstance(context);
            if(due==Long.MAX_VALUE){manager.cancelUniqueWork("pocket-autonomy-timer");return;}
            OneTimeWorkRequest timer=new OneTimeWorkRequest.Builder(AutonomyTimerWorker.class)
                .setInitialDelay(Math.max(0,due-System.currentTimeMillis()),TimeUnit.MILLISECONDS).build();
            manager.enqueueUniqueWork("pocket-autonomy-timer",ExistingWorkPolicy.REPLACE,timer);
        } catch(Exception error) {android.util.Log.e("PocketAutonomy","Could not arm background work",error);}
    }
    static void run(Context context) {
        androidx.work.Constraints constraints=new androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build();
        OneTimeWorkRequest run=new OneTimeWorkRequest.Builder(AutonomyWorker.class).setConstraints(constraints).build();
        WorkManager.getInstance(context).enqueueUniqueWork("pocket-autonomy-runner",ExistingWorkPolicy.APPEND_OR_REPLACE,run);
    }
}
