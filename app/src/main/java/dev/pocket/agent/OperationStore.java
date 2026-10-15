package dev.pocket.agent;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persist before dispatch. Never replay unfinished operations after process death. */
final class OperationStore extends SQLiteOpenHelper {
    private static final int LIMIT=20000;
    OperationStore(Context context) {super(context,"operations.db",null,3);}
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE operations (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, result TEXT, created INTEGER NOT NULL, epoch INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE receipts (operation TEXT NOT NULL, stage TEXT NOT NULL, part INTEGER NOT NULL, code INTEGER NOT NULL, delivery_status INTEGER, PRIMARY KEY(operation,stage,part))");
        createMeta(db);
    }
    private void createMeta(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX operations_epoch ON operations(epoch)");
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("INSERT INTO metadata VALUES ('epoch',?)",new Object[]{System.currentTimeMillis()/86400000L});
        db.execSQL("INSERT INTO metadata VALUES ('installation',?)",new Object[]{java.util.UUID.randomUUID().toString().replace("-","").substring(0,16)});
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<1 || newVersion!=3) throw new IllegalStateException("Unsupported ledger upgrade");
        if(oldVersion<2) {db.execSQL("ALTER TABLE operations ADD COLUMN epoch INTEGER NOT NULL DEFAULT 0");createMeta(db);}
        if(oldVersion<3) db.execSQL("ALTER TABLE receipts ADD COLUMN delivery_status INTEGER");
    }
    private String meta(String name) {try(Cursor c=getReadableDatabase().query("metadata",new String[]{"value"},"name=?",new String[]{name},null,null,null)) {if(!c.moveToFirst()) throw new IllegalStateException("Missing metadata");return c.getString(0);}}
    private long epoch() {
        long value=Math.max(Long.parseLong(meta("epoch")),System.currentTimeMillis()/86400000L);
        ContentValues update=new ContentValues();update.put("value",String.valueOf(value));getWritableDatabase().update("metadata",update,"name='epoch' AND CAST(value AS INTEGER)<?",new String[]{String.valueOf(value)});return value;
    }
    synchronized String prefix() {return epoch()+"-"+meta("installation");}
    interface Work {JSONObject run() throws Exception;}
    private static String canonical(Object value) throws Exception {
        if(value instanceof JSONObject o) {
            TreeMap<String,Object> sorted=new TreeMap<>();Iterator<String> keys=o.keys();
            while(keys.hasNext()) {String k=keys.next();sorted.put(k,o.get(k));}
            StringBuilder out=new StringBuilder("{");boolean first=true;
            for(java.util.Map.Entry<String,Object> entry:sorted.entrySet()) {if(!first) out.append(',');first=false;out.append(JSONObject.quote(entry.getKey())).append(':').append(canonical(entry.getValue()));}return out.append('}').toString();
        }
        if(value instanceof JSONArray a) {StringBuilder out=new StringBuilder("[");for(int i=0;i<a.length();i++) {if(i>0) out.append(',');out.append(canonical(a.get(i)));}return out.append(']').toString();}
        return value instanceof String s?JSONObject.quote(s):String.valueOf(value);
    }
    synchronized JSONObject execute(String id,String tool,JSONObject args,Work work) throws Exception {
        if(id==null || !id.matches("[A-Za-z0-9_-]{8,80}")) return Json.failure("INVALID_ARGUMENT","operation_id must be a unique 8..80 character identifier");
        byte[] digest=MessageDigest.getInstance("SHA-256").digest((tool+":"+canonical(args)).getBytes(StandardCharsets.UTF_8));
        StringBuilder hash=new StringBuilder();for(byte b:digest) hash.append(String.format("%02x",b&255));
        SQLiteDatabase db=getWritableDatabase();
        try(Cursor c=db.query("operations",new String[]{"fingerprint","result"},"id=?",new String[]{id},null,null,null)) {
            if(c.moveToFirst()) {
                if(!hash.toString().equals(c.getString(0))) return Json.failure("OPERATION_CONFLICT","This operation_id belongs to different arguments");
                return c.isNull(1)?Json.failure("RESULT_UNKNOWN","Previously started; inspect current state before repeating"):new JSONObject(c.getString(1));
            }
        }
        long current=epoch(),issued;
        java.util.regex.Matcher parsed=java.util.regex.Pattern.compile("([0-9]{1,12})-([0-9a-f]{16})_[A-Za-z0-9_-]{8,48}").matcher(id);
        if(!parsed.matches() || !parsed.group(2).equals(meta("installation"))) return Json.failure("INVALID_ARGUMENT","Use phone_capabilities.operation_prefix + '_' + a unique nonce for operation_id");
        issued=Long.parseLong(parsed.group(1));
        if(issued<current-1 || issued>current) return Json.failure("OPERATION_EXPIRED","Operation ID window expired; inspect the actual outcome, never blindly repeat an old action");
        // Expired IDs cannot be dispatched again, so old records can be pruned safely.
        db.beginTransaction();
        try {db.execSQL("DELETE FROM receipts WHERE operation IN (SELECT id FROM operations WHERE epoch<?)",new Object[]{current-1});db.delete("operations","epoch<?",new String[]{String.valueOf(current-1)});db.setTransactionSuccessful();}
        finally {db.endTransaction();}
        try(Cursor c=db.rawQuery("SELECT count(*) FROM operations",null)) {c.moveToFirst();if(c.getInt(0)>=LIMIT) return Json.failure("LEDGER_FULL","Retry history is full; wait for old operation windows to expire. No action dispatched.");}
        ContentValues values=new ContentValues();values.put("id",id);values.put("fingerprint",hash.toString());values.put("created",System.currentTimeMillis());values.put("epoch",issued);
        db.insertOrThrow("operations",null,values);
        JSONObject result;
        try {result=work.run();}
        catch(dev.pocket.control.ControlException e) {result=Json.failure(e.code.name(),e.getMessage());}
        catch(SecurityException e) {result=Json.failure("PERMISSION_REQUIRED","Android denied the operation; inspect before repeating");}
        catch(Exception e) {result=Json.failure("RESULT_UNKNOWN","Dispatch outcome unavailable; inspect before repeating");}
        ContentValues done=new ContentValues();done.put("result",result.toString());
        if(db.update("operations",done,"id=?",new String[]{id})!=1) throw new IllegalStateException("Ledger result write failed");
        return result;
    }
    synchronized JSONObject inspect(String id) throws Exception {
        JSONObject result=null;
        try(Cursor c=getReadableDatabase().query("operations",new String[]{"result","created"},"id=?",new String[]{id},null,null,null)) {
            if(!c.moveToFirst()) return Json.obj("operation_id",id,"status","not_found","retry_safe",false,"message","Missing history is not proof that an old action did not run. Inspect the actual app/provider state; expired IDs are never dispatched again.");
            result=Json.obj("operation_id",id,"status",c.isNull(0)?"result_unknown":"recorded","created_at_ms",c.getLong(1),"result",c.isNull(0)?null:new JSONObject(c.getString(0)));
        }
        JSONArray receipts=new JSONArray();
        try(Cursor c=getReadableDatabase().query("receipts",new String[]{"stage","part","code","delivery_status"},"operation=?",new String[]{id},null,null,"stage,part")) {
            while(c.moveToNext()) {
                boolean sent="sent".equals(c.getString(0));boolean ok=c.getInt(2)==android.app.Activity.RESULT_OK;
                boolean confirmed=!sent && ok && !c.isNull(3) && c.getInt(3)==0;
                receipts.put(Json.obj("stage",c.getString(0),"part",c.getInt(1),"android_result_code",c.getInt(2),"delivery_status",c.isNull(3)?null:c.getInt(3),"success",sent?ok:(c.isNull(3)?null:confirmed),"delivery_confirmed",confirmed));
            }
        }
        result.put("receipts",receipts);return result;
    }
    synchronized void receipt(String op,String stage,int part,int code,Integer deliveryStatus) {
        if(op==null || !("sent".equals(stage)||"delivered".equals(stage)) || part<0) return;
        try(Cursor c=getReadableDatabase().query("operations",new String[]{"id"},"id=?",new String[]{op},null,null,null)) {if(!c.moveToFirst()) return;}
        ContentValues v=new ContentValues();v.put("operation",op);v.put("stage",stage);v.put("part",part);v.put("code",code);
        if(deliveryStatus==null) v.putNull("delivery_status");else v.put("delivery_status",deliveryStatus);
        getWritableDatabase().insertWithOnConflict("receipts",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
}
