package dev.pocket.agent;

import android.service.notification.NotificationListenerService;

public final class PhoneNotifications extends NotificationListenerService {
    static volatile PhoneNotifications connected;
    @Override public void onNotificationPosted(android.service.notification.StatusBarNotification posted) {
        if(!AutonomyStore.enabled(this)||posted.getPackageName().equals(getPackageName()))return;
        android.os.Bundle extras=posted.getNotification().extras;
        String body=String.valueOf(extras.getCharSequence(android.app.Notification.EXTRA_TITLE,""))+"\n"+String.valueOf(extras.getCharSequence(android.app.Notification.EXTRA_TEXT,""));
        if(body.length()>2000)body=body.substring(0,2000);
        try(AutonomyStore store=new AutonomyStore(this)) {
            org.json.JSONArray watches=store.load().getJSONArray("watches");boolean matches=false;
            for(int i=0;i<watches.length();i++) {
                org.json.JSONObject watch=watches.getJSONObject(i);String contains=watch.optString("contains");
                if(watch.optBoolean("enabled")&&posted.getPackageName().equals(watch.optString("packageName"))&&(contains.isEmpty()||body.toLowerCase(java.util.Locale.ROOT).contains(contains.toLowerCase(java.util.Locale.ROOT)))){matches=true;break;}
            }
            if(matches)store.incoming(Json.obj("id","notification:"+posted.getKey()+":"+posted.getPostTime(),"type","notification","packageName",posted.getPackageName(),"text",body));
        } catch(Exception error){android.util.Log.w("PocketAutonomy","Could not queue notification event",error);}
    }
    @Override public void onListenerConnected() {connected=this;}
    @Override public void onListenerDisconnected() {if(connected==this) connected=null;}
    @Override public void onDestroy() {if(connected==this) connected=null;super.onDestroy();}
}
