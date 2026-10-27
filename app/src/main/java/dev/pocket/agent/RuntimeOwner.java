package dev.pocket.agent;

import android.content.Context;
import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;

/** Service and OS worker lease one runtime. New starts wait for the previous isolate to close. */
final class RuntimeOwner implements AutoCloseable {
    private static RuntimeOwner current;
    private static CompletableFuture<Void> previousClose=CompletableFuture.completedFuture(null);
    final RuntimeHost host;
    final CompletableFuture<Void> ready;
    private int references;
    private boolean released;
    private volatile boolean active;
    private RuntimeOwner(Context context) {
        host=new RuntimeHost(context,event->{
            if("activity".equals(event.optString("type"))) {synchronized(this){active=event.optBoolean("active");notifyAll();}}
            AgentService.publishShared(context,event);
        });
        ready=previousClose.thenCompose(ignored->host.start());
    }
    static synchronized RuntimeOwner acquire(Context context) {
        if(current==null)current=new RuntimeOwner(context.getApplicationContext());
        current.references++;return current;
    }
    static synchronized RuntimeHost peekHost(){return current==null?null:current.host;}
    synchronized boolean awaitIdle(long deadline,java.util.function.BooleanSupplier stopped) throws InterruptedException {
        while(active&&!stopped.getAsBoolean()&&android.os.SystemClock.elapsedRealtime()<deadline)wait(Math.min(1000,Math.max(1,deadline-android.os.SystemClock.elapsedRealtime())));
        return !active;
    }
    @Override public void close() {
        synchronized(RuntimeOwner.class) {
            if(released)return;
            if(--references>0)return;
            released=true;current=null;previousClose=host.closeAsync();
        }
    }
}
