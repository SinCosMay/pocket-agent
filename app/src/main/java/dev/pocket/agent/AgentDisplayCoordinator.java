package dev.pocket.agent;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.provider.AlarmClock;
import android.view.Display;
import dev.pocket.android.AndroidScreenBackend;
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

    synchronized AndroidScreenBackend screen(String owner,boolean physical) throws ControlException {
        AgentAccessibility observer=AgentAccessibility.connected;
        if(observer==null) throw new ControlException(PERMISSION_REQUIRED,"Enable Pocket Agent accessibility to control Android displays");
        if(physical)return observer.backend();
        return observer.backend(display(owner));
    }

    private void startPhysical(Intent source) throws ControlException {
        AgentAccessibility observer=AgentAccessibility.connected;
        if(observer==null) throw new ControlException(PERMISSION_REQUIRED,"Enable Pocket Agent accessibility to open content on the visible display");
        Intent intent=new Intent(source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ActivityOptions options=ActivityOptions.makeBasic();
            options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
            observer.startActivity(intent,options.toBundle());
        } catch(SecurityException denied) {
            throw new ControlException(PERMISSION_REQUIRED,"Android blocked opening content on the visible display");
        } catch(RuntimeException rejected) {
            throw new ControlException(ACTION_REJECTED,"Android rejected opening content on the visible display");
        }
    }

    synchronized void launch(String owner,Intent launch,boolean physical) throws ControlException {
        if(physical){startPhysical(launch);return;}
        if(launch.getComponent()==null)throw new ControlException(UNSUPPORTED,"No launcher component visible for this package");
        int id=display(owner);client().launch(id,launch.getComponent().flattenToString());
    }
    synchronized void openUrl(String owner,String url,boolean physical) throws ControlException {
        if(physical){startPhysical(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));return;}
        int id=display(owner);client().openUrl(id,url);
    }
    synchronized void showClock(String owner,String screen,boolean physical) throws ControlException {
        if(physical) {
            String action=switch(screen) {case "alarms"->AlarmClock.ACTION_SHOW_ALARMS;case "timers"->AlarmClock.ACTION_SHOW_TIMERS;default->throw new ControlException(INVALID_ARGUMENT,"Expected alarms or timers");};
            startPhysical(new Intent(action));return;
        }
        int id=display(owner);client().showClock(id,screen);
    }
    synchronized void key(String owner,int code) throws ControlException {int id=display(owner);client().key(id,code);}

    synchronized void release(String owner) {
        Integer id=displays.remove(owner);
        if(id!=null) {
            AgentAccessibility observer=AgentAccessibility.connected;
            if(observer!=null) observer.release(id);
            if(client!=null) client.removeDisplay(id);
        }
    }

    private synchronized void invalidate() {
        AgentAccessibility observer=AgentAccessibility.connected;
        if(observer!=null) for(int id:displays.values())observer.release(id);
        displays.clear();
    }

    synchronized int activeCount() {return displays.size();}
    String backendStatus() {ShizukuDisplayClient present=client;return present==null?ShizukuDisplayClient.prerequisites():present.status();}

    @Override public synchronized void close() {
        for(String owner:java.util.List.copyOf(displays.keySet()))release(owner);
        if(client!=null) {ShizukuDisplayClient closing=client;client=null;closing.close();}
    }
}