package dev.pocket.agent;
import android.app.UiAutomation;
import android.content.Context;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
/** Test-only permission provisioning; no exported production entrypoint. */
final class DeviceTestAccess {
 static void ensureAccessibility() throws Exception {
  if(AgentAccessibility.connected!=null)return;
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
  try {
   String name="dev.pocket.agent/.AgentAccessibility";
   String current=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
   java.util.LinkedHashSet<String> others=new java.util.LinkedHashSet<>();
   if(current!=null)for(String part:current.split(":"))if(!part.equals(name)&&!part.equals("dev.pocket.agent/dev.pocket.agent.AgentAccessibility")&&!part.isEmpty())others.add(part);
   Settings.Secure.putString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",others));
   others.add(name);Settings.Secure.putString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",others));
   Settings.Secure.putInt(c.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
  }finally{automation.dropShellPermissionIdentity();}
  long deadline=System.currentTimeMillis()+15000;
  while(AgentAccessibility.connected==null&&System.currentTimeMillis()<deadline)Thread.sleep(100);
  if(AgentAccessibility.connected==null)throw new AssertionError("Accessibility did not bind after test provisioning");
 }
}
