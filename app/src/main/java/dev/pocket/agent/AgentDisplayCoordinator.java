package dev.pocket.agent;

import android.content.Context;
import android.content.ComponentName;
import android.provider.Settings;
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

    AgentDisplayCoordinator(Context context) {this.context=context.getApplicationContext();}

    private synchronized ShizukuDisplayClient client() {
        if(client==null) client=new ShizukuDisplayClient(context,this::invalidate);
        return client;
    }

    synchronized JSONObject acquire(String owner) throws ControlException {
        Integer existing=displays.get(owner);
        if(existing!=null) return Json.obj("status","acquired","display_id",existing,"owner",owner);
        if(displays.size()>=MAX_DISPLAYS)
            throw new ControlException(NOT_READY,"All three displays are allocated");
        ShizukuDisplayClient active=client();
        int id=active.createDisplay();
        if(id<=0||displays.containsValue(id)) {
            if(id>0&&!displays.containsValue(id))active.removeDisplay(id);
            throw new ControlException(NOT_READY,"Virtual display backend supplied invalid or duplicate display ID");
        }
        try {
            active.launch(id,new ComponentName(context,MainActivity.class).flattenToString());
        } catch(ControlException error) {
            active.removeDisplay(id);
            throw error;
        } catch(RuntimeException error) {
            active.removeDisplay(id);
            throw new ControlException(NOT_READY,"Virtual display backend failed while launching Pocket Agent");
        }
        displays.put(owner,id);
        return Json.obj("status","acquired","display_id",id,"owner",owner);
    }

    synchronized int display(String owner) throws ControlException {
        Integer id=displays.get(owner);
        if(id==null||id<=0) throw new ControlException(NOT_READY,"No virtual display for conversation");
        if(client==null||!"ready".equals(client.status()))
            throw new ControlException(NOT_READY,"Shizuku backend disconnected; observe after recovery");
        return id;
    }

    synchronized AccessibilityProxyBackend screen(String owner) throws ControlException {
        int id=display(owner);
        AccessibilityProxyBackend backend=new AccessibilityProxyBackend(context,id);
        if(!AccessibilityProxyBackend.connected(context))recoverAccessibility();
        if(!AccessibilityProxyBackend.connected(context))
            throw new ControlException(NOT_READY,"Pocket Agent accessibility is enabled but has not rebound yet; retry after recovery");
        return backend;
    }

    private boolean accessibilityEnabledByUser(ComponentName target) {
        try {
            if(Settings.Secure.getInt(context.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0)!=1)return false;
            String raw=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if(raw==null)return false;
            for(String item:raw.split(":"))if(target.equals(ComponentName.unflattenFromString(item.trim())))return true;
        } catch(RuntimeException ignored) {}
        return false;
    }
    private void recoverAccessibility() throws ControlException {
        ComponentName component=new ComponentName(context,AgentAccessibility.class);
        boolean enabled=accessibilityEnabledByUser(component);
        boolean pending=AccessibilityRepairState.pending(context);
        if(!enabled&&!pending)
            throw new ControlException(PERMISSION_REQUIRED,"Enable Pocket Agent accessibility to use virtual displays");
        try {
            context.startForegroundService(new android.content.Intent(context,AccessibilityRepairService.class));
        } catch(RuntimeException failure) {
            throw new ControlException(NOT_READY,"Pocket Agent accessibility recovery could not be started");
        }
        long deadline=android.os.SystemClock.elapsedRealtime()+4500;
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            if(AccessibilityProxyBackend.connected(context))return;
            try {Thread.sleep(100);} catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ControlException(RESULT_UNKNOWN,"Interrupted while waiting for accessibility recovery");
            }
        }
        if(!accessibilityEnabledByUser(component)&&!AccessibilityRepairState.pending(context))
            throw new ControlException(PERMISSION_REQUIRED,"Pocket Agent accessibility is disabled");
    }

    synchronized void launch(String owner,String component) throws ControlException {client().launch(display(owner),component);}
    synchronized void openUrl(String owner,String url) throws ControlException {int id=display(owner);client().openUrl(id,url);}
    synchronized void showClock(String owner,String screen) throws ControlException {int id=display(owner);client().showClock(id,screen);}
    synchronized void key(String owner,int code) throws ControlException {client().key(display(owner),code);}

    synchronized void release(String owner) {
        Integer id=displays.remove(owner);
        if(id!=null) {
            new AccessibilityProxyBackend(context,id).release();
            if(client!=null) client.removeDisplay(id);
        }
    }

    private synchronized void invalidate() {
        for(int id:displays.values())new AccessibilityProxyBackend(context,id).release();
        displays.clear();
    }

    synchronized int activeCount() {return displays.size();}
    boolean accessibilityConnected() {return AccessibilityProxyBackend.connected(context);}
    String backendStatus() {ShizukuDisplayClient present=client;return present==null?ShizukuDisplayClient.prerequisites():present.status();}

    @Override public synchronized void close() {
        for(String owner:java.util.List.copyOf(displays.keySet()))release(owner);
        if(client!=null) {ShizukuDisplayClient closing=client;client=null;closing.close();}
    }
}