package dev.pocket.agent;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;

public final class AccessibilityRepairService extends Service {
    private static final int NOTIFICATION=62;
    private static final String CHANNEL="accessibility-repair";
    private volatile boolean running;

    private boolean enabledByUser(ComponentName target) {
        try {
            if(Settings.Secure.getInt(getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0)!=1)return false;
            String raw=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if(raw==null)return false;
            for(String item:raw.split(":"))if(target.equals(ComponentName.unflattenFromString(item.trim())))return true;
        } catch(RuntimeException ignored) {}
        return false;
    }
    private boolean safeForAutomaticRepair() {
        PowerManager power=getSystemService(PowerManager.class);
        KeyguardManager keyguard=getSystemService(KeyguardManager.class);
        return !power.isInteractive()||keyguard.isKeyguardLocked();
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Accessibility repair",NotificationManager.IMPORTANCE_MIN));
        Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Pocket Agent accessibility repair").setContentText("Restoring the enabled accessibility connection")
            .setOngoing(true).setOnlyAlertOnce(true).build();
        if(android.os.Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION,notification);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(running)return START_NOT_STICKY;
        running=true;
        Thread worker=new Thread(()->{
            ShizukuDisplayClient client=null;String outcome="failed";
            ComponentName component=new ComponentName(this,AgentAccessibility.class);
            boolean pending=AccessibilityRepairState.pending(this);
            boolean enabled=enabledByUser(component);
            try {
                if(!enabled&&!pending)outcome="disabled";
                else if(!safeForAutomaticRepair()) {
                    // Never rewrite Accessibility settings while an unlocked user can be
                    // changing them. If an interrupted repair left the component absent,
                    // abandon automatic restoration rather than undo a possible user disable.
                    if(!enabled&&pending)AccessibilityRepairState.clear(this);
                    outcome="deferred_unlocked";
                } else {
                    String prerequisite=ShizukuDisplayClient.prerequisites();
                    if(!"idle".equals(prerequisite))outcome=prerequisite;
                    else {
                        if(enabled&&!pending)AccessibilityRepairState.begin(this);
                        client=new ShizukuDisplayClient(this,()->{},false);
                        boolean repaired=client.repairAccessibility(component.flattenToShortString(),!enabled&&pending);
                        outcome=repaired?"triggered":"failed";
                        long deadline=android.os.SystemClock.elapsedRealtime()+4000;
                        while(repaired&&android.os.SystemClock.elapsedRealtime()<deadline) {
                            if(AccessibilityProxyBackend.connected(this)) {AccessibilityRepairState.clear(this);outcome="restored";break;}
                            try {Thread.sleep(100);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();break;}
                        }
                        if(!enabledByUser(component)&&!AccessibilityRepairState.pending(this))outcome="disabled";
                        else if(!enabledByUser(component)&&!"triggered".equals(outcome)) {
                            // The marker deliberately survives a failed mid-repair attempt so
                            // the independent guardian can retry once Shizuku is available.
                            outcome="repair_pending";
                        } else if(enabledByUser(component)&&!"restored".equals(outcome)) {
                            AccessibilityRepairState.clear(this);
                        }
                    }
                }
            } catch(Exception failure) {
                android.util.Log.w("PocketAccessibility","Accessibility repair unavailable",failure);
                if(enabledByUser(component))AccessibilityRepairState.clear(this);
            } finally {
                if(client!=null)try {client.close();}catch(RuntimeException ignored){}
                try {
                    startService(new Intent(this,AccessibilityGuardianService.class)
                        .setAction(AccessibilityGuardianService.ACTION_REPAIR_RESULT)
                        .putExtra(AccessibilityGuardianService.EXTRA_REPAIR_RESULT,outcome));
                } catch(RuntimeException ignored) {}
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            }
        },"PocketAccessibilityRepair");
        worker.setDaemon(true);worker.start();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) {return null;}
}
