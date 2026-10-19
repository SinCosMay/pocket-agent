package dev.pocket.agent;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Display;
import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shizuku UserService, executed under Android shell UID.
 * The app process never receives ADD_TRUSTED_DISPLAY or INJECT_EVENTS.
 */
public final class ShellDisplayService extends IDisplayShell.Stub {
    private static final int PUBLIC=1, PRESENTATION=2, OWN_CONTENT=8;
    private static final int TOUCH=1<<6, ROTATE=1<<7, DESTROY_CONTENT=1<<8;
    private static final int TRUSTED=1<<10, OWN_GROUP=1<<11, ALWAYS_UNLOCKED=1<<12;
    private static final int OWN_FOCUS=1<<14, DEVICE_GROUP=1<<15;
    private static final int DISPLAY_IME_POLICY_HIDE=2;
    private static final Pattern ROOT_TASK=Pattern.compile("(?i)(?:RootTask\\s+(?:id=|#)|Stack\\s+id=|Task\\{[^#]*#)(\\d+).*?(?:m?displayId|displayId)\\s*[=:]\\s*(\\d+)");
    private final Context context;
    private final Map<Integer,OwnedDisplay> displays=new LinkedHashMap<>();
    private HandlerThread frameThread;

    private static final class OwnedDisplay {
        final VirtualDisplay display;
        final ImageReader reader;
        final Set<Integer> rootTasks=new LinkedHashSet<>();
        OwnedDisplay(VirtualDisplay display,ImageReader reader) {this.display=display;this.reader=reader;}
        void close() {try {display.release();}finally {reader.close();}}
    }

    public ShellDisplayService() {this.context=null;android.util.Log.i("PocketDisplayShizuku","Shizuku UserService no-context constructor uid="+Process.myUid());}
    public ShellDisplayService(Context context) {this.context=context;android.util.Log.i("PocketDisplayShizuku","Shizuku UserService context constructor uid="+Process.myUid());}

    private Context asShell() {
        if(context==null) throw new IllegalStateException("Shizuku v13 Context required");
        return new ContextWrapper(context) {
            @Override public String getPackageName() {return "com.android.shell";}
            @Override public String getOpPackageName() {return "com.android.shell";}
        };
    }
    private DisplayManager displayManager() throws Exception {
        Constructor<DisplayManager> constructor=DisplayManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(asShell());
    }
    private Handler frameHandler() {
        if(frameThread==null) {frameThread=new HandlerThread("pocket-display-frames");frameThread.start();}
        return new Handler(frameThread.getLooper());
    }
    @Override public synchronized int createDisplay(int width,int height,int dpi) {
        if(Process.myUid()!=2000 && Process.myUid()!=0) throw new SecurityException("Requires Shizuku shell/root user service");
        if(width<320 || width>1920 || height<320 || height>2560 || dpi<120 || dpi>640) throw new IllegalArgumentException("Invalid display dimensions");
        if(displays.size()>=3) throw new IllegalStateException("Three virtual displays already allocated");
        ImageReader reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
        reader.setOnImageAvailableListener(r->{
            try(Image frame=r.acquireLatestImage()) {
                // Drain producer frames without recording content or running a video encoder.
            } catch(IllegalStateException ignored) {}
        },frameHandler());
        int required=PUBLIC|PRESENTATION|OWN_CONTENT|TOUCH|ROTATE|DESTROY_CONTENT|TRUSTED;
        int extended=required|OWN_GROUP|ALWAYS_UNLOCKED;
        if(Build.VERSION.SDK_INT>=34) extended|=OWN_FOCUS|DEVICE_GROUP;
        int[] choices={extended,required|OWN_GROUP,required};
        Exception last=null;
        try {
            DisplayManager dm=displayManager();
            for(int flags:choices) {
                try {
                    VirtualDisplay display=dm.createVirtualDisplay("PocketAgent-Worker",width,height,dpi,reader.getSurface(),flags);
                    if(display==null || display.getDisplay()==null || display.getDisplay().getDisplayId()<=Display.DEFAULT_DISPLAY) {
                        if(display!=null) display.release();
                        throw new IllegalStateException("Android returned no independent display");
                    }
                    int id=display.getDisplay().getDisplayId();
                    try {hideIme(id);}
                    catch(Exception imeFailure) {
                        display.release();
                        last=imeFailure;
                        continue;
                    }
                    displays.put(id,new OwnedDisplay(display,reader));
                    return id;
                } catch(Exception e) {last=e;}
            }
        } catch(Exception e) {last=e;}
        reader.close();
        if(displays.isEmpty() && frameThread!=null) {frameThread.quitSafely();frameThread=null;}
        throw new IllegalStateException("Trusted virtual display unavailable: "+(last==null?"unknown":last.getClass().getSimpleName()),last);
    }
    private void owned(int id) {
        if(id<=Display.DEFAULT_DISPLAY || !displays.containsKey(id)) throw new SecurityException("Display is not owned by Pocket Agent");
    }
    @Override public synchronized void removeDisplay(int id) {
        owned(id);
        OwnedDisplay owned=displays.remove(id);
        reconcileAndRemoveTasks(id,owned);
        owned.close();
        if(displays.isEmpty() && frameThread!=null) {frameThread.quitSafely();frameThread=null;}
    }
    private void hideIme(int displayId) throws Exception {
        Class<?> serviceManager=Class.forName("android.os.ServiceManager");
        android.os.IBinder binder=(android.os.IBinder)serviceManager.getMethod("getService",String.class).invoke(null,"window");
        if(binder==null)throw new IllegalStateException("Window manager unavailable");
        Class<?> stub=Class.forName("android.view.IWindowManager$Stub");
        Object windowManager=stub.getMethod("asInterface",android.os.IBinder.class).invoke(null,binder);
        Class<?> iWindowManager=Class.forName("android.view.IWindowManager");
        iWindowManager.getMethod("setDisplayImePolicy",int.class,int.class).invoke(windowManager,displayId,DISPLAY_IME_POLICY_HIDE);
    }
    static Set<Integer> parseRootTasks(String output,int displayId) {
        Set<Integer> result=new LinkedHashSet<>();
        for(String line:output.split("\\R")) {
            Matcher match=ROOT_TASK.matcher(line);
            if(match.find()&&Integer.parseInt(match.group(2))==displayId)result.add(Integer.parseInt(match.group(1)));
        }
        return result;
    }
    private static final class CommandResult {
        final boolean ok; final String output;
        CommandResult(boolean ok,String output){this.ok=ok;this.output=output;}
    }
    private static CommandResult run(String... command) {
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
        java.lang.Process child=null;Thread reader=null;ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try {
            child=builder.start();
            InputStream stream=child.getInputStream();
            reader=new Thread(()->{
                byte[] buffer=new byte[4096];int read,total=0;
                try {
                    while((read=stream.read(buffer))!=-1) {
                        int keep=Math.min(read,262_144-total);
                        if(keep>0){bytes.write(buffer,0,keep);total+=keep;}
                    }
                } catch(Exception ignored) {}
            },"PocketShellCommandOutput");
            reader.setDaemon(true);reader.start();
            if(!child.waitFor(8,TimeUnit.SECONDS)) {
                child.destroy();
                if(!child.waitFor(250,TimeUnit.MILLISECONDS))child.destroyForcibly();
                return new CommandResult(false,"");
            }
            if(reader!=null)reader.join(250);
            return new CommandResult(child.exitValue()==0,bytes.toString(StandardCharsets.UTF_8));
        } catch(Exception e) {
            if(child!=null)child.destroyForcibly();
            return new CommandResult(false,"");
        } finally {
            if(reader!=null&&reader.isAlive())reader.interrupt();
        }
    }
    private static String execOutput(String... command) {CommandResult result=run(command);return result.ok?result.output:"";}
    private static boolean exec(String... command) {return run(command).ok;}
    @Override public synchronized boolean repairAccessibility(String componentName) {
        android.content.ComponentName component=android.content.ComponentName.unflattenFromString(componentName);
        if(component==null||!"dev.pocket.agent".equals(component.getPackageName())
            ||!"dev.pocket.agent.AgentAccessibility".equals(component.getClassName()))
            throw new SecurityException("Only Pocket Agent accessibility may be repaired");
        if(!"1".equals(execOutput("/system/bin/settings","get","secure","accessibility_enabled").trim()))return false;
        String enabled=execOutput("/system/bin/settings","get","secure","enabled_accessibility_services");
        boolean found=false;
        for(String item:enabled.split(":"))
            if(component.equals(android.content.ComponentName.unflattenFromString(item.trim()))){found=true;break;}
        if(!found)return false;
        String processName=component.getPackageName()+":accessibility";
        String rawPid=execOutput("/system/bin/pidof",processName).trim();
        if(rawPid.isEmpty())return false;
        String pid=rawPid.split("\\s+")[0];
        if(!pid.matches("[0-9]+"))return false;
        // Crash only the isolated accessibility process. Android's existing enabled-service
        // binding is then free to reconnect it; no secure setting is rewritten, so a user
        // disable racing recovery cannot be undone by the repair path.
        return exec("/system/bin/am","crash","--user","current",pid);
    }
    private Set<Integer> rootTasksOnDisplay(int id) {
        Set<Integer> result=parseRootTasks(execOutput("/system/bin/am","stack","list"),id);
        if(result.isEmpty())result.addAll(parseRootTasks(execOutput("/system/bin/dumpsys","activity","activities"),id));
        return result;
    }
    private void reconcileAndRemoveTasks(int id,OwnedDisplay owned) {
        // Every root task on this shell-owned virtual display belongs to this lease.
        // Reconcile immediately before release so tasks created after launch tracking
        // cannot migrate into display 0 / Recents when Android destroys the display.
        for(int pass=0;pass<3;pass++) {
            owned.rootTasks.addAll(rootTasksOnDisplay(id));
            if(owned.rootTasks.isEmpty()) {
                if(pass<2)try {Thread.sleep(80);} catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                continue;
            }
            for(int rootTask:java.util.List.copyOf(owned.rootTasks))
                if(exec("/system/bin/am","stack","remove",String.valueOf(rootTask)))owned.rootTasks.remove(rootTask);
            if(rootTasksOnDisplay(id).isEmpty())break;
        }
    }
    private boolean launchTracked(int id,String... command) {
        Set<Integer> before=rootTasksOnDisplay(id);
        if(!exec(command))return false;
        long deadline=android.os.SystemClock.elapsedRealtime()+3000;
        do {
            Set<Integer> now=rootTasksOnDisplay(id);
            OwnedDisplay owned=displays.get(id);
            if(owned!=null)owned.rootTasks.addAll(now);
            Set<Integer> created=new LinkedHashSet<>(now);created.removeAll(before);
            if(!created.isEmpty())return true;
            try {Thread.sleep(75);} catch(InterruptedException e) {Thread.currentThread().interrupt();return false;}
        } while(android.os.SystemClock.elapsedRealtime()<deadline);
        OwnedDisplay owned=displays.get(id);
        if(owned!=null)owned.rootTasks.addAll(rootTasksOnDisplay(id));
        return false;
    }
    private static final String WORKER_ACTIVITY_FLAGS="0x18800000";
    @Override public synchronized boolean launchActivity(int id,String componentName) {
        owned(id);
        android.content.ComponentName component=android.content.ComponentName.unflattenFromString(componentName);
        if(component==null || component.getPackageName().isBlank()) throw new IllegalArgumentException("Expected component name");
        return launchTracked(id,"/system/bin/am","start","--display",String.valueOf(id),"-f",WORKER_ACTIVITY_FLAGS,"-n",component.flattenToString());
    }
    @Override public synchronized boolean openUrl(int id,String url) {
        owned(id);
        android.net.Uri uri=android.net.Uri.parse(url);
        String scheme=uri.getScheme();
        if(uri.getHost()==null || uri.getHost().isBlank() || uri.getUserInfo()!=null ||
            (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)))
            throw new IllegalArgumentException("Only ordinary HTTP(S) URLs without embedded credentials are supported");
        return launchTracked(id,"/system/bin/am","start","--display",String.valueOf(id),"-f",WORKER_ACTIVITY_FLAGS,
            "-a",android.content.Intent.ACTION_VIEW,"-d",uri.toString());
    }
    @Override public synchronized boolean showClock(int id,String screen) {
        owned(id);
        String action=switch(screen) {
            case "alarms" -> android.provider.AlarmClock.ACTION_SHOW_ALARMS;
            case "timers" -> android.provider.AlarmClock.ACTION_SHOW_TIMERS;
            default -> throw new IllegalArgumentException("Expected alarms or timers");
        };
        return launchTracked(id,"/system/bin/am","start","--display",String.valueOf(id),"-f",WORKER_ACTIVITY_FLAGS,"-a",action);
    }
    @Override public synchronized boolean injectKey(int id,int keyCode) {
        owned(id);
        if(keyCode!=4 && keyCode!=3 && keyCode!=187) throw new SecurityException("Key not permitted");
        return exec("/system/bin/input","-d",String.valueOf(id),"keyevent",String.valueOf(keyCode));
    }
    @Override public synchronized String health() {
        return "uid="+Process.myUid()+";display_count="+displays.size()+";android="+Build.VERSION.SDK_INT;
    }
    @Override public synchronized void destroy() {
        for(Map.Entry<Integer,OwnedDisplay> entry:displays.entrySet()) {
            reconcileAndRemoveTasks(entry.getKey(),entry.getValue());
            try {entry.getValue().close();}catch(Exception ignored){}
        }
        displays.clear();
        if(frameThread!=null) {frameThread.quitSafely();frameThread=null;}
        System.exit(0);
    }
}
