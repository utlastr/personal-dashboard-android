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
    private void screenshot(Context context,String name) throws Exception {
        Thread.sleep(600);
        android.graphics.Bitmap image=getUiAutomation().takeScreenshot();
        check(image!=null,"Android screenshot");
        try(FileOutputStream output=new FileOutputStream(new File(context.getExternalFilesDir(null),name))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);
        } finally { image.recycle(); }
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
            add(store,"a2","douyin","work",day+11*hour,day+11*hour+5*minute);
            add(store,"b","work","work",day-hour,day+5*minute);
            add(store,"c","future-end","work",now-minute,now+minute);
            add(store,"d","yesterday","work",day-hour,day-minute);
            add(store,"e","future-start","personal",now+minute,now+2*minute);
            JSONObject totals=store.summary(now,day);
            check(totals.getLong("total")==16*minute,"local phone total with midnight and future clipping");
            check(!totals.has("work")&&!totals.has("personal")&&!totals.has("purpose"),"no purpose categories");
            check(totals.getJSONArray("apps").length()==3,"exclude yesterday and future sessions");
            check(totals.getJSONArray("apps").getJSONObject(0).getLong("ms")==10*minute,"legacy purposes merge into one app");
            check(store.summary(now,now-1000).getLong("total")==1000,"different daily boundary");
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
        now=0;
        for(int i=0;i<events.length();i++) {
            JSONObject event=events.getJSONObject(i);
            now=Math.max(now,Instant.parse(event.getString("timestamp")).toEpochMilli()+Math.round(event.getDouble("duration")*1000));
        }
        calendar.setTimeInMillis(now); calendar.set(Calendar.HOUR_OF_DAY,0); calendar.set(Calendar.MINUTE,0);
        calendar.set(Calendar.SECOND,0); calendar.set(Calendar.MILLISECOND,0); day=calendar.getTimeInMillis();
        long expectedWork=0,expectedPersonal=0,douyin=0;
        try(Store store=new Store(context,null)) {
            for(int i=0;i<events.length();i++) {
                JSONObject event=events.getJSONObject(i),data=event.getJSONObject("data");
                long start=Instant.parse(event.getString("timestamp")).toEpochMilli();
                long end=start+Math.round(event.getDouble("duration")*1000);
                String purpose=data.optString("purpose","personal"),pkg=data.optString("package");
                add(store,"replay-"+i,pkg,purpose,start,end);
                store.getWritableDatabase().execSQL("UPDATE sessions SET app=? WHERE id=?",new Object[]{data.optString("app",pkg),"replay-"+i});
                if(store.excluded(pkg,data.optString("app",pkg))) continue;
                long duration=Math.max(0,Math.min(now,end)-Math.max(day,start));
                if(purpose.equals("work")) expectedWork+=duration; else expectedPersonal+=duration;
                if(pkg.equals("com.ss.android.ugc.aweme")) douyin+=duration;
            }
            JSONObject totals=store.summary(now,day);
            check(totals.getLong("total")==expectedWork+expectedPersonal,"real phone total across legacy labels");
            check(expectedWork+expectedPersonal<=now-day,"one phone cannot exceed elapsed day");
            result.putString("phoneReplay","PASS "+events.length()+" events; workMs="+expectedWork
                +"; personalMs="+expectedPersonal+"; douyinMs="+douyin);
        }
    }
    private long instant(String value) {
        return java.time.LocalDateTime.parse(value).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
    private void ranges(Context context) throws Exception {
        long minute=60000,now=instant("2026-11-16T12:00:00"),monday=instant("2026-11-09T00:00:00");
        try(Store store=new Store(context,null)) {
            add(store,"cross","example.app","personal",monday-10*minute,monday+10*minute);
            add(store,"hour","example.app","work",monday+50*minute,monday+80*minute);
            add(store,"duplicate","example.app","work",monday+55*minute,monday+70*minute);
            add(store,"sunday","example.app","work",monday+6*86400000L+23*3600000L,monday+7*86400000L+5*minute);
            add(store,"old-launcher","com.android.launcher3","personal",monday,monday+3600000);
            add(store,"other-launcher","vendor.desktop","personal",monday,monday+3600000);
            store.getWritableDatabase().execSQL("UPDATE sessions SET app='System Launcher' WHERE pkg='vendor.desktop'");
            store.insert(new ForegroundParser.Session("com.android.launcher3","",monday,monday+7200000),"Launcher","personal");
            JSONObject day=store.report("day","2026-11-09",now);
            check(day.getLong("total")==40*minute,"day clips midnight, excludes historical launchers and overlapping duplicate");
            check(day.getJSONArray("apps").length()==1,"launcher absent from app ranking");
            check(day.getJSONArray("hours").getLong(0)==20*minute && day.getJSONArray("hours").getLong(1)==20*minute,"session splits at hour");
            JSONObject app=day.getJSONArray("apps").getJSONObject(0);
            check(app.getJSONArray("frequency").getInt(0)==2 && app.getJSONArray("frequency").getInt(1)==1,"hourly session frequency");
            JSONObject week=store.report("week","2026-11-15",now);
            check(week.getString("start").equals("2026-11-09") && week.getString("last").equals("2026-11-15"),"Monday to Sunday week");
            check(week.getLong("total")==100*minute && week.getJSONArray("days").getLong(6)==60*minute,"week clips next Monday");
            check(store.report("day","2026-11-16",now).getLong("total")==5*minute,"next day receives cross-midnight tail");
            check(store.report("day","2026-11-17",now).getLong("total")==0,"future range is empty");
            check(store.report("day","2026-11-09",monday+60*minute).getLong("total")==20*minute,"current interval clips now");
        }
        try(Store store=new Store(context,null)) {
            add(store,"legacy-1","app","personal",monday,monday+5*minute);
            add(store,"legacy-2","app","work",monday+5*minute,monday+10*minute);
            check(store.report("day","2026-11-09",now).getJSONArray("apps").getJSONObject(0)
                .getJSONArray("frequency").getInt(0)==1,"legacy split is one continuous use");
        }
        TimeZone previous=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            for(String date:new String[]{"2026-03-08","2026-11-01"}) {
                java.time.LocalDate first=java.time.LocalDate.parse(date);
                long a=first.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                long b=first.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                try(Store store=new Store(context,null)) {
                    add(store,date,"dst","personal",a,b);
                    JSONObject report=store.report("day",date,b);
                    long sum=0; for(int i=0;i<24;i++) sum+=report.getJSONArray("hours").getLong(i);
                    check(sum==b-a && report.getLong("total")==b-a,"DST day conserves actual duration "+date);
                }
            }
        } finally { TimeZone.setDefault(previous); }
    }
    public void onStart() {
        Bundle result=new Bundle();
        try {
            Context c=getTargetContext();
            if("true".equals(arguments.getString("github"))) {
                if("true".equals(arguments.getString("selfUpdate"))) {
                    Collector.prefs(c).edit().putString("upgradeSentinel","keep-data")
                        .putLong("updateChecked",0).putLong("updateAttempted",0).commit();
                    if("true".equals(arguments.getString("background"))) {
                        UpdateJob.schedule(c);
                        result.putString("result","background update job scheduled; ready to close app and trigger job");
                        finish(Activity.RESULT_OK,result); return;
                    }
                    check(Updates.run(c,true),"live automatic update");
                    result.putString("result","live GitHub self update submitted");
                    finish(Activity.RESULT_OK,result); return;
                }
                JSONObject manifest=Updates.check(c);
                check(manifest.getInt("versionCode")<=Updates.VERSION,"published version");
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
            ranges(c);
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
                    check(cursor.getInt(0)==1 && cursor.getLong(1)==10000 && cursor.getInt(2)==1,"device role only; legacy modes ignored; dedupe");
                }
                add(store,"legacy-a","test.legacy","personal",start,start+5000);
                add(store,"legacy-b","test.legacy","work",start+5000,start+10000);
                store.insert(new ForegroundParser.Session("test.legacy","Activity",start,start+10000),"Legacy","personal");
                try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT COUNT(*),SUM(end-start) FROM sessions WHERE pkg='test.legacy'",null)) {
                    cursor.moveToFirst();
                    check(cursor.getInt(0)==2 && cursor.getLong(1)==10000,"upgrade recollection preserves old split IDs without duplication");
                }
                store.getWritableDatabase().delete("sessions","pkg=?",new String[]{"test.legacy"});
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
                    "document.documentElement && JSON.stringify({version:JSON.parse(Dashboard.state()).version,heading:document.querySelector('h1')?.textContent,backgroundUpdate:!document.querySelector('#home-check-update')&&!document.querySelector('#device-dialog').open,tabs:Array.from(document.querySelectorAll('[role=tab]')).map(x=>x.textContent).join(','),localOnly:!!document.querySelector('#total-time')&&!document.querySelector('#work-time')&&!document.querySelector('#shared-devices')&&!document.querySelector('#work'),overflow:document.documentElement.scrollWidth>innerWidth})",
                    value->{output[0]=value;done.countDown();}));
                done.await(2,TimeUnit.SECONDS);
                if(output[0]!=null && output[0].contains("heading")) break;
                Thread.sleep(200);
            }
            check(output[0]!=null && output[0].contains(Updates.NAME),"WebView bridge");
            check(output[0].contains("overflow\\\":false"),"mobile layout overflow");
            check(output[0].contains("backgroundUpdate\\\":true"),"no update panel on homepage");
            check(output[0].contains("Day,Week"),"only Day and Week tabs");
            check(output[0].contains("localOnly\\\":true"),"phone homepage shows only local device total");
            screenshot(c,"verify-day.png");
            CountDownLatch interaction=new CountDownLatch(1);
            runOnMainSync(()->web.evaluateJavascript(
                "document.querySelector('#week').click(); document.querySelector('#apps .row').click(); document.querySelector('#detail-group').value='days'; document.querySelector('#detail-group').dispatchEvent(new Event('change')); JSON.stringify({open:document.querySelector('#app-detail').open,bars:document.querySelectorAll('#detail-chart .bar-column').length,packageCaption:!!document.querySelector('#apps small'),overflow:document.documentElement.scrollWidth>innerWidth})",
                value->{output[0]=value;interaction.countDown();}));
            interaction.await(3,TimeUnit.SECONDS);
            check(output[0]!=null && output[0].contains("open\\\":true") && output[0].contains("bars\\\":7"),"week app detail has seven daily bars");
            check(output[0].contains("packageCaption\\\":false") && output[0].contains("overflow\\\":false"),"clean labels and detail layout");
            screenshot(c,"verify-week-detail.png");
            CountDownLatch hourly=new CountDownLatch(1);
            runOnMainSync(()->web.evaluateJavascript(
                "document.querySelector('#detail-group').value='hours'; document.querySelector('#detail-group').dispatchEvent(new Event('change')); document.querySelector('#detail-metric').value='frequency'; document.querySelector('#detail-metric').dispatchEvent(new Event('change')); JSON.stringify({bars:document.querySelectorAll('#detail-chart .bar-column').length,frequency:document.querySelector('#detail-chart button').getAttribute('aria-label').includes('次')})",
                value->{output[0]=value;hourly.countDown();}));
            hourly.await(3,TimeUnit.SECONDS);
            check(output[0]!=null && output[0].contains("bars\\\":24") && output[0].contains("frequency\\\":true"),"24 hour frequency detail");
            screenshot(c,"verify-hours-detail.png");
            CountDownLatch limitPanel=new CountDownLatch(1);
            runOnMainSync(()->web.evaluateJavascript(
                "document.querySelector('#app-detail').close();document.querySelector('#connection').click();JSON.stringify({open:document.querySelector('#device-dialog').open,limits:!!document.querySelector('#limits-enabled'),honest:document.querySelector('#limit-status').textContent.includes('没有隐藏权限'),bili:document.querySelector('#device-dialog').textContent.includes('22:00–23:00'),douyin:document.querySelector('#device-dialog').textContent.includes('15:30–17:30'),overflow:document.documentElement.scrollWidth>innerWidth})",
                value->{output[0]=value;limitPanel.countDown();}));
            limitPanel.await(3,TimeUnit.SECONDS);
            check(output[0]!=null&&output[0].contains("open\\\":true")&&output[0].contains("limits\\\":true")
                &&output[0].contains("honest\\\":true")&&output[0].contains("bili\\\":true")&&output[0].contains("douyin\\\":true")
                &&output[0].contains("overflow\\\":false"),"limit menu capability, hours and layout");
            screenshot(c,"verify-limit-menu.png");
            CountDownLatch toggle=new CountDownLatch(1);
            runOnMainSync(()->web.evaluateJavascript(
                "(()=>{const input=document.querySelector('#limits-enabled'); const original=input.checked; input.click(); const changed=JSON.parse(Dashboard.state()).limits.enabled===!original && input.checked===!original; input.click(); return changed && JSON.parse(Dashboard.state()).limits.enabled===original && input.checked===original;})()",
                value->{output[0]=value;toggle.countDown();}));
            check(toggle.await(3,TimeUnit.SECONDS)&&"true".equals(output[0]),"limit toggle immediately persists both states");
            runOnMainSync(web::reload);
            boolean restored=false;
            for(int i=0;i<20&&!restored;i++) {
                CountDownLatch ready=new CountDownLatch(1);
                runOnMainSync(()->web.evaluateJavascript(
                    "document.querySelector('#week')?.getAttribute('aria-selected')==='true' && !!document.querySelector('#app-count')?.textContent",
                    value->{output[0]=value;ready.countDown();}));
                restored=ready.await(2,TimeUnit.SECONDS)&&"true".equals(output[0]);
                if(!restored) Thread.sleep(200);
            }
            check(restored,"Week selection survives WebView reload");
            // Leave the real WebView on its homepage for visual verification.
            runOnMainSync(()->web.evaluateJavascript("document.querySelector('#device-dialog').close(); document.querySelector('#day').click();",null));
            result.putString("result","PASS: Day/Week totals, hourly/daily charts, DST, launcher filtering, overlap dedupe, legacy labels, sync, update job, APK integrity, WebView interactions");
            result.putString("web",output[0]);
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            result.putString("error",android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
