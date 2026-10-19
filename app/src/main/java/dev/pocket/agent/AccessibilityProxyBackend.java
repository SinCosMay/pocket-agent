package dev.pocket.agent;

import android.content.Context;
import android.os.Bundle;
import dev.pocket.control.ControlBackend;
import dev.pocket.control.ControlException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

final class AccessibilityProxyBackend implements ControlBackend {
    private static final ThreadPoolExecutor IPC=new ThreadPoolExecutor(0,2,20,TimeUnit.SECONDS,
        new SynchronousQueue<>(),r->{Thread t=new Thread(r,"PocketAccessibilityIpc");t.setDaemon(true);return t;},
        new ThreadPoolExecutor.AbortPolicy());
    private static final long IPC_TIMEOUT_MS=3500;
    private final Context context;
    private final int displayId;
    AccessibilityProxyBackend(Context context,int displayId) {this.context=context.getApplicationContext();this.displayId=displayId;}

    private static Bundle providerCall(Context context,String method,Bundle extras,boolean mutation) throws ControlException {
        final Future<Bundle> pending;
        try {
            pending=IPC.submit(()->context.getContentResolver().call(AccessibilityBridgeProvider.uri(context),method,null,extras));
        } catch(java.util.concurrent.RejectedExecutionException saturated) {
            throw new ControlException(ControlException.Code.NOT_READY,"Accessibility bridge is busy; retry after recovery");
        }
        try {return pending.get(IPC_TIMEOUT_MS,TimeUnit.MILLISECONDS);}
        catch(TimeoutException timeout) {
            pending.cancel(true);
            throw new ControlException(mutation?ControlException.Code.RESULT_UNKNOWN:ControlException.Code.NOT_READY,
                mutation?"Accessibility action timed out; observe current state before retrying":"Accessibility bridge timed out; wait for recovery");
        } catch(InterruptedException interrupted) {
            pending.cancel(true);Thread.currentThread().interrupt();
            throw new ControlException(mutation?ControlException.Code.RESULT_UNKNOWN:ControlException.Code.NOT_READY,
                "Accessibility request was interrupted");
        } catch(ExecutionException failure) {
            throw new ControlException(mutation?ControlException.Code.RESULT_UNKNOWN:ControlException.Code.NOT_READY,
                "Accessibility bridge process unavailable; wait for recovery");
        }
    }
    static boolean connected(Context context) {
        try {
            Bundle out=providerCall(context.getApplicationContext(),"status",new Bundle(),false);
            return out!=null&&out.getBoolean("connected");
        } catch(ControlException ignored) {return false;}
    }
    private Bundle call(String method,Bundle extras) throws ControlException {
        extras.putInt("displayId",displayId);
        final Bundle out=providerCall(context,method,extras,"execute".equals(method));
        if(out==null)throw new ControlException(ControlException.Code.NOT_READY,"Accessibility bridge returned no result");
        String code=out.getString("errorCode");
        if(code!=null) {
            ControlException.Code parsed;
            try {parsed=ControlException.Code.valueOf(code);} catch(IllegalArgumentException invalid) {parsed=ControlException.Code.RESULT_UNKNOWN;}
            throw new ControlException(parsed,out.getString("errorMessage","Accessibility operation failed"));
        }
        return out;
    }
    private static void optional(Bundle out,String key,Integer value) {if(value!=null)out.putInt(key,value);}
    private static void optional(Bundle out,String key,String value) {if(value!=null)out.putString(key,value);}
    private Bundle action(ControlBackend.Action action) {
        Bundle out=new Bundle();out.putString("kind",action.kind().name());out.putString("snapshotId",action.snapshotId());
        optional(out,"x",action.x());optional(out,"y",action.y());optional(out,"endX",action.endX());optional(out,"endY",action.endY());out.putInt("durationMs",action.durationMs());
        optional(out,"text",action.text());out.putBoolean("replace",action.replace());optional(out,"key",action.key());optional(out,"direction",action.direction());
        if(action.selector()!=null) {out.putBoolean("hasSelector",true);optional(out,"resourceId",action.selector().resourceId());optional(out,"selectorText",action.selector().text());optional(out,"description",action.selector().description());}
        return out;
    }
    private JSONObject payload(Bundle out) throws ControlException {
        String path=out.getString("payloadFile");
        if(path==null)throw new ControlException(ControlException.Code.RESULT_UNKNOWN,"Accessibility result transfer was incomplete; observe again");
        File file=new File(path);
        try {return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
        catch(Exception failure) {throw new ControlException(ControlException.Code.RESULT_UNKNOWN,"Accessibility result transfer failed; observe again");}
        finally {try {file.delete();}catch(RuntimeException ignored){}}
    }
    private static byte[] image(JSONObject payload) throws ControlException {
        String path=payload.optString("imageFile",null);
        if(path==null)return null;
        File file=new File(path);
        try {return Files.readAllBytes(file.toPath());}
        catch(Exception failure) {throw new ControlException(ControlException.Code.RESULT_UNKNOWN,"Screenshot transfer failed; observe again");}
        finally {try {file.delete();}catch(RuntimeException ignored){}}
    }
    void validateAction(ControlBackend.Action action) throws ControlException {call("validate",action(action));}
    void noteInteraction(long eventTime) throws ControlException {Bundle args=new Bundle();args.putLong("eventTime",eventTime);call("noteInteraction",args);}
    void release() {try {call("release",new Bundle());} catch(ControlException ignored) {}}

    @Override public ControlBackend.Observation observe(boolean includeImage,int maxImageDimension,int maxNodes) throws ControlException {
        Bundle args=new Bundle();args.putBoolean("includeImage",includeImage);args.putInt("maxImageDimension",maxImageDimension);args.putInt("maxNodes",maxNodes);
        JSONObject data=payload(call("observe",args));JSONArray raw=data.optJSONArray("nodes");List<ControlBackend.Node> nodes=new ArrayList<>();
        if(raw!=null)for(int i=0;i<raw.length();i++) {
            JSONObject node=raw.optJSONObject(i);if(node==null)continue;
            nodes.add(new ControlBackend.Node(node.optInt("index"),node.optString("resourceId"),node.optString("text"),node.optString("description"),node.optString("role"),
                node.optInt("left"),node.optInt("top"),node.optInt("right"),node.optInt("bottom"),node.optBoolean("enabled"),node.optBoolean("focused"),node.optBoolean("editable"),
                node.optBoolean("clickable"),node.optBoolean("longClickable"),node.optBoolean("scrollable")));
        }
        return new ControlBackend.Observation(data.optString("snapshotId"),data.optLong("capturedAtMs"),data.optString("packageName"),data.optInt("windowId"),
            data.optInt("coordinateWidth"),data.optInt("coordinateHeight"),data.optBoolean("interactive"),data.optBoolean("locked"),nodes,data.optBoolean("nodesTruncated"),
            image(data),data.optString("imageMime",null),data.optInt("imageWidth"),data.optInt("imageHeight"));
    }
    @Override public ControlBackend.Result execute(ControlBackend.Action action) throws ControlException {
        Bundle out=call("execute",action(action));return new ControlBackend.Result(out.getString("status"),out.getString("message"));
    }
}
