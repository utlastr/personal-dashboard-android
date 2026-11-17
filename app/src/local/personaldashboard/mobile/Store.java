package local.personaldashboard.mobile;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

final class Store extends SQLiteOpenHelper {
    private final Set<String> launchers=new HashSet<>(Arrays.asList(
        "com.android.launcher","com.android.launcher2","com.android.launcher3",
        "com.google.android.apps.nexuslauncher","com.miui.home","com.sec.android.app.launcher",
        "com.oppo.launcher","com.oplus.launcher","com.oneplus.launcher","com.coloros.launcher","com.huawei.android.launcher",
        "app.lawnchair.play","app.lawnchair","ch.deletescape.lawnchair.plah","com.teslacoilsw.launcher"));
    Store(Context context) { this(context,"mobile.sqlite"); }
    Store(Context context,String name) {
        super(context,name,null,1);
        Intent home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for(android.content.pm.ResolveInfo info:context.getPackageManager().queryIntentActivities(home,0))
            if(info.activityInfo!=null && !"com.android.settings".equals(info.activityInfo.packageName))
                launchers.add(info.activityInfo.packageName);
    }
    boolean excluded(String pkg,String label) {
        return launchers.contains(pkg) || "System Launcher".equalsIgnoreCase(label) || "系统桌面".equals(label)
            || "com.google.android.googlequicksearchbox".equals(pkg)
            || "com.android.quicksearchbox".equals(pkg)
            || "QuickSearchBox".equalsIgnoreCase(label) || "KeyTap".equalsIgnoreCase(label)
            || "android".equals(pkg) || "com.android.settings.intelligence".equals(pkg)
            || "com.android.permissioncontroller".equals(pkg) || "com.android.packageinstaller".equals(pkg)
            || pkg.toLowerCase(Locale.ROOT).matches(".*(?:^|\\.)keytap(?:\\..*)?");
    }
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,start INTEGER,end INTEGER,pkg TEXT,app TEXT,activity TEXT,purpose TEXT,role TEXT,synced INTEGER DEFAULT 0,mirrored INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE modes(time INTEGER PRIMARY KEY,purpose TEXT)");
        db.execSQL("INSERT INTO modes VALUES(0,'personal')");
    }
    public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {}
    synchronized void insert(ForegroundParser.Session session,String app,String role) {
        if(excluded(session.pkg,app)) return;
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
                if(excluded(c.getString(1),c.getString(0))) continue;
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
    synchronized JSONObject report(String period,String date,long now) throws Exception {
        boolean week="week".equals(period);
        ZoneId zone=ZoneId.systemDefault();
        LocalDate anchor=date.isEmpty()?Instant.ofEpochMilli(now).atZone(zone).toLocalDate():LocalDate.parse(date);
        LocalDate first=week?anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)):anchor;
        int count=week?7:1;
        long start=first.atStartOfDay(zone).toInstant().toEpochMilli();
        long end=first.plusDays(count).atStartOfDay(zone).toInstant().toEpochMilli();
        long until=Math.min(now,end);
        Map<String,JSONObject> apps=new LinkedHashMap<>();
        Map<String,Long> covered=new HashMap<>();
        JSONArray dates=new JSONArray();
        for(int i=0;i<count;i++) dates.put(first.plusDays(i).toString());
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT pkg,app,start,end FROM sessions WHERE end>CAST(? AS INTEGER) AND start<CAST(? AS INTEGER) AND end>start ORDER BY start,end",
            new String[]{""+start,""+until})) {
            while(c.moveToNext()) {
                String pkg=c.getString(0),label=c.getString(1);
                if(excluded(pkg,label)) continue;
                long previousEnd=covered.getOrDefault(pkg,Long.MIN_VALUE);
                long a=Math.max(Math.max(start,c.getLong(2)),previousEnd);
                long b=Math.min(until,c.getLong(3));
                if(b<=a) continue;
                boolean continuation=c.getLong(2)<=previousEnd;
                covered.put(pkg,b);
                JSONObject app=apps.get(pkg);
                if(app==null) {
                    app=new JSONObject().put("package",pkg).put("app",label).put("ms",0)
                        .put("hours",zeros(24)).put("frequency",zeros(24)).put("days",zeros(count));
                    apps.put(pkg,app);
                }
                app.put("ms",app.getLong("ms")+b-a);
                // Split at real local hour boundaries, including midnight and daylight-saving changes.
                Set<Integer> seen=new HashSet<>();
                while(a<b) {
                    java.time.ZonedDateTime local=Instant.ofEpochMilli(a).atZone(zone);
                    long next=local.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli();
                    long stop=Math.min(b,next);
                    int hour=local.getHour(),day=(int)java.time.temporal.ChronoUnit.DAYS.between(first,local.toLocalDate());
                    JSONArray hours=app.getJSONArray("hours"),days=app.getJSONArray("days"),frequency=app.getJSONArray("frequency");
                    hours.put(hour,hours.getLong(hour)+stop-a);
                    days.put(day,days.getLong(day)+stop-a);
                    long hourStart=local.truncatedTo(java.time.temporal.ChronoUnit.HOURS).toInstant().toEpochMilli();
                    if(seen.add(hour) && !(continuation && previousEnd>hourStart && a==previousEnd))
                        frequency.put(hour,frequency.getInt(hour)+1);
                    a=stop;
                }
            }
        }
        ArrayList<JSONObject> sorted=new ArrayList<>(apps.values());
        sorted.sort((a,b)->Long.compare(b.optLong("ms"),a.optLong("ms")));
        JSONArray rows=new JSONArray(),hours=zeros(24),days=zeros(count);
        long total=0;
        for(JSONObject app:sorted) {
            rows.put(app); total+=app.getLong("ms");
            for(int i=0;i<24;i++) hours.put(i,hours.getLong(i)+app.getJSONArray("hours").getLong(i));
            for(int i=0;i<count;i++) days.put(i,days.getLong(i)+app.getJSONArray("days").getLong(i));
        }
        int pending;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sessions WHERE synced=0",null)) { c.moveToFirst(); pending=c.getInt(0); }
        return new JSONObject().put("total",total).put("apps",rows).put("hours",hours).put("days",days)
            .put("dates",dates).put("start",first.toString()).put("last",first.plusDays(count-1).toString())
            .put("period",week?"week":"day").put("pending",pending);
    }
    private static JSONArray zeros(int count) {
        JSONArray result=new JSONArray();
        for(int i=0;i<count;i++) result.put(0);
        return result;
    }
}
