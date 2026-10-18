package dev.pocket.agent;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.pocket.android.AndroidScreenBackend;
import dev.pocket.control.ControlBackend.*;
import dev.pocket.control.ControlException;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic chooser on a trusted virtual display; no personal apps or Shizuku setup required. */
@RunWith(AndroidJUnit4.class)
public class PhoneTargetDeviceTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final UiAutomation automation=InstrumentationRegistry.getInstrumentation()
        .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);

    private final class Chooser implements AutoCloseable {
        ImageReader reader;
        VirtualDisplay display;
        AndroidScreenBackend screen;
        Chooser(String mode) throws Exception {
            try {
                DeviceTestAccess.ensureAccessibility();
                automation.adoptShellPermissionIdentity("android.permission.ADD_TRUSTED_DISPLAY",
                    "android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY");
                try {
                    reader=ImageReader.newInstance(720,1280,PixelFormat.RGBA_8888,2);
                    reader.setOnImageAvailableListener(r->{try(Image frame=r.acquireLatestImage()) {}},new Handler(Looper.getMainLooper()));
                    display=context.getSystemService(DisplayManager.class).createVirtualDisplay("PocketChooserTest",720,1280,320,reader.getSurface(),
                        1|8|64|1024|2048|4096|16384|32768);
                } finally {automation.dropShellPermissionIdentity();}
                assertNotNull(display);
                screen=AgentAccessibility.connected.backend(display.getDisplay().getDisplayId());
                String fixtureClass="launch_failure".equals(mode)
                    ?"dev.pocket.agent.fixture.MissingActivity":"dev.pocket.agent.fixture.FixtureActivity";
                context.startActivity(new Intent().setClassName("dev.pocket.agent.fixture",fixtureClass)
                    .putExtra("chooser_case",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                    ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplay().getDisplayId()).toBundle());
                observed("CHOOSER_READY");
            } catch(Exception|AssertionError error) {
                close();
                throw error;
            }
        }
        Observation observed(String expected) throws Exception {
            long end=System.currentTimeMillis()+6000;
            while(System.currentTimeMillis()<end) {
                try {
                    Observation o=screen.observe(false,720,200);
                    if(o.packageName().equals("dev.pocket.agent.fixture") && o.nodes().stream().anyMatch(n->n.text().equals(expected))) {
                        // Require a settled, fresh snapshot; never replay a dispatched action.
                        Thread.sleep(100);
                        o=screen.observe(false,720,200);
                        if(o.nodes().stream().anyMatch(n->n.text().equals(expected)))return o;
                    }
                } catch(ControlException e) {
                    if(e.code!=ControlException.Code.NOT_READY && e.code!=ControlException.Code.STALE_OBSERVATION)throw e;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("Missing chooser state: "+expected);
        }
        Action action(Kind kind,Selector selector,boolean point) throws Exception {
            Observation o=observed("CHOOSER_READY");
            Node row=o.nodes().stream().filter(n->n.description().equals("CHOOSER_ROW")).findFirst().orElse(null);
            Node panel=o.nodes().stream().filter(n->n.description().equals("CHOOSER_PANEL")).findFirst().orElseThrow();
            int x=row==null?panel.left()+100:(row.left()+row.right())/2;
            int y=row==null?panel.top()+200:(row.top()+row.bottom())/2;
            return new Action(kind,o.snapshotId(),point?x:null,point?y:null,
                null,null,800,null,false,null,null,selector);
        }
        void rejected(Action action,ControlException.Code code) throws Exception {
            try {screen.execute(action);fail("Unexpected dispatch");}
            catch(ControlException e) {assertEquals(e.getMessage(),code,e.code);}
            observed("CHOOSER_READY");
        }
        @Override public void close() {
            if(display!=null) {
                if(AgentAccessibility.connected!=null)AgentAccessibility.connected.release(display.getDisplay().getDisplayId());
                display.release();display=null;
            }
            if(reader!=null) {reader.close();reader=null;}
            // Public virtual-display removal can move its task to display 0. Explicitly
            // stop the synthetic fixture so teardown cannot contaminate later tests.
            try(android.os.ParcelFileDescriptor.AutoCloseInputStream input=
                    new android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am force-stop dev.pocket.agent.fixture"))) {
                byte[] buffer=new byte[64];while(input.read(buffer)!=-1) {}
            } catch(Exception ignored) {}
        }
    }
    private int chooserDisplayCount() {
        int count=0;
        for(android.view.Display item:context.getSystemService(DisplayManager.class).getDisplays())
            if("PocketChooserTest".equals(item.getName()))count++;
        return count;
    }
    @Test public void failedConstructionReleasesVirtualDisplayAndReader() throws Exception {
        int before=chooserDisplayCount();
        try {new Chooser("launch_failure");fail("Expected fixture launch failure");}
        catch(ActivityNotFoundException expected) {}
        long deadline=System.currentTimeMillis()+2000;
        while(chooserDisplayCount()!=before && System.currentTimeMillis()<deadline)Thread.sleep(50);
        assertEquals(before,chooserDisplayCount());
    }
    @Test public void uniqueVisibleLabelResolvesNearestClickAndLongClickAncestor() throws Exception {
        for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser("normal")) {
            Action action=c.action(kind,new Selector(null,"Chrome",null),false);
            assertEquals("dispatched",c.screen.execute(action).status());
            c.observed(kind==Kind.CLICK?"CHOOSER_SELECTED":"CHOOSER_LONG_SELECTED");
        }
    }
    @Test public void exactSelectorUniquenessPrecedesAncestorResolution() throws Exception {
        for(String mode:new String[]{"duplicate","duplicate_disabled"})
            for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser(mode)) {
            c.rejected(c.action(kind,new Selector(null,"Chrome",null),false),ControlException.Code.AMBIGUOUS_TARGET);
        }
        try(Chooser c=new Chooser("normal")) {
            c.rejected(c.action(Kind.CLICK,new Selector(null,"Chrome","not-the-label"),false),ControlException.Code.ACTION_REJECTED);
        }
    }
    @Test public void disabledAndInvisibleLabelsCannotPromoteToOuterContainer() throws Exception {
        for(String mode:new String[]{"disabled","disabled_label","invisible"})
            for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser(mode)) {
                c.rejected(c.action(kind,new Selector(null,"Chrome",null),false),ControlException.Code.ACTION_REJECTED);
            }
    }
    @Test public void coordinateHitsChooseDeepestInAncestorChain() throws Exception {
        for(String mode:new String[]{"normal","ineligible_siblings","overflow_descendant"})
            for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser(mode)) {
            assertEquals("dispatched",c.screen.execute(c.action(kind,null,true)).status());
            c.observed(kind==Kind.CLICK?"CHOOSER_SELECTED":"CHOOSER_LONG_SELECTED");
        }
    }
    @Test public void overlappingSiblingTargetsRemainAmbiguousEvenWhenBoundsNest() throws Exception {
        for(String mode:new String[]{"overlap","contained_sibling"})
            for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser(mode)) {
                Action a=c.action(kind,null,true);
                // Hit the smaller sibling too, rather than its right-exclusive boundary.
                a=new Action(a.kind(),a.snapshotId(),a.x()-50,a.y(),null,null,0,null,false,null,null,null);
                c.rejected(a,ControlException.Code.AMBIGUOUS_TARGET);
            }
    }
    @Test public void rawGesturesReachChooserOnRequestedVirtualDisplayWithDispatchOnlyReceipts() throws Exception {
        for(Kind kind:new Kind[]{Kind.TAP,Kind.LONG_PRESS})try(Chooser c=new Chooser("normal")) {
            Result receipt=c.screen.execute(c.action(kind,null,true));
            assertEquals("dispatched",receipt.status());assertTrue(receipt.message().contains("not yet verified"));
            c.observed(kind==Kind.TAP?"CHOOSER_SELECTED":"CHOOSER_LONG_SELECTED");
        }
    }
    @Test public void coordinateHitsRejectDisabledOrInvisibleTargets() throws Exception {
        for(String mode:new String[]{"disabled_only","invisible_only"})
            for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser(mode)) {
                c.rejected(c.action(kind,null,true),ControlException.Code.ACTION_REJECTED);
            }
    }
    @Test public void longClickSkipsClickOnlyContainerAndDirectSelectorsStillWork() throws Exception {
        try(Chooser c=new Chooser("long_parent")) {
            assertEquals("dispatched",c.screen.execute(c.action(Kind.LONG_CLICK,new Selector(null,"Chrome",null),false)).status());
            c.observed("PANEL_LONG_CLICKED");
        }
        try(Chooser c=new Chooser("normal")) {
            assertEquals("dispatched",c.screen.execute(c.action(Kind.CLICK,new Selector(null,null,"CHOOSER_ROW"),false)).status());
            c.observed("CHOOSER_SELECTED");
        }
    }
    @Test public void completedGestureOutsideOptionDoesNotVerifySelection() throws Exception {
        try(Chooser c=new Chooser("normal")) {
            Observation o=c.observed("CHOOSER_READY");
            Result receipt=c.screen.execute(new Action(Kind.TAP,o.snapshotId(),700,1000,null,null,100,null,false,null,null,null));
            assertEquals("dispatched",receipt.status());assertTrue(receipt.message().contains("not yet verified"));
            c.observed("OUTER_CLICKED");
        }
    }
    @Test public void changedContextStillRejectsOldSelectorAndCoordinateSnapshots() throws Exception {
        try(Chooser c=new Chooser("normal")) {
            Action stale=c.action(Kind.CLICK,new Selector(null,"Chrome",null),false);
            c.screen.noteInteraction(android.os.SystemClock.uptimeMillis()+1);
            c.rejected(stale,ControlException.Code.STALE_OBSERVATION);
            stale=c.action(Kind.CLICK,null,true);
            c.screen.noteInteraction(android.os.SystemClock.uptimeMillis()+1);
            c.rejected(stale,ControlException.Code.STALE_OBSERVATION);
        }
    }
}
