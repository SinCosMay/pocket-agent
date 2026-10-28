package dev.pocket.agent;

import android.content.Context;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/** OS-owned wakeup alarms with durable WorkManager recovery. */
final class AutonomyScheduler {
    private static final java.util.concurrent.ScheduledExecutorService WORK=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    static final class ArmRequests {
        private boolean queued;private long revision;
        synchronized boolean request(){revision++;if(queued)return false;queued=true;return true;}
        synchronized long revision(){return revision;}
        synchronized boolean current(long expected){return revision==expected;}
        synchronized boolean finish(long expected){if(revision!=expected)return false;queued=false;return true;}
    }
    private static final ArmRequests ARMS=new ArmRequests();
    static void execute(Runnable work){WORK.execute(work);}
    static void arm(Context context) {
        Context app=context.getApplicationContext();if(ARMS.request())WORK.execute(()->attemptArm(app,0));
    }
    private static void attemptArm(Context context,int attempt) {
        long revision=ARMS.revision();
        try {
            armNow(context);
            if(!ARMS.finish(revision))WORK.execute(()->attemptArm(context,0));
        } catch(Exception error) {
            android.util.Log.e("PocketAutonomy","Could not arm background work (attempt "+(attempt+1)+")",error);
            if(attempt<2)WORK.schedule(()->attemptArm(context,attempt+1),5L*(attempt+1),TimeUnit.SECONDS);
            else {
                if(!ARMS.current(revision)){WORK.execute(()->attemptArm(context,0));return;}
                // Persist a visible recovery state instead of leaving durable work silently stranded.
                try{AutonomyStore.startupFailed(context);AutonomyStore.startupFailed(context);AutonomyStore.startupFailed(context);}
                catch(Exception failure){android.util.Log.e("PocketAutonomy","Could not persist scheduling failure",failure);}
                if(!ARMS.finish(revision))WORK.execute(()->attemptArm(context,0));
            }
        }
    }
    static void armFromBroadcast(Context context,Runnable finished) {
        Context app=context.getApplicationContext();
        WORK.execute(()->{try{armNow(app);}catch(Exception error){arm(app);}finally{finished.run();}});
    }
    static boolean exactAlarmsAllowed(Context context) {
        return context.getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms();
    }
    private static android.app.PendingIntent alarmIntent(Context context,boolean exact) {
        return android.app.PendingIntent.getBroadcast(context,0,
            new android.content.Intent(context,AutonomyAlarmReceiver.class).putExtra("exact",exact),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT|android.app.PendingIntent.FLAG_IMMUTABLE);
    }
    private static void armNow(Context context) throws Exception {
        try(AutonomyStore store=new AutonomyStore(context)) {
            long due=store.nextDue();WorkManager manager=WorkManager.getInstance(context);
            android.app.AlarmManager alarms=context.getSystemService(android.app.AlarmManager.class);
            if(due==Long.MAX_VALUE){alarms.cancel(alarmIntent(context,false));manager.cancelUniqueWork("pocket-autonomy-timer").getResult().get(5,TimeUnit.SECONDS);return;}
            long at=Math.max(System.currentTimeMillis()+1000,due);
            boolean exact=exactAlarmsAllowed(context);
            try {
                if(exact)alarms.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,at,alarmIntent(context,true));
                else alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,at,alarmIntent(context,false));
            } catch(SecurityException revoked) {
                exact=false;
                alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,at,alarmIntent(context,false));
            }
            // Let exact-alarm foreground execution claim the run before the job fallback.
            // Otherwise JobScheduler may win the same-deadline race and leave it subject to job quotas.
            OneTimeWorkRequest timer=new OneTimeWorkRequest.Builder(AutonomyTimerWorker.class)
                .setInitialDelay(Math.max(1000,due-System.currentTimeMillis())+(exact?60_000:0),TimeUnit.MILLISECONDS).build();
            manager.enqueueUniqueWork("pocket-autonomy-timer",ExistingWorkPolicy.REPLACE,timer).getResult().get(5,TimeUnit.SECONDS);
        }
    }
    static synchronized void run(Context context) throws Exception {
        if(!AutonomyStore.enabled(context))return;
        WorkManager manager=WorkManager.getInstance(context);
        // At most one running request and one successor. KEEP would lose a trigger arriving
        // after the runner's final state read but before WorkManager marks it complete.
        for(androidx.work.WorkInfo info:manager.getWorkInfosForUniqueWork("pocket-autonomy-runner").get(10,TimeUnit.SECONDS))
            if(info.getState()==androidx.work.WorkInfo.State.ENQUEUED||info.getState()==androidx.work.WorkInfo.State.BLOCKED)return;
        androidx.work.Constraints constraints=new androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build();
        OneTimeWorkRequest run=new OneTimeWorkRequest.Builder(AutonomyWorker.class).setConstraints(constraints)
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build();
        manager.enqueueUniqueWork("pocket-autonomy-runner",ExistingWorkPolicy.APPEND_OR_REPLACE,run).getResult().get(5,TimeUnit.SECONDS);
    }
}
