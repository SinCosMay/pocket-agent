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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku UserService, executed under Android shell UID.
 * The app process never receives ADD_TRUSTED_DISPLAY or INJECT_EVENTS.
 */
public final class ShellDisplayService extends IDisplayShell.Stub {
    private static final int PUBLIC=1, PRESENTATION=2, OWN_CONTENT=8;
    private static final int TOUCH=1<<6, ROTATE=1<<7, DESTROY_CONTENT=1<<8;
    private static final int TRUSTED=1<<10, OWN_GROUP=1<<11, ALWAYS_UNLOCKED=1<<12;
    private static final int OWN_FOCUS=1<<14, DEVICE_GROUP=1<<15;
    private final Context context;
    private final Map<Integer,OwnedDisplay> displays=new LinkedHashMap<>();
    private HandlerThread frameThread;

    private static final class OwnedDisplay {
        final VirtualDisplay display;
        final ImageReader reader;
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
        displays.remove(id).close();
        if(displays.isEmpty() && frameThread!=null) {frameThread.quitSafely();frameThread=null;}
    }
    private static boolean exec(String... command) {
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
        java.lang.Process child=null;
        try {
            child=builder.start();
            if(!child.waitFor(8,TimeUnit.SECONDS)) {child.destroyForcibly();return false;}
            return child.exitValue()==0;
        } catch(Exception e) {
            if(child!=null) child.destroyForcibly();
            return false;
        }
    }
    @Override public synchronized boolean launchActivity(int id,String componentName) {
        owned(id);
        android.content.ComponentName component=android.content.ComponentName.unflattenFromString(componentName);
        if(component==null || component.getPackageName().isBlank()) throw new IllegalArgumentException("Expected component name");
        return exec("/system/bin/am","start","--display",String.valueOf(id),"-f","0x18000000","-n",component.flattenToString());
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
        for(OwnedDisplay d:displays.values()) try {d.close();}catch(Exception ignored){}
        displays.clear();
        if(frameThread!=null) {frameThread.quitSafely();frameThread=null;}
        System.exit(0);
    }
}
