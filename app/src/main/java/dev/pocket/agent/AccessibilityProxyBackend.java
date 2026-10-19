package dev.pocket.agent;

import android.content.Context;
import android.os.Bundle;
import dev.pocket.control.ControlBackend;
import dev.pocket.control.ControlException;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

final class AccessibilityProxyBackend implements ControlBackend {
    private final Context context;
    private final int displayId;
    AccessibilityProxyBackend(Context context,int displayId) {this.context=context.getApplicationContext();this.displayId=displayId;}

    static boolean connected(Context context) {
        try {
            Bundle out=context.getContentResolver().call(AccessibilityBridgeProvider.uri(context),"status",null,new Bundle());
            return out!=null&&out.getBoolean("connected");
        } catch(Throwable ignored) {return false;}
    }
    private Bundle call(String method,Bundle extras) throws ControlException {
        extras.putInt("displayId",displayId);
        final Bundle out;
        try {out=context.getContentResolver().call(AccessibilityBridgeProvider.uri(context),method,null,extras);}
        catch(Throwable failure) {throw new ControlException(ControlException.Code.NOT_READY,"Accessibility bridge process unavailable; wait for recovery");}
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
    void validateAction(ControlBackend.Action action) throws ControlException {call("validate",action(action));}
    void noteInteraction(long eventTime) throws ControlException {Bundle args=new Bundle();args.putLong("eventTime",eventTime);call("noteInteraction",args);}
    void release() {try {call("release",new Bundle());} catch(ControlException ignored) {}}

    @Override public ControlBackend.Observation observe(boolean includeImage,int maxImageDimension,int maxNodes) throws ControlException {
        Bundle args=new Bundle();args.putBoolean("includeImage",includeImage);args.putInt("maxImageDimension",maxImageDimension);args.putInt("maxNodes",maxNodes);
        Bundle out=call("observe",args);
        ArrayList<Bundle> raw=out.getParcelableArrayList("nodes");
        List<ControlBackend.Node> nodes=new ArrayList<>();
        if(raw!=null)for(Bundle node:raw)nodes.add(new ControlBackend.Node(node.getInt("index"),node.getString("resourceId",""),node.getString("text",""),node.getString("description",""),node.getString("role",""),
            node.getInt("left"),node.getInt("top"),node.getInt("right"),node.getInt("bottom"),node.getBoolean("enabled"),node.getBoolean("focused"),node.getBoolean("editable"),node.getBoolean("clickable"),node.getBoolean("longClickable"),node.getBoolean("scrollable")));
        byte[] image=null;String path=out.getString("imageFile");
        if(path!=null) {
            File file=new File(path);
            try {image=Files.readAllBytes(file.toPath());}
            catch(Exception failure) {throw new ControlException(ControlException.Code.RESULT_UNKNOWN,"Screenshot transfer failed; observe again");}
            finally {try {file.delete();}catch(RuntimeException ignored){}}
        }
        return new ControlBackend.Observation(out.getString("snapshotId"),out.getLong("capturedAtMs"),out.getString("packageName",""),out.getInt("windowId"),
            out.getInt("coordinateWidth"),out.getInt("coordinateHeight"),out.getBoolean("interactive"),out.getBoolean("locked"),nodes,out.getBoolean("nodesTruncated"),
            image,out.getString("imageMime"),out.getInt("imageWidth"),out.getInt("imageHeight"));
    }
    @Override public ControlBackend.Result execute(ControlBackend.Action action) throws ControlException {
        Bundle out=call("execute",action(action));return new ControlBackend.Result(out.getString("status"),out.getString("message"));
    }
}
