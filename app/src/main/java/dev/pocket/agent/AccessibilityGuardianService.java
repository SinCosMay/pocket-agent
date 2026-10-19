package dev.pocket.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

public final class AccessibilityGuardianService extends Service {
    private static final int NOTIFICATION=61;
    static final String ACTION_REPAIR_RESULT="dev.pocket.agent.ACCESSIBILITY_REPAIR_RESULT";
    static final String EXTRA_REPAIR_RESULT="repair_result";
    private static final String CHANNEL="accessibility-recovery";
    private static final int MAX_REPAIRS=3;
    private final Handler main=new Handler(Looper.getMainLooper());
    private int misses,repairAttempts;
    private long startedAt,nextRepairAt;
    private final Runnable check=new Runnable() {
        @Override public void run() {
            if(!stillEnabled()) {
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return;
            }
            long now=android.os.SystemClock.elapsedRealtime();
            if(AccessibilityProxyBackend.connected(AccessibilityGuardianService.this)) {
                misses=0;repairAttempts=0;nextRepairAt=0;
                updateNotification("Accessibility connected");
            } else if(now-startedAt>=12_000 && ++misses>=3 && now>=nextRepairAt) {
                misses=0;
                if(repairAttempts>=MAX_REPAIRS) {
                    updateNotification("Recovery paused after repeated failures · check Shizuku and Accessibility settings");
                    main.postDelayed(this,30_000);return;
                }
                repairAttempts++;
                nextRepairAt=now+Math.min(60_000,5_000L<<(repairAttempts-1));
                updateNotification("Accessibility disconnected · recovery attempt "+repairAttempts+" of "+MAX_REPAIRS);
                try {startForegroundService(new Intent(AccessibilityGuardianService.this,AccessibilityRepairService.class));}
                catch(RuntimeException failure) {updateNotification("Recovery could not start · check Shizuku");}
            }
            main.postDelayed(this,2000);
        }
    };
    private boolean stillEnabled() {
        String raw=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if(raw==null||raw.isBlank())return false;
        ComponentName expected=new ComponentName(this,AgentAccessibility.class);
        for(String item:raw.split(":")) {
            ComponentName parsed=ComponentName.unflattenFromString(item);
            if(expected.equals(parsed))return true;
        }
        return false;
    }
    private Notification notification(String text) {
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Pocket Agent accessibility recovery").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).build();
    }
    private void updateNotification(String text) {
        try {getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(text));}
        catch(RuntimeException ignored) {}
    }
    @Override public void onCreate() {
        super.onCreate();startedAt=android.os.SystemClock.elapsedRealtime();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Accessibility recovery",NotificationManager.IMPORTANCE_MIN));
        Notification notification=notification("Waiting for the enabled accessibility service to bind");
        if(android.os.Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION,notification);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null&&ACTION_REPAIR_RESULT.equals(intent.getAction())) {
            String result=intent.getStringExtra(EXTRA_REPAIR_RESULT);
            if("shizuku_unavailable".equals(result)||"permission_required".equals(result))
                updateNotification("Recovery blocked by Shizuku · start Shizuku and grant Pocket Agent");
            else if("failed".equals(result))updateNotification("Accessibility recovery attempt failed");
        }
        main.removeCallbacks(check);main.post(check);return START_STICKY;
    }
    @Override public void onDestroy() {main.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public IBinder onBind(Intent intent) {return null;}
}
