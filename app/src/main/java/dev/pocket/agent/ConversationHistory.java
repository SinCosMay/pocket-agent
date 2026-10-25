package dev.pocket.agent;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * App-private, append-only transcripts, independent of the bounded live Pi checkpoint.
 * All writes occur inside StateStore's checkpoint transaction. No server or model is needed.
 */
final class ConversationHistory {
    private static final Pattern TOKEN=Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final String COMMON=" a an and are as at be been but by can could did do does for from had has have how i in is it its me my of on or our please said say she so tell than that the their them there these this to was were what when where which who why will with would you your ";
    private ConversationHistory() {}

    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversation_history (id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id TEXT NOT NULL, owner TEXT NOT NULL, role TEXT NOT NULL, timestamp INTEGER NOT NULL, event_key TEXT NOT NULL UNIQUE, text TEXT NOT NULL)");
        db.execSQL("CREATE INDEX conversation_history_thread ON conversation_history(thread_id,timestamp)");
        db.execSQL("CREATE INDEX conversation_history_recent ON conversation_history(timestamp DESC)");
        db.execSQL("CREATE VIRTUAL TABLE conversation_history_fts USING fts4(text, tokenize=unicode61)");
    }

    private static String messageText(JSONObject message) {
        Object raw=message.opt("content");
        if(raw instanceof String)return (String)raw;
        if(!(raw instanceof JSONArray))return "";
        JSONArray blocks=(JSONArray)raw;StringBuilder text=new StringBuilder();
        for(int i=0;i<blocks.length();i++) {
            JSONObject block=blocks.optJSONObject(i);
            if(block==null||!"text".equals(block.optString("type")))continue;
            if(text.length()>0)text.append('\n');
            text.append(block.optString("text"));
        }
        return text.toString();
    }

    private static String fingerprint(String thread,String role,long timestamp,String content) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] hash=digest.digest((thread+(char)0+role+(char)0+timestamp+(char)0+content).getBytes(StandardCharsets.UTF_8));
            char[] hex=new char[hash.length*2],digits="0123456789abcdef".toCharArray();
            for(int i=0;i<hash.length;i++){int b=hash[i]&255;hex[2*i]=digits[b>>>4];hex[2*i+1]=digits[b&15];}
            return new String(hex);
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }

    private static void append(SQLiteDatabase db,String thread,String owner,JSONArray messages) {
        if(messages==null||thread.isEmpty()||thread.length()>180)return;
        for(int i=0;i<messages.length();i++) {
            JSONObject message=messages.optJSONObject(i);if(message==null)continue;
            String role=message.optString("role");
            if(!role.equals("user")&&!role.equals("assistant"))continue;
            String content=messageText(message).trim();if(content.isEmpty())continue;
            // No tool calls, screenshots, or raw provider request bodies in long-lived history.
            if(content.length()>100_000)content=content.substring(0,100_000)+" [archived excerpt]";
            long timestamp=message.optLong("timestamp",0);
            ContentValues values=new ContentValues();
            values.put("thread_id",thread);values.put("owner",owner);values.put("role",role);
            values.put("timestamp",timestamp);values.put("text",content);
            values.put("event_key",fingerprint(thread,role,timestamp,content));
            long id=db.insertWithOnConflict("conversation_history",null,values,SQLiteDatabase.CONFLICT_IGNORE);
            if(id<0)continue;
            ContentValues indexed=new ContentValues();indexed.put("docid",id);indexed.put("text",content);
            db.insertOrThrow("conversation_history_fts",null,indexed);
        }
    }

    static void archive(SQLiteDatabase db,JSONObject checkpoint) {
        String main=checkpoint.optString("mainThreadId","00000000-0000-4000-8000-000000000000");
        if(main.isEmpty())main="00000000-0000-4000-8000-000000000000";
        append(db,main,"main",checkpoint.optJSONArray("mainMessages"));
        JSONObject workerMessages=checkpoint.optJSONObject("workerMessages");
        if(workerMessages==null)return;
        java.util.Iterator<String> workers=workerMessages.keys();
        while(workers.hasNext()) {
            String worker=workers.next();
            append(db,"worker:"+worker,worker,workerMessages.optJSONArray(worker));
        }
    }

    private static String match(String query) {
        Matcher tokens=TOKEN.matcher(query.toLowerCase(Locale.ROOT));
        ArrayList<String> terms=new ArrayList<>();
        while(tokens.find()&&terms.size()<8) {
            String word=tokens.group();
            if(word.length()>1&&word.length()<=40&&!COMMON.contains(" "+word+" ")&&!terms.contains(word))terms.add(word);
        }
        ArrayList<String> quoted=new ArrayList<>();
        for(String term:terms)quoted.add("\""+term+"\"");
        return String.join(" OR ",quoted);
    }

    static JSONArray search(SQLiteDatabase db,String query,int limit,String requester) {
        if(query==null||query.length()>300||limit<1||limit>50||requester==null||requester.isEmpty()||requester.length()>160)
            throw new IllegalArgumentException("Invalid history search");
        String expression=match(query);
        boolean main="main".equals(requester);
        String scope=main?"":"h.owner IN ('main',?)";
        String base="SELECT h.id,h.thread_id,h.owner,h.role,h.timestamp,substr(h.text,1,1800) FROM conversation_history h";
        if(!expression.isEmpty())base+=" JOIN conversation_history_fts f ON f.docid=h.id";
        String where=expression.isEmpty()?scope:"f.text MATCH ?"+(main?"":" AND "+scope);
        String sql=base+(where.isEmpty()?"":" WHERE "+where)+" ORDER BY h.timestamp DESC,h.id DESC LIMIT ?";
        String[] args=expression.isEmpty()?(main?new String[]{String.valueOf(limit)}:new String[]{requester,String.valueOf(limit)})
            :(main?new String[]{expression,String.valueOf(limit)}:new String[]{expression,requester,String.valueOf(limit)});
        JSONArray results=new JSONArray();
        try(Cursor cursor=db.rawQuery(sql,args)) {
            while(cursor.moveToNext()) {
                try {results.put(new JSONObject().put("id",cursor.getLong(0)).put("threadId",cursor.getString(1))
                    .put("owner",cursor.getString(2)).put("role",cursor.getString(3))
                    .put("timestamp",cursor.getLong(4)).put("text",cursor.getString(5)));}
                catch(org.json.JSONException impossible) {throw new IllegalStateException(impossible);}
            }
        }
        return results;
    }

    static JSONObject read(SQLiteDatabase db,long id,String requester) {
        if(id<=0||requester==null||requester.isEmpty()||requester.length()>160)
            throw new IllegalArgumentException("Invalid history reference");
        boolean main="main".equals(requester);
        String sql="SELECT id,thread_id,owner,role,timestamp,text FROM conversation_history WHERE id=?"+
            (main?"":" AND owner IN ('main',?)");
        String[] args=main?new String[]{String.valueOf(id)}:new String[]{String.valueOf(id),requester};
        try(Cursor cursor=db.rawQuery(sql,args)) {
            if(!cursor.moveToFirst())return new JSONObject();
            try {return new JSONObject().put("id",cursor.getLong(0)).put("threadId",cursor.getString(1))
                .put("owner",cursor.getString(2)).put("role",cursor.getString(3))
                .put("timestamp",cursor.getLong(4)).put("text",cursor.getString(5));}
            catch(org.json.JSONException impossible) {throw new IllegalStateException(impossible);}
        }
    }
}
