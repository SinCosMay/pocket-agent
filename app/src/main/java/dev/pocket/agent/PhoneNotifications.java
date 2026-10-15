package dev.pocket.agent;

import android.service.notification.NotificationListenerService;

public final class PhoneNotifications extends NotificationListenerService {
    static volatile PhoneNotifications connected;
    @Override public void onListenerConnected() {connected=this;}
    @Override public void onListenerDisconnected() {if(connected==this) connected=null;}
    @Override public void onDestroy() {if(connected==this) connected=null;super.onDestroy();}
}
