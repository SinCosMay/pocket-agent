package dev.pocket.agent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.PowerManager;
import org.json.JSONArray;
import org.json.JSONObject;

/** Separate from transcript checkpoints: timers and external events survive runtime shutdown. */
final class AutonomyStore extends SQLiteOpenHelper {
    static final Object LOCK=new Object();
    private final Context context;
    private final boolean production;
    private static volatile java.util.Set<String> watchedPackages;
    AutonomyStore(Context context) {this(context,"agent-autonomy.db");}
    AutonomyStore(Context context,String databaseName) {super(context,databaseName,null,1);this.context=context.getApplicationContext();this.production="agent-autonomy.db".equals(databaseName);}
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE state (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE incoming (id TEXT PRIMARY KEY, json TEXT NOT NULL, at INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to) {throw new IllegalStateException("Unsupported autonomy migration");}
    JSONObject load() throws Exception {
        synchronized(LOCK) {
            try(Cursor c=getReadableDatabase().rawQuery("SELECT json FROM state WHERE id=1",null)) {
                if(c.moveToFirst()){JSONObject state=new JSONObject(c.getString(0));refreshWatchCache(state);return state;}
            }
            JSONObject root=Json.obj("version",1,"revision",0);
            for(String key:new String[]{"schedules","wakeups","tasks","watches","skills","decisions","notifications","runs","seenEvents"})root.put(key,new JSONArray());
            refreshWatchCache(root);return root;
        }
    }
    void save(JSONObject state,long expectedRevision) throws Exception {
        String json=state.toString();if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1_800_000)throw new IllegalArgumentException("Autonomy store limit reached");
        synchronized(LOCK) {
            SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
            // Unknown cache means queue a fresh lookup while the transaction changes watches.
            if(production)watchedPackages=null;
            try {
                if(load().optLong("revision")!=expectedRevision||state.getLong("revision")!=expectedRevision+1)throw new IllegalStateException("Stale autonomy writer");
                if(production)watchedPackages=null;
                ContentValues values=new ContentValues();values.put("id",1);values.put("json",json);
                if(db.insertWithOnConflict("state",null,values,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("Could not persist autonomy state");
                db.setTransactionSuccessful();
            } finally {db.endTransaction();}
            refreshWatchCache(state);
        }
        if(production)AutonomyScheduler.arm(context);
    }
    private void refreshWatchCache(JSONObject state) throws Exception {
        if(!production)return;
        java.util.Set<String> packages=new java.util.HashSet<>();JSONArray watches=state.getJSONArray("watches");
        for(int i=0;i<watches.length();i++){JSONObject watch=watches.getJSONObject(i);if(watch.optBoolean("enabled")&&activeTask(state,watch.optString("taskId")))packages.add(watch.optString("packageName"));}
        watchedPackages=java.util.Collections.unmodifiableSet(packages);
    }
    static boolean mayWatch(String packageName){java.util.Set<String> packages=watchedPackages;return packages==null||packages.contains(packageName);}
    // A pending pause is visible before disk/SQLite locks; only its own writer may clear it.
    private record PolicyChange(boolean enabled) {}
    private static final java.util.concurrent.atomic.AtomicReference<PolicyChange> pendingPolicy=new java.util.concurrent.atomic.AtomicReference<>();
    static boolean enabled(Context context) {
        PolicyChange pending=pendingPolicy.get();
        return (pending==null||pending.enabled())&&context.getSharedPreferences("autonomy-policy",0).getBoolean("enabled",true);
    }
    static Object beginPolicyChange(boolean enabled){PolicyChange change=new PolicyChange(enabled);pendingPolicy.set(change);return change;}
    static boolean applyPolicyChange(Context context,Object token) {
        synchronized(LOCK){
            PolicyChange change=(PolicyChange)token;if(pendingPolicy.get()!=change)return false;
            persistEnabled(context,change.enabled());pendingPolicy.compareAndSet(change,null);return true;
        }
    }
    static void requestEnabled(Context context,boolean enabled,java.util.function.Consumer<Exception> onFailure) {
        Object change=beginPolicyChange(enabled);Context app=context.getApplicationContext();
        AutonomyScheduler.execute(()->{try{
            if(applyPolicyChange(app,change)){AutonomyScheduler.arm(app);if(enabled)AutonomyScheduler.run(app);}
        }catch(Exception error){onFailure.accept(error);}});
    }
    static void setEnabled(Context context,boolean enabled) {
        Object change=beginPolicyChange(enabled);if(applyPolicyChange(context,change))AutonomyScheduler.arm(context);
    }
    static void persistEnabled(Context context,boolean enabled) {
        synchronized(LOCK) {
            android.content.SharedPreferences.Editor edit=context.getSharedPreferences("autonomy-policy",0).edit().putBoolean("enabled",enabled);
            if(enabled)edit.remove("startupFailures").remove("backoffUntil").remove("lastFailure");
            if(!edit.commit())throw new IllegalStateException("Could not save automation setting");
        }
    }
    static void startupFailed(Context context) {
        synchronized(LOCK) {
            if(!enabled(context))return;
            android.content.SharedPreferences policy=context.getSharedPreferences("autonomy-policy",0);
            int failures=Math.min(3,policy.getInt("startupFailures",0)+1);
            long retry=System.currentTimeMillis()+60_000L*(1L<<(failures-1));
            android.content.SharedPreferences.Editor edit=policy.edit().putInt("startupFailures",failures).putLong("backoffUntil",retry)
                .putString("lastFailure",failures>=3?"Background startup failed repeatedly. Review the app and enable automation to retry.":"Background startup failed. The next attempt will be delayed.");
            if(failures>=3)edit.putBoolean("enabled",false);
            if(!edit.commit())throw new IllegalStateException("Could not persist background backoff");
        }
    }
    static void startupHealthy(Context context) {
        synchronized(LOCK){if(!context.getSharedPreferences("autonomy-policy",0).edit().remove("startupFailures").remove("backoffUntil").remove("lastFailure").commit())throw new IllegalStateException("Could not clear background backoff");}
    }
    JSONArray events() throws Exception {
        synchronized(LOCK) {
            JSONArray result=new JSONArray();
            try(Cursor c=getReadableDatabase().rawQuery("SELECT json FROM incoming ORDER BY at,id LIMIT 64",null)) {while(c.moveToNext())result.put(new JSONObject(c.getString(0)));}
            return result;
        }
    }
    void ack(JSONArray ids) {
        synchronized(LOCK) {for(int i=0;i<ids.length();i++)getWritableDatabase().delete("incoming","id=?",new String[]{ids.optString(i)});}
        if(production)AutonomyScheduler.arm(context);
    }
    void incoming(JSONObject event) throws Exception {
        synchronized(LOCK) {
            ContentValues values=new ContentValues();values.put("id",event.getString("id"));values.put("json",event.toString());values.put("at",System.currentTimeMillis());
            SQLiteDatabase db=getWritableDatabase();
            boolean duplicate;
            try(Cursor existing=db.rawQuery("SELECT 1 FROM incoming WHERE id=?",new String[]{event.getString("id")})){duplicate=existing.moveToFirst();}
            if(!duplicate){
                try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM incoming",null)){if(c.moveToFirst()&&c.getInt(0)>=64)throw new IllegalStateException("Autonomy event inbox full");}
                if(db.insertWithOnConflict("incoming",null,values,SQLiteDatabase.CONFLICT_IGNORE)<0)throw new IllegalStateException("Could not persist incoming event");
            }
        }
        if(production)AutonomyScheduler.arm(context);
    }
    long nextDue() throws Exception {
        if(!enabled(context))return Long.MAX_VALUE;
        synchronized(LOCK) {
            long backoff=context.getSharedPreferences("autonomy-policy",0).getLong("backoffUntil",0);
            if(events().length()>0)return Math.max(System.currentTimeMillis(),backoff);
            JSONObject root=load();long next=Long.MAX_VALUE;
            for(String key:new String[]{"schedules","wakeups"}) {
                JSONArray items=root.getJSONArray(key);
                for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);if(item.optBoolean("enabled")&&!item.isNull("nextAt")&&activeTask(root,item.optString("taskId")))next=Math.min(next,item.optLong("nextAt",Long.MAX_VALUE));}
            }
            JSONArray runs=root.getJSONArray("runs");long queuedDue=Long.MAX_VALUE;boolean active=false;
            for(int i=0;i<runs.length();i++) {
                JSONObject run=runs.getJSONObject(i);String status=run.optString("status");
                if(status.equals("queued")&&activeTask(root,run.optString("taskId"))){queuedDue=Math.min(queuedDue,Math.max(System.currentTimeMillis()+1000,run.optLong("nextAttemptAt")));}
                if(status.equals("running")||status.equals("dispatching")){active=true;next=Math.min(next,run.optLong("startedAt",run.optLong("createdAt"))+9*60_000L);}
            }
            if(!active)next=Math.min(next,queuedDue);
            return next==Long.MAX_VALUE?next:Math.max(next,backoff);
        }
    }
    static boolean activeTask(JSONObject root,String id) throws Exception {
        JSONArray tasks=root.getJSONArray("tasks");
        for(int i=0;i<tasks.length();i++){JSONObject task=tasks.getJSONObject(i);if(id.equals(task.optString("id")))return "active".equals(task.optString("status"));}
        return false;
    }
    JSONObject status() {
        ConnectivityManager connectivity=context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities network=connectivity==null?null:connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        PowerManager power=context.getSystemService(PowerManager.class);
        NotificationManager notifications=context.getSystemService(NotificationManager.class);
        return Json.obj("enabled",enabled(context),"timing",AutonomyScheduler.exactAlarmsAllowed(context)?"exact_android_alarm":"inexact_android_alarm",
            "exactAlarmPermission",AutonomyScheduler.exactAlarmsAllowed(context),"connected",network!=null&&network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            "notificationsEnabled",notifications!=null&&notifications.areNotificationsEnabled(),"notificationAccess",PhoneNotifications.connected!=null,
            "batteryRestricted",power!=null&&!power.isIgnoringBatteryOptimizations(context.getPackageName()),"maxRunDurationMs",8*60_000,"phoneMustBeOnline",true,
            "lastFailure",context.getSharedPreferences("autonomy-policy",0).getString("lastFailure",""),"retryAfter",context.getSharedPreferences("autonomy-policy",0).getLong("backoffUntil",0));
    }
    JSONObject notifyResult(JSONObject entry) throws Exception {
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null||!manager.areNotificationsEnabled()||android.os.Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return Json.obj("status","blocked","reason","Notification permission disabled; result remains in app activity");
        manager.createNotificationChannel(new NotificationChannel("autonomy-results","Background results",NotificationManager.IMPORTANCE_DEFAULT));
        manager.createNotificationChannel(new NotificationChannel("autonomy-results-quiet","Background results during quiet hours",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(context,0,new Intent(context,MainActivity.class).putExtra("openAutonomy",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        int hour=java.time.ZonedDateTime.now().getHour();boolean quiet=hour>=22||hour<8;
        Notification notification=new Notification.Builder(context,quiet?"autonomy-results-quiet":"autonomy-results").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(entry.getString("title")).setContentText(entry.getString("message"))
            .setStyle(new Notification.BigTextStyle().bigText(entry.getString("message"))).setContentIntent(open)
            .setAutoCancel(true).setOnlyAlertOnce(true).build();
        manager.notify("autonomy:"+entry.getString("id"),0,notification);
        return Json.obj("status","posted","quietHours",quiet);
    }
    Object call(String action,JSONObject args) throws Exception {
        return switch(action) {
            case "load" -> load();
            case "pause" -> {setEnabled(context,false);yield true;}
            case "save" -> {save(args.getJSONObject("state"),args.getLong("expectedRevision"));yield true;}
            case "events" -> events();
            case "ack" -> {ack(args.getJSONArray("ids"));yield true;}
            case "status" -> status();
            case "notify" -> notifyResult(args);
            default -> throw new IllegalArgumentException("Unknown autonomy method");
        };
    }
}
