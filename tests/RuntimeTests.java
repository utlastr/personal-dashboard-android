package local.personaldashboard.mobile;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.os.Bundle;
import android.webkit.WebView;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import net.activitywatch.android.RustInterface;

public final class RuntimeTests extends Instrumentation {
    private Bundle arguments;
    public void onCreate(Bundle args) { arguments=args; super.onCreate(args); start(); }
    private void check(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);
    }
    private void add(Store store,String id,String pkg,String purpose,long start,long end) {
        ContentValues row=new ContentValues();
        row.put("id",id); row.put("start",start); row.put("end",end);
        row.put("pkg",pkg); row.put("app",pkg); row.put("activity","");
        row.put("purpose",purpose); row.put("role","personal");
        store.getWritableDatabase().insertOrThrow("sessions",null,row);
    }
    private void statistics(Context context,Bundle result) throws Exception {
        long day=1700006400000L,minute=60000,hour=3600000,now=day+12*hour;
        try(Store store=new Store(context,null)) {
            add(store,"a1","douyin","personal",day+hour,day+hour+5*minute);
            add(store,"a2","douyin","personal",day+11*hour,day+11*hour+5*minute);
            add(store,"b","work","work",day-hour,day+5*minute);
            add(store,"c","future-end","work",now-minute,now+minute);
            add(store,"d","yesterday","work",day-hour,day-minute);
            add(store,"e","future-start","personal",now+minute,now+2*minute);
            JSONObject totals=store.summary(now,day);
            check(totals.getLong("personal")==10*minute,"two separate sessions must sum to 10 minutes");
            check(totals.getLong("work")==6*minute,"midnight and future end clipping");
            check(totals.getJSONArray("apps").length()==3,"exclude yesterday and future sessions");
            check(store.summary(now,now-1000).getLong("work")==1000,"different daily boundary");
        }
        String json;
        try(InputStream input=getContext().getAssets().open("phone-events.json");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int count;
            while((count=input.read(buffer))!=-1) bytes.write(buffer,0,count);
            json=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        } catch(FileNotFoundException absent) {
            result.putString("phoneReplay","not provided"); return;
        }
        JSONArray events=new JSONArray(json);
        Calendar calendar=Calendar.getInstance(); calendar.set(Calendar.HOUR_OF_DAY,0);
        calendar.set(Calendar.MINUTE,0); calendar.set(Calendar.SECOND,0); calendar.set(Calendar.MILLISECOND,0);
        day=calendar.getTimeInMillis(); now=System.currentTimeMillis();
        long expectedWork=0,expectedPersonal=0,douyin=0;
        try(Store store=new Store(context,null)) {
            for(int i=0;i<events.length();i++) {
                JSONObject event=events.getJSONObject(i),data=event.getJSONObject("data");
                long start=Instant.parse(event.getString("timestamp")).toEpochMilli();
                long end=start+Math.round(event.getDouble("duration")*1000);
                String purpose=data.optString("purpose","personal"),pkg=data.optString("package");
                add(store,"replay-"+i,pkg,purpose,start,end);
                long duration=Math.max(0,Math.min(now,end)-Math.max(day,start));
                if(purpose.equals("work")) expectedWork+=duration; else expectedPersonal+=duration;
                if(pkg.equals("com.ss.android.ugc.aweme")) douyin+=duration;
            }
            JSONObject totals=store.summary(now,day);
            check(totals.getLong("work")==expectedWork,"real phone work totals");
            check(totals.getLong("personal")==expectedPersonal,"real phone personal totals");
            check(expectedWork+expectedPersonal<=now-day,"one phone cannot exceed elapsed day");
            result.putString("phoneReplay","PASS "+events.length()+" events; workMs="+expectedWork
                +"; personalMs="+expectedPersonal+"; douyinMs="+douyin);
        }
    }
    public void onStart() {
        Bundle result=new Bundle();
        try {
            Context c=getTargetContext();
            if("true".equals(arguments.getString("github"))) {
                JSONObject manifest=Updates.check(c);
                check(manifest.getInt("versionCode")==Updates.VERSION,"published version");
                check(Updates.download(c,manifest).length()>1000000,"published GitHub APK integrity");
                result.putString("result","PASS: live GitHub manifest and APK download/hash/package/signature");
                finish(Activity.RESULT_OK,result); return;
            }
            if("true".equals(arguments.getString("selfUpdate"))) {
                JSONObject manifest=SyncClient.request(c,"/mobile/v1/update",null);
                java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL("http://10.0.2.2:5670/mobile/v1/apk").openConnection();
                connection.setRequestProperty("Authorization","Bearer runtime-test-token");
                try { Updates.download(c,manifest,connection); } finally { connection.disconnect(); }
                Updates.save(c,manifest.put("available",true).put("ready",true));
                Updates.requestInstall(c);
                result.putString("result","self update submitted");
                finish(Activity.RESULT_OK,result); return;
            }
            statistics(c,result);
            ForegroundParser.Result parsed=ForegroundParser.parse(Arrays.asList(
                new ForegroundParser.Event(1,1000,"a","a"),
                new ForegroundParser.Event(1,5000,"b","b"),
                new ForegroundParser.Event(2,5100,"a","a"),
                new ForegroundParser.Event(16,10000,"","")));
            check(parsed.sessions.size()==2,"foreground switch/stale pause");
            check(parsed.sessions.get(1).end==10000,"screen-off boundary");
            check(ForegroundParser.parse(Arrays.asList(new ForegroundParser.Event(1,1000,"a","a"))).sessions.isEmpty(),"open session excluded");
            check(ForegroundParser.parse(Arrays.asList(
                new ForegroundParser.Event(1,1000,"a","a"),
                new ForegroundParser.Event(1,2000,"a","a"),
                new ForegroundParser.Event(26,5000,"",""))).sessions.size()==1,"same app/shutdown");
            long start=System.currentTimeMillis()-12000;
            try(Store store=new Store(c)) {
                store.getWritableDatabase().delete("sessions","pkg=?",new String[]{"test.runtime"});
                store.getWritableDatabase().execSQL("INSERT OR REPLACE INTO modes VALUES(?,?)",new Object[]{start,"personal"});
                store.getWritableDatabase().execSQL("INSERT OR REPLACE INTO modes VALUES(?,?)",new Object[]{start+5000,"work"});
                ForegroundParser.Session s=new ForegroundParser.Session("test.runtime","Activity",start,start+10000);
                store.insert(s,"Runtime test","work"); store.insert(s,"Runtime test","work");
                try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT COUNT(*),SUM(end-start),COUNT(DISTINCT purpose) FROM sessions WHERE pkg='test.runtime'",null)) {
                    cursor.moveToFirst();
                    check(cursor.getInt(0)==2 && cursor.getLong(1)==10000 && cursor.getInt(2)==2,"purpose split and dedupe");
                }
            }
            check(Collector.allowed(c),"usage permission");
            Collector.collect(c);
            check(new JSONObject(RustInterface.get(c).getBuckets()).has(Collector.bucket(c)),"JNI database and collector");
            Collector.prefs(c).edit().putString("server","http://10.0.2.2:5670").putString("token","runtime-test-token").commit();
            SyncClient.sync(c);
            try(Store store=new Store(c)) { check(store.pending("synced",500).length()==0,"sync acknowledged"); }
            JSONObject manifest=SyncClient.request(c,"/mobile/v1/update",null);
            Updates.validateManifest(manifest);
            long now=10*Updates.DAY;
            check(Updates.due(now,now-Updates.DAY,now-Updates.DAY),"daily check due");
            check(!Updates.due(now,now-1000,now-1000),"daily check throttled");
            check(!Updates.due(now,0,now-1000),"failed check backs off");
            check(Updates.due(now,0,now-3600001),"failed check retries");
            check(Updates.due(now,now+1000,now+1000),"clock rollback recovers");
            UpdateJob.schedule(c);
            android.app.job.JobInfo job=((android.app.job.JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).getPendingJob(8102);
            check(job!=null && job.isPersisted() && job.getNetworkType()==android.app.job.JobInfo.NETWORK_TYPE_ANY,"persistent network update job");
            boolean rejected=false;
            try { Updates.connect("http://github.com/"+Updates.REPOSITORY+"/releases/latest"); }
            catch(IOException expected) { rejected=true; }
            check(rejected,"reject insecure update source");
            java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL("http://10.0.2.2:5670/mobile/v1/apk").openConnection();
            connection.setRequestProperty("Authorization","Bearer runtime-test-token");
            try { check(Updates.download(c,manifest,connection).length()>1000000,"APK download/hash/package/signature verification"); }
            finally { connection.disconnect(); }
            JSONObject bad=new JSONObject(manifest.toString()).put("sha256",String.join("",Collections.nCopies(64,"0")));
            rejected=false;
            try { Updates.verify(c,bad,Updates.file(c)); } catch(IOException expected) { rejected=true; }
            check(rejected,"reject tampered APK");
            Activity activity=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Field field=MainActivity.class.getDeclaredField("web"); field.setAccessible(true);
            WebView web=(WebView)field.get(activity);
            String[] output={null};
            for(int i=0;i<20;i++) {
                CountDownLatch done=new CountDownLatch(1);
                runOnMainSync(()->web.evaluateJavascript(
                    "document.documentElement && JSON.stringify({version:JSON.parse(Dashboard.state()).version,heading:document.querySelector('h1')?.textContent,update:!!document.querySelector('#home-check-update'),overflow:document.documentElement.scrollWidth>innerWidth})",
                    value->{output[0]=value;done.countDown();}));
                done.await(2,TimeUnit.SECONDS);
                if(output[0]!=null && output[0].contains("heading")) break;
                Thread.sleep(200);
            }
            check(output[0]!=null && output[0].contains(Updates.NAME),"WebView bridge");
            check(output[0].contains("overflow\\\":false"),"mobile layout overflow");
            check(output[0].contains("update\\\":true"),"homepage update control");
            runOnMainSync(()->web.loadUrl("http://127.0.0.1:5667/#/digital"));
            for(int i=0;i<40;i++) {
                CountDownLatch done=new CountDownLatch(1);
                runOnMainSync(()->web.evaluateJavascript(
                    "JSON.stringify({text:document.body?.innerText,charts:document.querySelectorAll('canvas').length})",
                    value->{output[0]=value;done.countDown();}));
                done.await(2,TimeUnit.SECONDS);
                if(output[0]!=null && output[0].contains("ActivityWatch")) break;
                Thread.sleep(250);
            }
            result.putString("fullDashboard",output[0]);
            result.putString("result","PASS: parser, statistics, sync, daily update gate/backoff/job, download/hash/signature, homepage, WebView layout");
            result.putString("web",output[0]);
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            result.putString("error",android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
