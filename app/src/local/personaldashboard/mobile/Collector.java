package local.personaldashboard.mobile;

import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.app.AppOpsManager;
import android.content.pm.*;
import android.os.*;
import net.activitywatch.android.RustInterface;
import org.json.*;
import java.util.*;

final class Collector {
    static boolean allowed(Context context) {
        return ((AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE))
            .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),context.getPackageName())==AppOpsManager.MODE_ALLOWED;
    }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("dashboard",0); }
    static synchronized String device(Context c) {
        SharedPreferences p=prefs(c); String id=p.getString("device","");
        if(id.isEmpty()) { id="android-"+UUID.randomUUID().toString().substring(0,12); p.edit().putString("device",id).commit(); }
        return id;
    }
    static String bucket(Context c) { return "aw-watcher-android_"+device(c); }
    static synchronized void collect(Context c) throws Exception {
        if(!allowed(c)) return;
        SharedPreferences p=prefs(c); long now=System.currentTimeMillis();
        long since=Math.max(now-7*86400000L,p.getLong("cursor",now-86400000L));
        UsageEvents events=((UsageStatsManager)c.getSystemService(Context.USAGE_STATS_SERVICE)).queryEvents(since,now);
        if(events==null) throw new IllegalStateException("系统暂时未提供使用记录，请解锁后重试");
        ArrayList<ForegroundParser.Event> raw=new ArrayList<>();
        UsageEvents.Event event=new UsageEvents.Event();
        while(events.hasNextEvent()) {
            events.getNextEvent(event);
            raw.add(new ForegroundParser.Event(event.getEventType(),event.getTimeStamp(),
                event.getPackageName()==null?"":event.getPackageName(),event.getClassName()==null?"":event.getClassName()));
        }
        raw.sort(Comparator.comparingLong(e->e.time));
        ForegroundParser.Result parsed=ForegroundParser.parse(raw);
        try(Store store=new Store(c)) {
            for(ForegroundParser.Session session:parsed.sessions) {
                String app=session.pkg;
                try { app=c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(session.pkg,0)).toString(); }
                catch(PackageManager.NameNotFoundException ignored) {}
                store.insert(session,app,p.getString("role","personal"));
            }
            p.edit().putLong("cursor",parsed.openStart>=0?parsed.openStart:now).putLong("collected",now).commit();
            RustInterface rust=RustInterface.get(c);
            String id=bucket(c);
            JSONObject buckets=new JSONObject(rust.getBuckets());
            if(!buckets.has(id)) check(rust.createBucket(new JSONObject().put("id",id).put("type","currentwindow").put("hostname",device(c)).put("client","personal-dashboard-android").toString()));
            JSONArray pending;
            do {
                pending=store.pending("mirrored",500);
                for(int i=0;i<pending.length();i++) {
                    JSONObject row=pending.getJSONObject(i);
                    check(rust.heartbeat(id,Store.awEvent(row).toString(),0));
                    store.marked(row.getString("id"),"mirrored");
                }
            } while(pending.length()==500);
        }
    }
    static void check(String response) throws Exception {
        if(response==null) throw new IllegalStateException("数据引擎未响应");
        if(response.startsWith("{")) {
            JSONObject object=new JSONObject(response);
            if(object.has("error")) throw new IllegalStateException(object.optString("error"));
        }
    }
}
