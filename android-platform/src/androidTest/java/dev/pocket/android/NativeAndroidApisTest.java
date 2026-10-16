package dev.pocket.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.pocket.control.ControlException;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** No dial/SMS side effects: exercises provider queries and predispatch call validation. */
@RunWith(AndroidJUnit4.class)
public class NativeAndroidApisTest {
    private Context context() {return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    @Test public void invalidCallCannotDial() throws Exception {
        Context c=context();
        if(c.checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED)return;
        AndroidSystemActions system=new AndroidSystemActions(c);
        try {
            system.callNumber("not-a-number",null,false);
            fail("Invalid number accepted");
        }catch(ControlException expected) {
            assertEquals(ControlException.Code.INVALID_ARGUMENT,expected.code);
        }
    }
    @Test public void calendarProviderQueryWorksWithoutAnyScreen() throws Exception {
        Context c=context();
        if(c.checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED) {
            try {
                new AndroidSystemActions(c).calendars();
                fail("Expected permission check");
            } catch(ControlException expected) {
                assertEquals(ControlException.Code.PERMISSION_REQUIRED,expected.code);
            }
            return;
        }
        AndroidSystemActions system=new AndroidSystemActions(c);
        assertNotNull(system.calendars());
        long now=System.currentTimeMillis();
        AndroidSystemActions.CalendarEvents events=system.calendarEvents(now,now+86400000L,null,50);
        assertNotNull(events);
        assertNotNull(events.events());
        assertTrue(events.events().size()<=50);
        try {
            system.calendarEvents(1000,999,null,50);
            fail("Invalid time window accepted");
        }catch(ControlException expected) {
            assertEquals(ControlException.Code.INVALID_ARGUMENT,expected.code);
        }
    }
}
