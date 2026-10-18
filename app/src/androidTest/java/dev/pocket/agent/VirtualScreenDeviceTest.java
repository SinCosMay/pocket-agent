package dev.pocket.agent;

import android.content.Context;
import android.hardware.display.DisplayManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real virtual-display regression tests, run on the dedicated Fedora emulator. */
@RunWith(AndroidJUnit4.class)
public class VirtualScreenDeviceTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static JSONObject call(NativeTools tools,String name,JSONObject args,String owner) {return tools.execute(name,args,owner);}
    private static JSONObject args(String... values) throws Exception {
        JSONObject json=new JSONObject();for(int i=0;i<values.length;i+=2)json.put(values[i],values[i+1]);return json;
    }
    private static JSONObject ready(NativeTools tools,String owner,String pkg) throws Exception {
        long deadline=System.currentTimeMillis()+8000;JSONObject last=null;
        while(System.currentTimeMillis()<deadline) {
            last=observe(tools,owner,false);
            if(!last.optBoolean("isError") && pkg.equals(last.optString("package_name")))return last;
            Thread.sleep(150);
        }
        throw new AssertionError("Missing virtual UI "+pkg+": "+last);
    }
    private static JSONObject observe(NativeTools tools,String owner,boolean image) throws Exception {
        return call(tools,"phone_observe",new JSONObject().put("include_image",image),owner);
    }
    @Test public void acquireRequiresAccessibilityBeforeAllocatingDisplay() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        AgentAccessibility observer=AgentAccessibility.connected;
        assertNotNull(observer);
        AgentAccessibility.connected=null;
        try(NativeTools tools=new NativeTools(context)) {
            String owner="virtual-no-accessibility";
            JSONObject result=call(tools,"phone_screen",args("action","acquire"),owner);
            assertTrue(result.toString(),result.optBoolean("isError"));
            assertEquals(result.toString(),"PERMISSION_REQUIRED",result.optString("code"));
            JSONObject caps=call(tools,"phone_capabilities",new JSONObject(),owner);
            assertEquals(caps.toString(),0,caps.getJSONObject("virtual_displays").getInt("active"));
        } finally {
            AgentAccessibility.connected=observer;
        }
    }
    @Test public void coldDisplayHasWorkspaceWithoutOpenApp() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        try(NativeTools tools=new NativeTools(context)) {
            String owner="virtual-cold";
            try {
                JSONObject acquired=call(tools,"phone_screen",args("action","acquire"),owner);
                assertFalse(acquired.toString(),acquired.optBoolean("isError"));
                assertTrue(acquired.toString(),acquired.getInt("display_id")>0);
                JSONObject first=ready(tools,owner,"dev.pocket.agent");
                assertEquals(first.toString(),720,first.getInt("coordinate_width"));
                JSONObject picture=observe(tools,owner,true);
                assertFalse(picture.toString().substring(0,Math.min(450,picture.toString().length())),picture.optBoolean("isError"));
                assertNotNull(picture.optJSONArray("images"));
                System.out.println("POCKET_VIRTUAL_COLD_READY="+picture.getString("package_name"));
            }finally{tools.releaseOwner(owner);}
        }
    }
    @Test public void emptyDisplayObservationDoesNotRequireAccessibilityRoot() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        ShizukuDisplayClient backend=new ShizukuDisplayClient(context,()->{});
        int display=-1;
        try {
            display=backend.createDisplay();
            assertTrue(display>0);
            dev.pocket.android.AndroidScreenBackend screen=AgentAccessibility.connected.backend(display);
            dev.pocket.control.ControlBackend.Observation empty=screen.observe(false,1280,150);
            assertEquals(empty.toString(),-1,empty.windowId());
            assertEquals(empty.toString(),0,empty.nodes().size());
            dev.pocket.control.ControlBackend.Observation screenshot=screen.observe(true,1280,150);
            assertNotNull("Screenshot must be independent of accessibility roots",screenshot.image());
            assertTrue(screenshot.image().length>100);
            System.out.println("POCKET_EMPTY_DISPLAY_SCREENSHOT="+screenshot.image().length);
        }finally {
            if(display>0){AgentAccessibility.connected.release(display);backend.removeDisplay(display);}
            backend.close();
        }
    }
    @Test public void homeRecoveryDoesNotRequireExistingSnapshot() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        try(NativeTools tools=new NativeTools(context)) {
            String owner="virtual-home";
            try {
                JSONObject acquired=call(tools,"phone_screen",args("action","acquire"),owner);
                assertFalse(acquired.toString(),acquired.optBoolean("isError"));
                String prefix=call(tools,"phone_capabilities",new JSONObject(),owner).getString("operation_prefix");
                JSONObject invalid=call(tools,"phone_act",new JSONObject().put("kind","tap")
                    .put("x",10).put("y",20).put("operation_id",prefix+"_nosnapshot001"),owner);
                assertEquals("INVALID_ARGUMENT",invalid.optString("code"));
                JSONObject home=args("kind","key","key","home","operation_id",prefix+"_homefresh001");
                JSONObject result=call(tools,"phone_act",home,owner);
                assertFalse(result.toString(),result.optBoolean("isError"));
                ready(tools,owner,"dev.pocket.agent");
                JSONObject duplicate=call(tools,"phone_act",home,owner);
                assertEquals(result.toString(),duplicate.toString());
                System.out.println("POCKET_VIRTUAL_HOME_RECOVERED="+result.optString("status"));
            }finally{tools.releaseOwner(owner);}
        }
    }
    @Test public void directLaunchRendersAndHomeReturnsToWorkspace() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        try(NativeTools tools=new NativeTools(context)) {
            String owner="virtual-launch";
            try {
                JSONObject acquired=call(tools,"phone_screen",args("action","acquire"),owner);
                assertFalse(acquired.toString(),acquired.optBoolean("isError"));
                ready(tools,owner,"dev.pocket.agent");
                String prefix=call(tools,"phone_capabilities",new JSONObject(),owner).getString("operation_prefix");
                JSONObject opened=call(tools,"phone_open_app",args("package_name","dev.pocket.agent.fixture","operation_id",prefix+"_fixture001"),owner);
                assertFalse(opened.toString(),opened.optBoolean("isError"));
                JSONObject target=ready(tools,owner,"dev.pocket.agent.fixture");
                assertNotEquals(target.toString(),0,target.getInt("window_id"));
                JSONObject home=call(tools,"phone_act",args("kind","key","key","home","operation_id",prefix+"_homereturn001"),owner);
                assertFalse(home.toString(),home.optBoolean("isError"));
                ready(tools,owner,"dev.pocket.agent");
                System.out.println("POCKET_VIRTUAL_DIRECT_AND_HOME=ok");
            }finally{tools.releaseOwner(owner);}
        }
    }
    @Test public void screenOnlyLauncherOpensFixtureWithoutDirectLaunch() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        try(NativeTools tools=new NativeTools(context)) {
            String owner="virtual-menu";
            try {
                JSONObject acquired=call(tools,"phone_screen",args("action","acquire"),owner);
                assertFalse(acquired.toString(),acquired.optBoolean("isError"));
                String prefix=call(tools,"phone_capabilities",new JSONObject(),owner).getString("operation_prefix");
                JSONObject typed=null;
                for(int i=0;i<5;i++) {
                    JSONObject screen=ready(tools,owner,"dev.pocket.agent");
                    typed=call(tools,"phone_act",new JSONObject().put("kind","type")
                        .put("snapshot_id",screen.getString("snapshot_id"))
                        .put("selector",new JSONObject().put("description","Search apps"))
                        .put("text","Fixture").put("operation_id",prefix+"_search_"+i+"12345678"),owner);
                    if(!"STALE_OBSERVATION".equals(typed.optString("code")))break;
                    Thread.sleep(150);
                }
                assertFalse(String.valueOf(typed),typed.optBoolean("isError"));
                JSONObject clicked=null;
                for(int i=0;i<5;i++) {
                    JSONObject screen=ready(tools,owner,"dev.pocket.agent");
                    clicked=call(tools,"phone_act",new JSONObject().put("kind","click")
                        .put("snapshot_id",screen.getString("snapshot_id"))
                        .put("selector",new JSONObject().put("description","Open Pocket Agent Fixture"))
                        .put("operation_id",prefix+"_menuclick_"+i+"12345678"),owner);
                    if(!"STALE_OBSERVATION".equals(clicked.optString("code")))break;
                    Thread.sleep(150);
                }
                assertFalse(String.valueOf(clicked),clicked.optBoolean("isError"));
                ready(tools,owner,"dev.pocket.agent.fixture");
                System.out.println("POCKET_SCREEN_ONLY_NAVIGATION=ok");
            }finally{tools.releaseOwner(owner);}
        }
    }
    @Test public void releaseAndReacquireDoesNotReuseDeadDisplay() throws Exception {
        DeviceTestAccess.ensureAccessibility();
        try(NativeTools tools=new NativeTools(context)) {
            String owner="virtual-recycle";
            try {
                JSONObject first=call(tools,"phone_screen",args("action","acquire"),owner);
                assertFalse(first.toString(),first.optBoolean("isError"));
                int old=first.getInt("display_id");
                assertNotEquals(0,old);
                assertFalse(call(tools,"phone_screen",args("action","release"),owner).optBoolean("isError"));
                for(int attempt=0;attempt<20 && context.getSystemService(DisplayManager.class).getDisplay(old)!=null;attempt++)Thread.sleep(50);
                assertNull(context.getSystemService(DisplayManager.class).getDisplay(old));
                JSONObject next=call(tools,"phone_screen",args("action","acquire"),owner);
                assertFalse(next.toString(),next.optBoolean("isError"));
                assertNotEquals(old,next.getInt("display_id"));
                ready(tools,owner,"dev.pocket.agent");
                JSONObject other=call(tools,"phone_observe",new JSONObject(),"unleased-owner");
                assertEquals(other.toString(),"NOT_READY",other.optString("code"));
            }finally{tools.releaseOwner(owner);}
        }
    }
}
