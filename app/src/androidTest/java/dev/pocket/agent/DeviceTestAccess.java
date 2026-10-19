package dev.pocket.agent;
import android.app.UiAutomation;
import android.content.Context;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
/** Test-only permission provisioning; no exported production entrypoint. */
final class DeviceTestAccess {
 static void ensureAccessibility() throws Exception {
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  if(AccessibilityProxyBackend.connected(c))return;
  UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
  try {
   String name="dev.pocket.agent/.AgentAccessibility";
   String current=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
   java.util.LinkedHashSet<String> others=new java.util.LinkedHashSet<>();
   if(current!=null)for(String part:current.split(":"))if(!part.equals(name)&&!part.equals("dev.pocket.agent/dev.pocket.agent.AgentAccessibility")&&!part.isEmpty())others.add(part);
   Settings.Secure.putString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",others));
   // Settings observers run asynchronously. Let the disabled state clear Android's crashed-service record.
   Thread.sleep(300);
   others.add(name);Settings.Secure.putString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",others));
   Settings.Secure.putInt(c.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
  }finally{automation.dropShellPermissionIdentity();}
  long deadline=System.currentTimeMillis()+15000;
  while(!AccessibilityProxyBackend.connected(c)&&System.currentTimeMillis()<deadline)Thread.sleep(100);
  if(!AccessibilityProxyBackend.connected(c))throw new AssertionError("Accessibility did not bind after test provisioning");
 }
}
