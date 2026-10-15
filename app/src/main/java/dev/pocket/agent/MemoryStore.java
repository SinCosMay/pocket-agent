package dev.pocket.agent;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local, independent memory database: no changes to the operation ledger or runtime checkpoint. */
public final class MemoryStore extends SQLiteOpenHelper {
    private static final int MAX_ROWS = 5000;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]+");
    public MemoryStore(Context context) { this(context, "personal-memory.db"); }
    MemoryStore(Context context, String databaseName) { super(context, databaseName, null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, owner TEXT NOT NULL, category TEXT NOT NULL, memo_key TEXT NOT NULL, content TEXT NOT NULL, confidence REAL NOT NULL, confirmed INTEGER NOT NULL, source TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, expires_at INTEGER NOT NULL DEFAULT 0, UNIQUE(scope,owner,memo_key))");
        db.execSQL("CREATE INDEX memories_access ON memories(scope,owner,updated_at DESC)");
        db.execSQL("CREATE VIRTUAL TABLE memory_fts USING fts4(content, tokenize=unicode61)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("Unsupported memory migration"); }

    private static void check(boolean ok, String reason) {
        if (!ok) throw new IllegalArgumentException(reason);
    }
    private static String string(JSONObject args,String field,int max) {
        String v=args.optString(field,"").trim();
        check(!v.isEmpty() && v.length()<=max,"Invalid "+field);
        return v;
    }
    private static String owner(String thread) {
        check(thread!=null && thread.length()>0 && thread.length()<=160,"Invalid thread owner");
        return thread;
    }
    private static boolean isMain(String thread) { return "main".equals(thread); }
    private static String visibleWhere() { return "(scope='global' OR (scope='thread' AND owner=?)) AND (expires_at=0 OR expires_at>?)"; }
    private static String[] visibleArgs(String thread) { return new String[]{owner(thread),String.valueOf(System.currentTimeMillis())}; }

    private static JSONObject row(Cursor c) throws Exception {
        return new JSONObject().put("id",c.getLong(0)).put("scope",c.getString(1)).put("owner",c.getString(2))
            .put("category",c.getString(3)).put("key",c.getString(4)).put("content",c.getString(5))
            .put("confidence",c.getDouble(6)).put("confirmed",c.getInt(7)!=0)
            .put("source",c.getString(8)).put("updatedAt",c.getLong(9)).put("expiresAt",c.getLong(10));
    }
    private static final String COLUMNS="m.id,m.scope,m.owner,m.category,m.memo_key,m.content,m.confidence,m.confirmed,m.source,m.updated_at,m.expires_at";
    private JSONArray rows(SQLiteDatabase db,String where,String[] values,String order,int limit) throws Exception {
        JSONArray out=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT "+COLUMNS+" FROM memories m WHERE "+where+" ORDER BY "+order+" LIMIT "+limit,values)) {
            while(c.moveToNext())out.put(row(c));
        }
        return out;
    }
    private static String match(String query) {
        Matcher tokens=TOKEN.matcher(query.toLowerCase(Locale.ROOT));
        ArrayList<String> terms=new ArrayList<>();
        while(tokens.find() && terms.size()<8) {
            String word=tokens.group();
            if(word.length()>1 && word.length()<=40 && !terms.contains(word))terms.add(word);
        }
        if(terms.isEmpty())return "";
        ArrayList<String> quoted=new ArrayList<>();
        for(String t:terms)quoted.add("\""+t+"\"");
        return String.join(" OR ",quoted);
    }
    public synchronized JSONObject run(JSONObject args,String thread) throws Exception {
        owner(thread);
        String action=string(args,"action",32);
        SQLiteDatabase db=getWritableDatabase();
        switch(action) {
            case "save": return save(db,args,thread);
            case "forget": return forget(db,args,thread);
            case "search": {
                String query=string(args,"query",300);
                String expression=match(query);
                JSONArray memories=new JSONArray();
                if(!expression.isEmpty()) {
                    try(Cursor c=db.rawQuery("SELECT "+COLUMNS+" FROM memories m JOIN memory_fts f ON f.docid=m.id WHERE "+visibleWhere()+" AND f.content MATCH ? ORDER BY m.confirmed DESC,m.updated_at DESC LIMIT 12",
                        new String[]{thread,String.valueOf(System.currentTimeMillis()),expression})) {
                        while(c.moveToNext())memories.put(row(c));
                    }
                }
                return new JSONObject().put("memories",memories);
            }
            case "profile": return new JSONObject().put("memories",rows(db,visibleWhere()+" AND category IN ('preference','procedure','person','project','goal')",visibleArgs(thread),"m.confirmed DESC,m.updated_at DESC",16));
            default: throw new IllegalArgumentException("Unknown memory action");
        }
    }
    private JSONObject save(SQLiteDatabase db,JSONObject args,String thread) throws Exception {
        String scope=args.optString("scope","thread");
        check(scope.equals("global")||scope.equals("thread"),"Invalid memory scope");
        check(!scope.equals("global")||isMain(thread),"Only main may write shared memory");
        String owner=scope.equals("global")?"main":thread;
        String category=string(args,"category",24);
        check(category.equals("preference")||category.equals("procedure")||category.equals("person")||category.equals("project")||category.equals("goal")||category.equals("episodic"),"Invalid category");
        String key=string(args,"key",100).toLowerCase(Locale.ROOT);
        String content=string(args,"content",800);
        String source=args.optString("source","agent");
        check(source.equals("agent")||source.equals("observer"),"Invalid memory source");
        boolean confirmed=source.equals("agent")&&args.optBoolean("confirmed",false);
        double confidence=args.optDouble("confidence",confirmed?1.0:0.7);
        check(Double.isFinite(confidence)&&confidence>=0&&confidence<=1,"Invalid confidence");
        long expiresAt=args.optLong("expiresAt",0),now=System.currentTimeMillis();
        check(expiresAt==0||expiresAt>now,"Invalid expiry");
        db.beginTransaction();
        long id;
        try {
            long previous=-1; boolean previousConfirmed=false;
            try(Cursor c=db.rawQuery("SELECT id,confirmed FROM memories WHERE scope=? AND owner=? AND memo_key=?",new String[]{scope,owner,key})) {
                if(c.moveToFirst()){previous=c.getLong(0);previousConfirmed=c.getInt(1)!=0;}
            }
            if(source.equals("observer")&&previousConfirmed) {
                db.setTransactionSuccessful();
                return new JSONObject().put("saved",false).put("reason","Confirmed memory cannot be overwritten by automatic observation");
            }
            if(previous==-1) {
                try(Cursor count=db.rawQuery("SELECT COUNT(*) FROM memories",null)){
                    count.moveToFirst();check(count.getInt(0)<MAX_ROWS,"Memory capacity reached");
                }
            }
            ContentValues values=new ContentValues();
            values.put("scope",scope);values.put("owner",owner);values.put("category",category);
            values.put("memo_key",key);values.put("content",content);values.put("confidence",confidence);
            values.put("confirmed",confirmed?1:0);values.put("source",source);values.put("updated_at",now);values.put("expires_at",expiresAt);
            if(previous<0) {
                values.put("created_at",now);
                id=db.insertOrThrow("memories",null,values);
            } else {
                id=previous;
                db.update("memories",values,"id=?",new String[]{String.valueOf(id)});
                db.delete("memory_fts","docid=?",new String[]{String.valueOf(id)});
            }
            ContentValues indexed=new ContentValues();
            indexed.put("docid",id);indexed.put("content",content);
            db.insertOrThrow("memory_fts",null,indexed);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        return new JSONObject().put("saved",true).put("id",id);
    }
    private JSONObject forget(SQLiteDatabase db,JSONObject args,String thread) throws Exception {
        long id=args.optLong("id",-1);
        check(id>0,"Invalid memory id");
        db.beginTransaction();
        int count;
        try {
            count=db.delete("memories","id=? AND (scope='global' AND ?='main' OR scope='thread' AND owner=?)",
                new String[]{String.valueOf(id),thread,thread});
            if(count>0)db.delete("memory_fts","docid=?",new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        return new JSONObject().put("deleted",count>0);
    }
}
