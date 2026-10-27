package dev.pocket.agent;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Bounded WorkManager work; no prohibited background startForegroundService call. */
public final class AutonomyWorker extends Worker {
    private volatile RuntimeOwner owner;
    public AutonomyWorker(Context context,WorkerParameters parameters){super(context,parameters);}
    @Override public Result doWork() {
        Context context=getApplicationContext();
        if(!AutonomyStore.enabled(context))return Result.success();
        RuntimeOwner lease=RuntimeOwner.acquire(context);owner=lease;
        try {
            lease.ready.get(40,TimeUnit.SECONDS);
            if(isStopped()||!AutonomyStore.enabled(context))return Result.success();
            JSONObject accepted=lease.host.dispatch(Json.obj("action","autonomy_tick")).get(20,TimeUnit.SECONDS);
            if(!accepted.optBoolean("ok")){AutonomyStore.startupFailed(context);return Result.failure();}
            try{AutonomyStore.startupHealthy(context);}catch(Exception error){android.util.Log.w("PocketAutonomy","Could not clear startup backoff; continuing to supervise accepted work",error);}
            boolean idle=lease.awaitIdle(android.os.SystemClock.elapsedRealtime()+8*60_000L,this::isStopped);
            if(!idle)lease.host.dispatch(Json.obj("action","stop_autonomy"));
            return Result.success();
        } catch(Exception error) {
            // A dispatch timeout may still have started work in a runtime shared with the service.
            try{lease.host.dispatch(Json.obj("action","stop_autonomy")).get(5,TimeUnit.SECONDS);}catch(Exception cancelError){android.util.Log.w("PocketAutonomy","Could not confirm background cancellation",cancelError);}
            try{AutonomyStore.startupFailed(context);}catch(Exception policyError){android.util.Log.w("PocketAutonomy","Could not persist startup failure",policyError);}
            android.util.Log.w("PocketAutonomy","Background run interrupted; work will not replay uncertain side effects",error);
            return Result.failure();
        } finally {owner=null;lease.close();AutonomyScheduler.arm(context);}
    }
    @Override public void onStopped() {
        RuntimeOwner lease=owner;if(lease!=null)lease.host.dispatch(Json.obj("action","stop_autonomy"));
    }
}
