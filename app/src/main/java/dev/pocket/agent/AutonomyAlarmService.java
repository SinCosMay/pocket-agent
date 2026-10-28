package dev.pocket.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Bounded foreground execution owned by a user-scheduled exact alarm. */
public final class AutonomyAlarmService extends Service {
    private volatile boolean stopped;
    private boolean running;
    private ActiveWorkWakeLock wakeLock;
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("autonomy-running","Scheduled work",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,AgentService.class).setAction(AgentService.ACTION_STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this,"autonomy-running").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pocket Agent is running scheduled work").setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"Stop all",stop).build()).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(34,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(34,notification);
        wakeLock=new ActiveWorkWakeLock(this);wakeLock.setActive(true);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(!running) {
            running=true;
            new Thread(()->{
                try {
                    android.net.ConnectivityManager connectivity=getSystemService(android.net.ConnectivityManager.class);
                    android.net.NetworkCapabilities network=connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
                    if(network==null||!network.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        // Leave the trigger unconsumed; WorkManager waits for connectivity.
                        AutonomyScheduler.run(getApplicationContext());
                    } else AutonomyWorker.execute(getApplicationContext(),()->stopped||!AutonomyStore.enabled(this));
                } catch(Exception error) {
                    android.util.Log.e("PocketAutonomy","Alarm execution failed",error);AutonomyScheduler.arm(this);
                }
                finally{stopSelf();}
            },"PocketAlarmRun").start();
        }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){stopped=true;if(wakeLock!=null)wakeLock.setActive(false);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
