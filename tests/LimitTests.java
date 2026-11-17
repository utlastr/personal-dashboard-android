package local.personaldashboard.mobile;

import android.app.*;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.time.*;
import org.json.*;

public final class LimitTests extends Instrumentation {
    private Bundle args;
    public void onCreate(Bundle bundle) { args=bundle; super.onCreate(bundle); start(); }
    private void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private ZonedDateTime time(int minute) {
        return LocalDate.of(2026,11,17).atTime(minute/60,minute%60).atZone(ZoneId.systemDefault());
    }
    private String shell(UiAutomation ui,String command) throws Exception {
        try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int count;
            while((count=input.read(buffer))!=-1) bytes.write(buffer,0,count);
            return bytes.toString("UTF-8");
        }
    }
    private void rules(Context c) throws Exception {
        for(int minute=0;minute<1440;minute++) {
            check(AppLimits.allowed("tv.danmaku.bili",time(minute))==(minute>=1320&&minute<1380),"Bilibili minute "+minute);
            check(AppLimits.allowed("com.bilibili.app.in",time(minute))==(minute>=1320&&minute<1380),"installed Bilibili variant");
            check(AppLimits.allowed("com.ss.android.ugc.aweme",time(minute))==(minute>=930&&minute<1050),"Douyin minute "+minute);
            check(AppLimits.allowed("com.android.settings",time(minute)),"unrelated app must stay allowed");
            check(AppLimits.nextBoundary(time(minute))>time(minute).toInstant().toEpochMilli(),"next alarm strictly future");
        }
        check(AppLimits.nextBoundary(time(1380))==time(930).plusDays(1).toInstant().toEpochMilli(),"daily rollover");
        for(String zone:new String[]{"Asia/Shanghai","America/New_York"}) {
            ZonedDateTime dst=LocalDate.of(2026,11,1).atTime(23,1).atZone(ZoneId.of(zone));
            check(AppLimits.nextBoundary(dst)==dst.toLocalDate().plusDays(1).atTime(15,30).atZone(dst.getZone()).toInstant().toEpochMilli(),"zone boundary");
        }
        LimitJob.schedule(c);
        android.app.job.JobInfo job=((android.app.job.JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).getPendingJob(8103);
        check(job!=null&&job.isPersisted(),"persistent recovery job");
    }
    private boolean launchable(Context c,String pkg) {
        return !c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),0).isEmpty();
    }
    private String resumed(String dump) {
        for(String line:dump.split("\\n"))
            if(line.contains("topResumedActivity=")||line.contains("mResumedActivity:")) return line;
        return "";
    }
    private void owner(Context c) throws Exception {
        check(AppLimits.canHide(c),"owner hiding permission");
        DevicePolicyManager p=AppLimits.policy(c);
        ComponentName admin=AppLimits.admin(c);
        String bili="tv.danmaku.bili",douyin="com.ss.android.ugc.aweme";
        check(AppLimits.installed(c,bili)&&AppLimits.installed(c,douyin),"fixtures installed");
        try {
            AppLimits.setEnabled(c,true);
            AppLimits.apply(c,time(600));
            check(p.isApplicationHidden(admin,bili)&&p.isApplicationHidden(admin,douyin),"both hidden outside hours");
            check(!launchable(c,bili)&&!launchable(c,douyin),"both absent from all-apps launcher query");
            AppLimits.apply(c,time(930));
            check(p.isApplicationHidden(admin,bili)&&!p.isApplicationHidden(admin,douyin),"15:30 only Douyin restored");
            check(launchable(c,douyin),"Douyin icon restored");
            AppLimits.apply(c,time(1050));
            check(p.isApplicationHidden(admin,douyin),"17:30 Douyin hidden");
            AppLimits.apply(c,time(1320));
            check(!p.isApplicationHidden(admin,bili)&&p.isApplicationHidden(admin,douyin),"22:00 only Bilibili restored");
            AppLimits.apply(c,time(1380));
            check(p.isApplicationHidden(admin,bili),"23:00 Bilibili hidden");
            check(AppLimits.setEnabled(c,false),"disable restores apps");
            check(launchable(c,bili)&&launchable(c,douyin),"both icons restored when disabled");
            p.setApplicationHidden(admin,bili,true);
            AppLimits.apply(c,time(1320));
            check(p.isApplicationHidden(admin,bili),"preexisting admin restriction preserved");
            p.setApplicationHidden(admin,bili,false);
        } finally {
            AppLimits.setEnabled(c,false);
            p.setApplicationHidden(admin,bili,false); p.setApplicationHidden(admin,douyin,false);
            p.clearDeviceOwnerApp(c.getPackageName());
            AppLimits.setEnabled(c,true);
        }
    }
    private void accessibility(Context c) throws Exception {
        UiAutomation ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        check(!AppLimits.canHide(c),"ordinary phone has no hiding permission");
        AppLimits.setEnabled(c,true);
        shell(ui,"settings put secure enabled_accessibility_services ''");
        shell(ui,"settings put secure enabled_accessibility_services local.personaldashboard.mobile/local.personaldashboard.mobile.AppLimitService");
        shell(ui,"settings put secure accessibility_enabled 1");
        long connectedDeadline=SystemClock.uptimeMillis()+5000;
        while(!AppLimitService.connected()&&SystemClock.uptimeMillis()<connectedDeadline) Thread.sleep(100);
        check(AppLimits.state(c).getBoolean("accessibility"),"accessibility connected");
        if("boundary".equals(args.getString("mode"))) {
            long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();
            String autoTime=shell(ui,"settings get global auto_time").trim();
            try {
                shell(ui,"settings put global auto_time 0");
                for(String target:new String[]{"tv.danmaku.bili","com.ss.android.ugc.aweme"}) {
                    String open=target.equals("tv.danmaku.bili")?"22:59:50":"17:29:50";
                    String close=target.equals("tv.danmaku.bili")?"23:00:00":"17:30:00";
                    shell(ui,"su 0 date -s "+open);
                    shell(ui,"am start -W -n "+target+"/fixture.LimitActivity");
                    Thread.sleep(700);
                    check(resumed(shell(ui,"dumpsys activity activities")).contains(target),"allowed launch "+target);
                    shell(ui,"su 0 date -s "+close);
                    long end=SystemClock.uptimeMillis()+5000;
                    String top;
                    do {
                        Thread.sleep(150);
                        top=resumed(shell(ui,"dumpsys activity activities"));
                    } while(top.contains(target)&&SystemClock.uptimeMillis()<end);
                    check(!top.isEmpty()&&!top.contains(target),"active session exits at boundary "+target);
                }
            } finally {
                long restored=(wall+SystemClock.elapsedRealtime()-elapsed)/1000;
                shell(ui,"su 0 date -s @"+restored);
                shell(ui,"settings put global auto_time "+autoTime);
            }
            return;
        }
        String pkg=args.getString("package","tv.danmaku.bili");
        boolean expected=AppLimits.allowed(pkg,ZonedDateTime.now());
        shell(ui,"am start -W -n "+pkg+"/fixture.LimitActivity");
        long deadline=SystemClock.uptimeMillis()+5000;
        String top="";
        do {
            Thread.sleep(150);
            top=shell(ui,"dumpsys activity activities");
            if(!resumed(top).isEmpty() && resumed(top).contains(pkg)==expected) break;
        } while(SystemClock.uptimeMillis()<deadline);
        String resumed=resumed(top);
        check(!resumed.isEmpty() && resumed.contains(pkg)==expected,"foreground enforcement: "+resumed+" allowed="+expected);
        check(launchable(c,pkg),"ordinary phone icon remains visible");
        // Disabled limits must not affect launches even outside the window.
        AppLimits.setEnabled(c,false);
        shell(ui,"am start -W -n "+pkg+"/fixture.LimitActivity");
        Thread.sleep(1000);
        top=shell(ui,"dumpsys activity activities");
        check(!resumed(top).isEmpty() && resumed(top).contains(pkg),"disabled app stays open");
        AppLimits.setEnabled(c,true);
    }
    public void onStart() {
        Bundle result=new Bundle();
        try {
            Context c=getTargetContext();
            rules(c);
            if("owner".equals(args.getString("mode"))) owner(c);
            else if("accessibility".equals(args.getString("mode"))||"boundary".equals(args.getString("mode"))) accessibility(c);
            else {
                check(!AppLimits.canHide(c),"no special privileges");
                AppLimits.apply(c);
                check(!AppLimits.state(c).getBoolean("canHide"),"honest unsupported state");
            }
            result.putString("result","PASS app limits: "+args.getString("mode","ordinary")+"; all 1440 minute boundaries");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            result.putString("error",android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
