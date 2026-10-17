package dev.pocket.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import com.ngrok.Forwarder;
import com.ngrok.Session;
import java.net.URL;
import org.json.JSONObject;

public final class DevBridgeService extends Service {
    static final String START_LOCAL="dev.pocket.agent.DEV_LOCAL";
    static final String START_PUBLIC="dev.pocket.agent.DEV_PUBLIC";
    static final String STOP="dev.pocket.agent.DEV_STOP";
    private static volatile String stage="stopped", publicUrl="", diagnostic="";
    private volatile DevMcpServer server;
    private volatile Session tunnel;
    private volatile Forwarder.Endpoint endpoint;
    private volatile boolean stopped;
    private Thread starter;

    static JSONObject status() {
        try { return new JSONObject().put("stage",stage).put("publicUrl",publicUrl).put("diagnostic",diagnostic).put("localUrl",stage.equals("stopped")||stage.equals("failed")?"":"http://127.0.0.1:8767/mcp"); }
        catch(Exception error) { return new JSONObject(); }
    }

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("dev-bridge","Development MCP access",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(String description) {
        PendingIntent open=PendingIntent.getActivity(this,51,new Intent(this,DevToolsActivity.class),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,52,new Intent(this,DevBridgeService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"dev-bridge").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Pocket Agent development bridge").setContentText(description).setContentIntent(open)
            .setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop bridge",stop).build()).build();
    }
    private void notifyState(String message) {
        getSystemService(NotificationManager.class).notify(52,notification(message));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?"":intent.getAction();
        if(STOP.equals(action)) {stopSelf();return START_NOT_STICKY;}
        if(!START_LOCAL.equals(action)&&!START_PUBLIC.equals(action))return START_NOT_STICKY;
        if(starter!=null) {
            diagnostic="Bridge already active. Stop the existing bridge before switching between USB and wireless.";
            notifyState("Bridge already active; stop before switching modes");
            return START_NOT_STICKY;
        }
        if(android.os.Build.VERSION.SDK_INT>=34)startForeground(52,notification("Starting authenticated local server"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(52,notification("Starting authenticated local server"));
        stopped=false;stage="starting";diagnostic="";publicUrl="";
        if(!AgentService.isRunning())
            startForegroundService(new Intent(this,AgentService.class).setAction(AgentService.ACTION_START));
        boolean publicRequested=START_PUBLIC.equals(action);
        starter=new Thread(()->start(publicRequested),"pocket-dev-bridge");
        starter.start();return START_NOT_STICKY;
    }
    private void start(boolean remote) {
        try {
            DevSecrets secrets=new DevSecrets(this);
            String bearer=secrets.bearer();
            String authtoken=remote?secrets.ngrok():"";
            if(remote&&authtoken.trim().isEmpty())throw new IllegalArgumentException("ngrok token missing");
            if(remote&&!new java.io.File(getApplicationInfo().nativeLibraryDir,"libngrok_java.so").exists())
                throw new IllegalStateException("Wireless tunnel is supported only on ARM64 in this build");
            DevMcpServer ready=new DevMcpServer(this,bearer);
            ready.start(8000,false);
            synchronized(this){if(stopped){ready.stop();return;}server=ready;stage="local";}
            notifyState("Local USB bridge ready");
            if(!remote)return;
            stage="connecting";
            System.loadLibrary("ngrok_java");
            Session session=Session.withAuthtoken(authtoken).addClientInfo("pocket-agent-dev",BuildConfig.VERSION_NAME).connect();
            synchronized(this){if(stopped){session.close();return;}tunnel=session;}
            Forwarder.Endpoint forward=session.httpEndpoint().scheme(com.ngrok.Http.Scheme.HTTPS)
                .forward(new URL("http://127.0.0.1:8767"));
            synchronized(this) {
                if(stopped){forward.close();return;}
                endpoint=forward;
                URL address=new URL(forward.getUrl());
                if(!"https".equals(address.getProtocol()))throw new IllegalStateException("Tunnel is not HTTPS");
                publicUrl=forward.getUrl().replaceAll("/$","")+"/mcp";
                stage="online";
            }
            notifyState("ngrok bridge connected — privileged access");
        } catch(Throwable error) {
            if(stopped)return;
            diagnostic="Bridge setup failed at "+stage+". Check token, ngrok account permissions, connectivity and native ABI.";
            stage="failed";publicUrl="";
            new android.os.Handler(getMainLooper()).post(this::stopSelf);
        }
    }
    @Override public void onDestroy() {
        synchronized(this) {
            stopped=true;stage="stopped";publicUrl="";
            if(server!=null){server.stop();server=null;}
        }
        Thread cleaner=new Thread(()->{
            try {if(endpoint!=null)endpoint.close();}catch(Exception ignored){}
            try {if(tunnel!=null)tunnel.close();}catch(Exception ignored){}
        },"pocket-dev-bridge-cleanup");
        cleaner.start();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) {return null;}
}
