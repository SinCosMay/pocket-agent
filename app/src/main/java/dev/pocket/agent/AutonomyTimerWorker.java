package dev.pocket.agent;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Separate timer from execution so rearming a timer never cancels a live run. */
public final class AutonomyTimerWorker extends Worker {
    public AutonomyTimerWorker(Context context,WorkerParameters parameters){super(context,parameters);}
    @Override public Result doWork() {
        try{if(AutonomyStore.enabled(getApplicationContext()))AutonomyScheduler.run(getApplicationContext());}
        catch(Exception error){
            android.util.Log.w("PocketAutonomy","Could not enqueue background runner",error);
            try{AutonomyStore.startupFailed(getApplicationContext());}catch(Exception failure){android.util.Log.e("PocketAutonomy","Could not persist scheduling failure",failure);}
            return getRunAttemptCount()<2?Result.retry():Result.failure();
        }
        return Result.success();
    }
}
