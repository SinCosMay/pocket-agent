package dev.pocket.agent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import dev.pocket.android.AndroidScreenBackend;
import dev.pocket.control.ControlBackend;
import dev.pocket.control.ControlException;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

public final class AccessibilityBridgeProvider extends ContentProvider {
    static String authority(android.content.Context context) {return context.getPackageName()+".accessibility-bridge";}
    static Uri uri(android.content.Context context) {return Uri.parse("content://"+authority(context));}
    @Override public boolean onCreate() {return true;}

    private static Bundle error(ControlException error) {
        Bundle out=new Bundle();out.putString("errorCode",error.code.name());out.putString("errorMessage",error.getMessage());return out;
    }
    private static Bundle error(Throwable error) {
        Bundle out=new Bundle();out.putString("errorCode",ControlException.Code.RESULT_UNKNOWN.name());
        out.putString("errorMessage","Accessibility bridge unavailable; observe current state before retrying");return out;
    }
    private static String nullable(Bundle extras,String key) {return extras.containsKey(key)?extras.getString(key):null;}
    private static ControlBackend.Action action(Bundle extras) {
        ControlBackend.Selector selector=null;
        if(extras.getBoolean("hasSelector"))selector=new ControlBackend.Selector(nullable(extras,"resourceId"),nullable(extras,"selectorText"),nullable(extras,"description"));
        return new ControlBackend.Action(ControlBackend.Kind.valueOf(extras.getString("kind")),extras.getString("snapshotId"),
            extras.containsKey("x")?extras.getInt("x"):null,extras.containsKey("y")?extras.getInt("y"):null,
            extras.containsKey("endX")?extras.getInt("endX"):null,extras.containsKey("endY")?extras.getInt("endY"):null,
            extras.getInt("durationMs"),nullable(extras,"text"),extras.getBoolean("replace"),nullable(extras,"key"),nullable(extras,"direction"),selector);
    }
    private AndroidScreenBackend backend(AgentAccessibility service,int displayId) {
        if(displayId<0)throw new IllegalArgumentException("Display ID required");
        return displayId==android.view.Display.DEFAULT_DISPLAY?service.backend():service.backend(displayId);
    }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        try {
            AgentAccessibility service=AgentAccessibility.connected;
            if("status".equals(method)) {Bundle out=new Bundle();out.putBoolean("connected",service!=null);return out;}
            if("release".equals(method)) {
                if(service!=null&&extras!=null&&extras.getInt("displayId",-1)>0)service.release(extras.getInt("displayId"));
                return new Bundle();
            }
            if(service==null)throw new ControlException(ControlException.Code.PERMISSION_REQUIRED,"Pocket Agent accessibility is enabled but not currently bound");
            if(extras==null)throw new ControlException(ControlException.Code.INVALID_ARGUMENT,"Missing accessibility bridge arguments");
            AndroidScreenBackend backend=backend(service,extras.getInt("displayId",-1));
            if("observe".equals(method)) {
                ControlBackend.Observation observation=backend.observe(extras.getBoolean("includeImage"),extras.getInt("maxImageDimension"),extras.getInt("maxNodes"));
                Bundle out=new Bundle();
                out.putString("snapshotId",observation.snapshotId());out.putLong("capturedAtMs",observation.capturedAtMs());out.putString("packageName",observation.packageName());
                out.putInt("windowId",observation.windowId());out.putInt("coordinateWidth",observation.coordinateWidth());out.putInt("coordinateHeight",observation.coordinateHeight());
                out.putBoolean("interactive",observation.interactive());out.putBoolean("locked",observation.locked());out.putBoolean("nodesTruncated",observation.nodesTruncated());
                out.putString("imageMime",observation.imageMime());out.putInt("imageWidth",observation.imageWidth());out.putInt("imageHeight",observation.imageHeight());
                ArrayList<Bundle> nodes=new ArrayList<>();
                for(ControlBackend.Node node:observation.nodes()) {
                    Bundle item=new Bundle();item.putInt("index",node.index());item.putString("resourceId",node.resourceId());item.putString("text",node.text());item.putString("description",node.description());item.putString("role",node.role());
                    item.putInt("left",node.left());item.putInt("top",node.top());item.putInt("right",node.right());item.putInt("bottom",node.bottom());
                    item.putBoolean("enabled",node.enabled());item.putBoolean("focused",node.focused());item.putBoolean("editable",node.editable());item.putBoolean("clickable",node.clickable());item.putBoolean("longClickable",node.longClickable());item.putBoolean("scrollable",node.scrollable());nodes.add(item);
                }
                out.putParcelableArrayList("nodes",nodes);
                byte[] image=observation.image();
                if(image!=null) {
                    File dir=new File(getContext().getCacheDir(),"accessibility-bridge");
                    if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Could not create accessibility bridge cache");
                    File file=File.createTempFile("screen-",".bin",dir);
                    try(FileOutputStream output=new FileOutputStream(file)){output.write(image);}
                    out.putString("imageFile",file.getAbsolutePath());
                }
                return out;
            }
            if("validate".equals(method)) {backend.validateAction(action(extras));return new Bundle();}
            if("execute".equals(method)) {
                ControlBackend.Result result=backend.execute(action(extras));Bundle out=new Bundle();out.putString("status",result.status());out.putString("message",result.message());return out;
            }
            throw new ControlException(ControlException.Code.INVALID_ARGUMENT,"Unknown accessibility bridge operation");
        } catch(ControlException error) {return error(error);}
        catch(Throwable error) {return error(error);}
    }

    @Override public String getType(Uri uri) {return null;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder) {throw new UnsupportedOperationException();}
    @Override public Uri insert(Uri uri,ContentValues values) {throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] selectionArgs) {throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs) {throw new UnsupportedOperationException();}
}
