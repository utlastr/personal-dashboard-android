package local.personaldashboard.mobile;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.time.Instant;
import java.util.*;

final class Store extends SQLiteOpenHelper {
    Store(Context context) { this(context,"mobile.sqlite"); }
    Store(Context context,String name) { super(context,name,null,1); }
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,start INTEGER,end INTEGER,pkg TEXT,app TEXT,activity TEXT,purpose TEXT,role TEXT,synced INTEGER DEFAULT 0,mirrored INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE modes(time INTEGER PRIMARY KEY,purpose TEXT)");
        db.execSQL("INSERT INTO modes VALUES(0,'personal')");
    }
    public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {}
    synchronized void insert(ForegroundParser.Session session,String app,String role) {
        SQLiteDatabase db=getWritableDatabase();
        // Preserve old session IDs and boundaries when a previously split interval is collected again.
        ArrayList<long[]> gaps=new ArrayList<>();
        long cursor=session.start;
        try(Cursor c=db.rawQuery("SELECT start,end FROM sessions WHERE pkg=? AND end>? AND start<? ORDER BY start",
            new String[]{session.pkg,""+session.start,""+session.end})) {
            while(c.moveToNext()) {
                if(c.getLong(0)>cursor) gaps.add(new long[]{cursor,c.getLong(0)});
                cursor=Math.max(cursor,c.getLong(1));
            }
        }
        if(cursor<session.end) gaps.add(new long[]{cursor,session.end});
        db.beginTransaction();
        try {
            for(long[] gap:gaps) {
                long start=gap[0],end=gap[1];
                if(end<=start) continue;
                ContentValues v=new ContentValues();
                v.put("id",session.pkg+":"+start); v.put("start",start); v.put("end",end);
                v.put("pkg",session.pkg); v.put("app",app); v.put("activity",session.activity);
                // Legacy protocol column retained for older sync servers; it is no longer a UI category.
                v.put("purpose",role); v.put("role",role);
                db.insertWithOnConflict("sessions",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized JSONArray pending(String column,int limit) throws Exception {
        if(!column.equals("synced")&&!column.equals("mirrored")) throw new IllegalArgumentException();
        JSONArray rows=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM sessions WHERE "+column+"=0 ORDER BY start LIMIT ?",new String[]{""+limit})) {
            while(c.moveToNext()) {
                JSONObject row=new JSONObject();
                for(String key:new String[]{"id","pkg","app","activity","purpose","role"}) row.put(key,c.getString(c.getColumnIndexOrThrow(key)));
                row.put("start",c.getLong(c.getColumnIndexOrThrow("start")));
                row.put("end",c.getLong(c.getColumnIndexOrThrow("end")));
                rows.put(row);
            }
        }
        return rows;
    }
    synchronized void marked(String id,String column) {
        if(!column.equals("synced")&&!column.equals("mirrored")) throw new IllegalArgumentException();
        getWritableDatabase().execSQL("UPDATE sessions SET "+column+"=1 WHERE id=?",new Object[]{id});
    }
    static JSONObject awEvent(JSONObject row) throws Exception {
        JSONObject data=new JSONObject();
        data.put("app",row.getString("app")); data.put("title",row.optString("activity"));
        data.put("package",row.getString("pkg")); data.put("classname",row.optString("activity"));
        data.put("purpose",row.getString("purpose")); data.put("device_role",row.getString("role"));
        data.put("session_id",row.getString("id"));
        return new JSONObject().put("timestamp",Instant.ofEpochMilli(row.getLong("start")).toString())
            .put("duration",(row.getLong("end")-row.getLong("start"))/1000.0).put("data",data);
    }
    synchronized JSONObject summary() throws Exception {
        Calendar day=Calendar.getInstance(); day.set(Calendar.HOUR_OF_DAY,0); day.set(Calendar.MINUTE,0); day.set(Calendar.SECOND,0); day.set(Calendar.MILLISECOND,0);
        return summary(System.currentTimeMillis(),day.getTimeInMillis());
    }
    synchronized JSONObject summary(long now,long dayStart) throws Exception {
        JSONObject result=new JSONObject(); JSONArray apps=new JSONArray(),recent=new JSONArray();
        long total=0;
        // rawQuery binds String[] as TEXT; scalar MIN/MAX need explicit numeric casts.
        String query="SELECT app,pkg,SUM(MIN(end,CAST(? AS INTEGER))-MAX(start,CAST(? AS INTEGER))) duration "
            +"FROM sessions WHERE end>CAST(? AS INTEGER) AND start<CAST(? AS INTEGER) AND end>start "
            +"GROUP BY pkg ORDER BY duration DESC";
        try(Cursor c=getReadableDatabase().rawQuery(query,new String[]{""+now,""+dayStart,""+dayStart,""+now})) {
            while(c.moveToNext()) {
                long ms=c.getLong(2); total+=ms;
                apps.put(new JSONObject().put("app",c.getString(0)).put("package",c.getString(1)).put("ms",ms));
            }
        }
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,app,start,end FROM sessions ORDER BY start DESC LIMIT 40",null)) {
            while(c.moveToNext()) recent.put(new JSONObject().put("id",c.getString(0)).put("app",c.getString(1)).put("start",c.getLong(2)).put("end",c.getLong(3)));
        }
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sessions WHERE synced=0",null)) { c.moveToFirst(); result.put("pending",c.getInt(0)); }
        return result.put("total",total).put("apps",apps).put("recent",recent);
    }
}
