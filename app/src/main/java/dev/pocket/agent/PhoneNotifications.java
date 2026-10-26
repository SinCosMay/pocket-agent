package dev.pocket.agent;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;

public final class PhoneNotifications extends NotificationListenerService {
    static volatile PhoneNotifications connected;
    private static final java.util.concurrent.ThreadPoolExecutor WORK=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,
        new java.util.concurrent.ArrayBlockingQueue<>(64));
    static String notificationText(Bundle extras) {
        if(extras==null)return "";
        java.util.LinkedHashSet<String> content=new java.util.LinkedHashSet<>();
        for(String key:new String[]{Notification.EXTRA_TITLE,Notification.EXTRA_TEXT,Notification.EXTRA_BIG_TEXT,Notification.EXTRA_SUB_TEXT}) {
            CharSequence value=extras.getCharSequence(key);if(value!=null&&!value.toString().isEmpty())content.add(value.toString());
        }
        CharSequence[] lines=extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if(lines!=null)for(CharSequence line:lines)if(line!=null&&!line.toString().isEmpty())content.add(line.toString());
        android.os.Parcelable[] messages=extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if(messages!=null)for(android.os.Parcelable value:messages)if(value instanceof Bundle message){CharSequence text=message.getCharSequence("text");if(text!=null&&!text.toString().isEmpty())content.add(text.toString());}
        return String.join("\n",content);
    }
    static String clip(String text) {
        int end=Math.min(2000,text.length());if(end<text.length()&&end>0&&Character.isHighSurrogate(text.charAt(end-1)))end--;
        return text.substring(0,end);
    }
    @Override public void onNotificationPosted(android.service.notification.StatusBarNotification posted) {
        if(posted==null||!AutonomyStore.enabled(this)||posted.getPackageName().equals(getPackageName())||!AutonomyStore.mayWatch(posted.getPackageName()))return;
        final android.content.Context context=getApplicationContext();
        try{WORK.execute(()->{
            try(AutonomyStore store=new AutonomyStore(context)) {
                if(!AutonomyStore.enabled(context))return;
                String body=notificationText(posted.getNotification().extras);
                org.json.JSONObject root=store.load();org.json.JSONArray watches=root.getJSONArray("watches"),matches=new org.json.JSONArray();
                for(int i=0;i<watches.length();i++) {
                    org.json.JSONObject watch=watches.getJSONObject(i);String contains=watch.optString("contains");
                    if(watch.optBoolean("enabled")&&AutonomyStore.activeTask(root,watch.optString("taskId"))&&posted.getPackageName().equals(watch.optString("packageName"))&&(contains.isEmpty()||body.toLowerCase(java.util.Locale.ROOT).contains(contains.toLowerCase(java.util.Locale.ROOT))))matches.put(watch.getString("id"));
                }
                // Preserve match provenance: the bounded event excerpt may omit the matched word.
                if(matches.length()>0)store.incoming(Json.obj("id","notification:"+posted.getKey()+":"+posted.getPostTime(),"type","notification","packageName",posted.getPackageName(),"text",clip(body),"matchedWatchIds",matches));
            } catch(Exception error){android.util.Log.w("PocketAutonomy","Could not queue notification event: "+error.getClass().getSimpleName());}
        });}catch(java.util.concurrent.RejectedExecutionException full){android.util.Log.w("PocketAutonomy","Notification queue full (64); newest event omitted");}
    }
    @Override public void onListenerConnected() {connected=this;}
    @Override public void onListenerDisconnected() {if(connected==this) connected=null;}
    @Override public void onDestroy() {if(connected==this) connected=null;super.onDestroy();}
}
