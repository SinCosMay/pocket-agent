package dev.pocket.agent;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.view.Display;
import dev.pocket.control.ControlException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import static dev.pocket.control.ControlException.Code.*;

/** Maps each conversation to one independent shell-owned display, with explicit opt-in access to display zero. */
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

    synchronized int display(String owner,boolean physical) throws ControlException {
        return physical?Display.DEFAULT_DISPLAY:display(owner);
    }

    synchronized AccessibilityProxyBackend screen(String owner,boolean physical) throws ControlException {
        int id=display(owner,physical);
        AccessibilityProxyBackend backend=new AccessibilityProxyBackend(context,id);
        AccessibilityProxyBackend.ConnectionState state=AccessibilityProxyBackend.connectionState(context);
        if(state==AccessibilityProxyBackend.ConnectionState.UNKNOWN)
            throw new ControlException(NOT_READY,"Accessibility bridge is busy; retry after the in-flight operation completes");
        if(state==AccessibilityProxyBackend.ConnectionState.DISCONNECTED)recoverAccessibility();
        state=AccessibilityProxyBackend.connectionState(context);
        if(state!=AccessibilityProxyBackend.ConnectionState.CONNECTED)
            throw new ControlException(NOT_READY,"Pocket Agent accessibility has not rebound yet; retry after recovery");
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
            throw new ControlException(PERMISSION_REQUIRED,"Enable Pocket Agent accessibility to control Android displays");
        try {
            context.startForegroundService(new Intent(context,AccessibilityRepairService.class));
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

    private void startPhysical(String owner,Intent source) throws ControlException {
        screen(owner,true).startActivity(source);
    }

    synchronized void launch(String owner,Intent launch,boolean physical) throws ControlException {
        if(physical){startPhysical(owner,launch);return;}
        if(launch.getComponent()==null)throw new ControlException(UNSUPPORTED,"No launcher component visible for this package");
        int id=display(owner);client().launch(id,launch.getComponent().flattenToString());
    }

    synchronized void openUrl(String owner,String url,boolean physical) throws ControlException {
        if(physical){startPhysical(owner,new Intent(Intent.ACTION_VIEW,Uri.parse(url)));return;}
        int id=display(owner);client().openUrl(id,url);
    }

    synchronized void showClock(String owner,String screen,boolean physical) throws ControlException {
        if(physical) {
            String action=switch(screen) {
                case "alarms"->AlarmClock.ACTION_SHOW_ALARMS;
                case "timers"->AlarmClock.ACTION_SHOW_TIMERS;
                default->throw new ControlException(INVALID_ARGUMENT,"Expected alarms or timers");
            };
            startPhysical(owner,new Intent(action));return;
        }
        int id=display(owner);client().showClock(id,screen);
    }

    synchronized void key(String owner,int code) throws ControlException {int id=display(owner);client().key(id,code);}

    synchronized void release(String owner) {
        Integer id=displays.get(owner);
        if(id!=null) {
            if(client==null||!client.removeDisplay(id))
                throw new IllegalStateException("Virtual display cleanup did not complete; lease retained to protect existing tasks");
            displays.remove(owner);
            new AccessibilityProxyBackend(context,id).release();
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
