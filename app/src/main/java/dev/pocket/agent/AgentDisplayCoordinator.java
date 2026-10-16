package dev.pocket.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.pocket.android.AndroidScreenBackend;
import dev.pocket.control.ControlException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import static dev.pocket.control.ControlException.Code.*;

/** Maps each conversation to one independent shell-owned display, never display zero. */
final class AgentDisplayCoordinator implements AutoCloseable {
    static final int MAX_DISPLAYS=3;
    private final Context context;
    private final Map<String,Integer> displays=new LinkedHashMap<>();
    private ShizukuDisplayClient client;
    private final Handler idleHandler=new Handler(Looper.getMainLooper());
    private final Runnable idleShutdown=()->{
        synchronized(this) {
            if(displays.isEmpty() && client!=null) {
                ShizukuDisplayClient closing=client;client=null;closing.close();
            }
        }
    };

    AgentDisplayCoordinator(Context context) {this.context=context.getApplicationContext();}

    private synchronized ShizukuDisplayClient client() {
        if(client==null) client=new ShizukuDisplayClient(context,this::invalidate);
        return client;
    }

    synchronized JSONObject acquire(String owner) throws ControlException {
        idleHandler.removeCallbacks(idleShutdown);
        Integer existing=displays.get(owner);
        if(existing!=null) return Json.obj("status","acquired","display_id",existing,"owner",owner);
        if(displays.size()>=MAX_DISPLAYS)
            throw new ControlException(NOT_READY,"All three displays are allocated");
        try {
            int id=client().createDisplay();
            if(id<=0||displays.containsValue(id)) {
                if(id>0&&!displays.containsValue(id))client().removeDisplay(id);
                throw new ControlException(NOT_READY,"Virtual display backend supplied invalid or duplicate display ID");
            }
            displays.put(owner,id);
            try {launchWorkspace(owner);} catch(ControlException | RuntimeException error) {release(owner);throw error;}
            return Json.obj("status","acquired","display_id",id,"owner",owner);
        } catch(ControlException | RuntimeException error) {
            stopIfIdle();
            throw error;
        }
    }

    synchronized int display(String owner) throws ControlException {
        Integer id=displays.get(owner);
        if(id==null||id<=0) throw new ControlException(NOT_READY,"No virtual display for conversation");
        if(client==null||!"ready".equals(client.status()))
            throw new ControlException(NOT_READY,"Shizuku backend disconnected; observe after recovery");
        return id;
    }

    synchronized AndroidScreenBackend screen(String owner) throws ControlException {
        int id=display(owner);
        AgentAccessibility observer=AgentAccessibility.connected;
        if(observer==null) throw new ControlException(PERMISSION_REQUIRED,"Enable Pocket Agent accessibility to use virtual displays");
        return observer.backend(id);
    }

    synchronized void launchWorkspace(String owner) throws ControlException {
        client().launch(display(owner),new android.content.ComponentName(context,VirtualWorkspaceActivity.class).flattenToString());
    }
    synchronized void launch(String owner,String component) throws ControlException {client().launch(display(owner),component);}
    synchronized void key(String owner,int code) throws ControlException {client().key(display(owner),code);}

    synchronized void release(String owner) {
        Integer id=displays.remove(owner);
        if(id!=null) {
            AgentAccessibility observer=AgentAccessibility.connected;
            if(observer!=null) observer.release(id);
            if(client!=null) client.removeDisplay(id);
        }
        stopIfIdle();
    }

    private synchronized void invalidate() {
        AgentAccessibility observer=AgentAccessibility.connected;
        if(observer!=null) for(int id:displays.values())observer.release(id);
        displays.clear();
        stopIfIdle();
    }

    private synchronized void stopIfIdle() {
        if(displays.isEmpty() && client!=null) {
            idleHandler.removeCallbacks(idleShutdown);
            idleHandler.postDelayed(idleShutdown,10_000);
        }
    }

    synchronized int activeCount() {return displays.size();}
    String backendStatus() {ShizukuDisplayClient present=client;return present==null?ShizukuDisplayClient.prerequisites():present.status();}

    @Override public synchronized void close() {
        for(String owner:java.util.List.copyOf(displays.keySet()))release(owner);
        idleHandler.removeCallbacks(idleShutdown);
        if(client!=null) {ShizukuDisplayClient closing=client;client=null;closing.close();}
    }
}