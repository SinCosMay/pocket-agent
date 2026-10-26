package dev.pocket.agent;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Separate timer from execution so rearming a timer never cancels a live run. */
public final class AutonomyTimerWorker extends Worker {
    public AutonomyTimerWorker(Context context,WorkerParameters parameters){super(context,parameters);}
    @Override public Result doWork() {
        try{if(AutonomyStore.enabled(getApplicationContext()))AutonomyScheduler.run(getApplicationContext());}
        catch(Exception error){android.util.Log.w("PocketAutonomy","Could not enqueue background runner",error);return Result.retry();}
        return Result.success();
    }
}
