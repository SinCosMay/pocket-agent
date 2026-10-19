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
    private static final String CHANNEL="accessibility-recovery";
    private final Handler main=new Handler(Looper.getMainLooper());
    private int misses;
    private final Runnable check=new Runnable() {
        @Override public void run() {
            if(!stillEnabled()) {
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return;
            }
            if(AccessibilityProxyBackend.connected(AccessibilityGuardianService.this))misses=0;
            else if(++misses>=3) {
                misses=0;
                try {startForegroundService(new Intent(AccessibilityGuardianService.this,AccessibilityRepairService.class));}
                catch(RuntimeException ignored) {}
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
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Accessibility recovery",NotificationManager.IMPORTANCE_MIN));
        Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Pocket Agent accessibility recovery").setContentText("Watching the enabled accessibility service for crashes")
            .setOngoing(true).setOnlyAlertOnce(true).build();
        if(android.os.Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION,notification);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        main.removeCallbacks(check);main.post(check);return START_STICKY;
    }
    @Override public void onDestroy() {main.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public IBinder onBind(Intent intent) {return null;}
}
