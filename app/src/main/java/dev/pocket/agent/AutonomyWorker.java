package dev.pocket.agent;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Bounded WorkManager work; no prohibited background startForegroundService call. */
public final class AutonomyWorker extends Worker {
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING=new java.util.concurrent.atomic.AtomicBoolean();
    public AutonomyWorker(Context context,WorkerParameters parameters){super(context,parameters);}
    @Override public Result doWork() {
        return execute(getApplicationContext(),this::isStopped);
    }
    static Result execute(Context context,java.util.function.BooleanSupplier stopped) {
        if(!AutonomyStore.enabled(context)||!RUNNING.compareAndSet(false,true))return Result.success();
        RuntimeOwner lease=null;
        try {
            lease=RuntimeOwner.acquire(context);
            return supervise(context,lease,stopped);
        } catch(Exception error) {
            android.util.Log.e("PocketAutonomy","Could not acquire background runtime",error);
            try{AutonomyStore.startupFailed(context);}catch(Exception persistenceError){android.util.Log.e("PocketAutonomy","Could not persist runtime startup failure",persistenceError);}
            return Result.failure();
        } finally {
            if(lease!=null)lease.close();
            RUNNING.set(false);AutonomyScheduler.arm(context);
        }
    }
    private static Result supervise(Context context,RuntimeOwner lease,java.util.function.BooleanSupplier stopped) {
        try {
            lease.ready.get(40,TimeUnit.SECONDS);
            if(stopped.getAsBoolean()||!AutonomyStore.enabled(context))return Result.success();
            JSONObject accepted=lease.host.dispatch(Json.obj("action","autonomy_tick")).get(20,TimeUnit.SECONDS);
            if(!accepted.optBoolean("ok")){AutonomyStore.startupFailed(context);return Result.failure();}
            try{AutonomyStore.startupHealthy(context);}catch(Exception error){android.util.Log.w("PocketAutonomy","Could not clear startup backoff; continuing to supervise accepted work",error);}
            boolean idle=lease.awaitIdle(android.os.SystemClock.elapsedRealtime()+8*60_000L,stopped);
            if(!idle)lease.host.dispatch(Json.obj("action","stop_autonomy"));
            return Result.success();
        } catch(Exception error) {
            // A dispatch timeout may still have started work in a runtime shared with the service.
            try{lease.host.dispatch(Json.obj("action","stop_autonomy")).get(5,TimeUnit.SECONDS);}catch(Exception cancelError){android.util.Log.w("PocketAutonomy","Could not confirm background cancellation",cancelError);}
            try{AutonomyStore.startupFailed(context);}catch(Exception policyError){android.util.Log.w("PocketAutonomy","Could not persist startup failure",policyError);}
            android.util.Log.w("PocketAutonomy","Background run interrupted; work will not replay uncertain side effects",error);
            return Result.failure();
        }
    }
}
