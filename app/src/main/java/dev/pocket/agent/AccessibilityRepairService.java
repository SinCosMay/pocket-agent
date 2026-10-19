package dev.pocket.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

public final class AccessibilityRepairService extends Service {
    private static final int NOTIFICATION=62;
    private static final String CHANNEL="accessibility-repair";
    private volatile boolean running;
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
            try {
                String prerequisite=ShizukuDisplayClient.prerequisites();
                if(!"idle".equals(prerequisite))outcome=prerequisite;
                else {
                    // Recovery borrows the shared display UserService. Closing this temporary
                    // client must only drop its binding, never remove active worker displays.
                    client=new ShizukuDisplayClient(this,()->{},false);
                    boolean repaired=client.repairAccessibility(new ComponentName(this,AgentAccessibility.class).flattenToShortString());
                    outcome=repaired?"triggered":"failed";
                }
            } catch(Exception failure) {
                android.util.Log.w("PocketAccessibility","Accessibility repair unavailable",failure);
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
