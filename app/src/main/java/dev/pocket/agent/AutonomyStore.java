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
    AutonomyStore(Context context) {this(context,"agent-autonomy.db");}
    AutonomyStore(Context context,String databaseName) {super(context,databaseName,null,1);this.context=context.getApplicationContext();}
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE state (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE incoming (id TEXT PRIMARY KEY, json TEXT NOT NULL, at INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to) {throw new IllegalStateException("Unsupported autonomy migration");}
    JSONObject load() throws Exception {
        synchronized(LOCK) {
            try(Cursor c=getReadableDatabase().rawQuery("SELECT json FROM state WHERE id=1",null)) {
                if(c.moveToFirst())return new JSONObject(c.getString(0));
            }
            JSONObject root=Json.obj("version",1,"revision",0);
            for(String key:new String[]{"schedules","wakeups","tasks","watches","skills","decisions","notifications","runs","seenEvents"})root.put(key,new JSONArray());
            return root;
        }
    }
    void save(JSONObject state,long expectedRevision) throws Exception {
        String json=state.toString();if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1_800_000)throw new IllegalArgumentException("Autonomy store limit reached");
        synchronized(LOCK) {
            SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
            try {
                if(load().optLong("revision")!=expectedRevision||state.getLong("revision")!=expectedRevision+1)throw new IllegalStateException("Stale autonomy writer");
                ContentValues values=new ContentValues();values.put("id",1);values.put("json",json);
                if(db.insertWithOnConflict("state",null,values,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("Could not persist autonomy state");
                db.setTransactionSuccessful();
            } finally {db.endTransaction();}
        }
        AutonomyScheduler.arm(context);
    }
    static boolean enabled(Context context) {return context.getSharedPreferences("autonomy-policy",0).getBoolean("enabled",true);}
    static void setEnabled(Context context,boolean enabled) {
        if(!context.getSharedPreferences("autonomy-policy",0).edit().putBoolean("enabled",enabled).commit())throw new IllegalStateException("Could not save automation setting");
        AutonomyScheduler.arm(context);
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
        AutonomyScheduler.arm(context);
    }
    void incoming(JSONObject event) throws Exception {
        synchronized(LOCK) {
            ContentValues values=new ContentValues();values.put("id",event.getString("id"));values.put("json",event.toString());values.put("at",System.currentTimeMillis());
            SQLiteDatabase db=getWritableDatabase();
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM incoming",null)){if(c.moveToFirst()&&c.getInt(0)>=64)throw new IllegalStateException("Autonomy event inbox full");}
            db.insertWithOnConflict("incoming",null,values,SQLiteDatabase.CONFLICT_IGNORE);
        }
        AutonomyScheduler.arm(context);
    }
    long nextDue() throws Exception {
        if(!enabled(context))return Long.MAX_VALUE;
        synchronized(LOCK) {
            if(events().length()>0)return System.currentTimeMillis();
            JSONObject root=load();long next=Long.MAX_VALUE;
            for(String key:new String[]{"schedules","wakeups"}) {
                JSONArray items=root.getJSONArray(key);
                for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);if(item.optBoolean("enabled")&&!item.isNull("nextAt"))next=Math.min(next,item.optLong("nextAt",Long.MAX_VALUE));}
            }
            JSONArray runs=root.getJSONArray("runs");boolean active=false,queued=false;
            for(int i=0;i<runs.length();i++) {
                JSONObject run=runs.getJSONObject(i);String status=run.optString("status");
                if(status.equals("queued")) {
                    JSONArray tasks=root.getJSONArray("tasks");
                    for(int t=0;t<tasks.length();t++){JSONObject task=tasks.getJSONObject(t);if(task.optString("id").equals(run.optString("taskId"))&&"active".equals(task.optString("status")))queued=true;}
                }
                if(status.equals("running")||status.equals("dispatching")){active=true;next=Math.min(next,run.optLong("startedAt",run.optLong("createdAt"))+9*60_000L);}
            }
            if(queued&&!active)next=Math.min(next,System.currentTimeMillis()+1000);
            return next;
        }
    }
    JSONObject status() {
        ConnectivityManager connectivity=context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities network=connectivity==null?null:connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        PowerManager power=context.getSystemService(PowerManager.class);
        NotificationManager notifications=context.getSystemService(NotificationManager.class);
        return Json.obj("enabled",enabled(context),"timing","inexact_android_workmanager","connected",network!=null&&network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            "notificationsEnabled",notifications!=null&&notifications.areNotificationsEnabled(),"notificationAccess",PhoneNotifications.connected!=null,
            "batteryRestricted",power!=null&&!power.isIgnoringBatteryOptimizations(context.getPackageName()),"maxRunDurationMs",8*60_000,"phoneMustBeOnline",true);
    }
    JSONObject notifyResult(JSONObject entry) throws Exception {
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null||!manager.areNotificationsEnabled()||android.os.Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return Json.obj("status","blocked","reason","Notification permission disabled; result remains in app activity");
        manager.createNotificationChannel(new NotificationChannel("autonomy-results","Background results",NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open=PendingIntent.getActivity(context,0,new Intent(context,MainActivity.class).putExtra("openAutonomy",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        int hour=java.time.ZonedDateTime.now().getHour();boolean quiet=hour>=22||hour<8;
        Notification notification=new Notification.Builder(context,"autonomy-results").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(entry.getString("title")).setContentText(entry.getString("message"))
            .setStyle(new Notification.BigTextStyle().bigText(entry.getString("message"))).setContentIntent(open)
            .setAutoCancel(true).setOnlyAlertOnce(true).setSilent(quiet).build();
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
