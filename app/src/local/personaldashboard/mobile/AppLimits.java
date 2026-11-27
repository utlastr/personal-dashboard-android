package local.personaldashboard.mobile;

import android.app.*;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import java.time.*;
import java.util.*;
import org.json.*;

final class AppLimits {
    static final String[] PACKAGES={"tv.danmaku.bili","com.bilibili.app.in","com.bilibili.app.blue",
        "tv.danmaku.bilibilihd","com.ss.android.ugc.aweme","com.ss.android.ugc.aweme.lite"};
    private static final int DOUYIN_START=16*60+30, DOUYIN_END=18*60;
    private static final int BILIBILI_START=22*60, BILIBILI_END=23*60;
    private static final int[] BOUNDARIES={DOUYIN_START,DOUYIN_END,BILIBILI_START,BILIBILI_END};
    static boolean restricted(String pkg) { return Arrays.asList(PACKAGES).contains(pkg); }
    static boolean allowed(String pkg,ZonedDateTime now) {
        int minute=now.getHour()*60+now.getMinute();
        if(!restricted(pkg)) return true;
        return pkg.startsWith("com.ss.android.") ? minute>=DOUYIN_START && minute<DOUYIN_END
            : minute>=BILIBILI_START && minute<BILIBILI_END;
    }
    static long nextBoundary(ZonedDateTime now) {
        for(int day=0;day<2;day++) for(int minute:BOUNDARIES) {
            ZonedDateTime time=now.toLocalDate().plusDays(day).atTime(minute/60,minute%60).atZone(now.getZone());
            if(time.isAfter(now)) return time.toInstant().toEpochMilli();
        }
        throw new IllegalStateException("No next boundary");
    }
    static boolean enabled(Context c) { return Collector.prefs(c).getBoolean("limitsEnabled",true); }
    static ComponentName admin(Context c) { return new ComponentName(c,LimitAdmin.class); }
    static DevicePolicyManager policy(Context c) { return (DevicePolicyManager)c.getSystemService(Context.DEVICE_POLICY_SERVICE); }
    static boolean owner(Context c) {
        DevicePolicyManager p=policy(c);
        return p!=null && (p.isDeviceOwnerApp(c.getPackageName()) || p.isProfileOwnerApp(c.getPackageName()));
    }
    static boolean canHide(Context c) {
        DevicePolicyManager p=policy(c);
        if(p==null) return false;
        if(owner(c)) return true;
        try { return p.getDelegatedScopes(null,c.getPackageName()).contains(DevicePolicyManager.DELEGATION_PACKAGE_ACCESS); }
        catch(SecurityException ignored) { return false; }
    }
    static boolean installed(Context c,String pkg) {
        try {
            PackageInfo info=c.getPackageManager().getPackageInfo(pkg,PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return info.applicationInfo!=null && (info.applicationInfo.flags&ApplicationInfo.FLAG_INSTALLED)!=0;
        } catch(PackageManager.NameNotFoundException ignored) { return false; }
    }
    static synchronized void apply(Context c) {
        apply(c,ZonedDateTime.now());
    }
    static synchronized void apply(Context c,ZonedDateTime now) {
        SharedPreferences prefs=Collector.prefs(c);
        Set<String> managed=new HashSet<>(prefs.getStringSet("limitHidden",Collections.emptySet()));
        String error="";
        if(canHide(c)) {
            DevicePolicyManager p=policy(c);
            ComponentName admin=owner(c)?admin(c):null;
            for(String pkg:PACKAGES) {
                boolean hide=enabled(c)&&!allowed(pkg,now);
                if(!installed(c,pkg) && !managed.contains(pkg)) continue;
                try {
                    boolean hidden=p.isApplicationHidden(admin,pkg);
                    // Never unhide restrictions owned by another administrator.
                    if(!hide && !managed.contains(pkg)) continue;
                    if(hide && hidden && !managed.contains(pkg)) continue;
                    boolean tracked=managed.contains(pkg);
                    if(hide) {
                        managed.add(pkg);
                        prefs.edit().putStringSet("limitHidden",new HashSet<>(managed)).commit();
                    }
                    if(hidden!=hide && !p.setApplicationHidden(admin,pkg,hide)) {
                        if(!tracked) managed.remove(pkg);
                        throw new IllegalStateException("Android denied the visibility change for "+pkg);
                    }
                    if(!hide) managed.remove(pkg);
                    prefs.edit().putStringSet("limitHidden",new HashSet<>(managed)).commit();
                } catch(Exception failure) { error=failure.getMessage()==null?failure.toString():failure.getMessage(); }
            }
        } else if(!managed.isEmpty()) error="Restore device management access to show apps hidden by Dashboard.";
        prefs.edit().putString("limitError",error).putLong("limitApplied",System.currentTimeMillis()).apply();
        schedule(c);
    }
    static synchronized boolean setEnabled(Context c,boolean enabled) {
        if(!saveEnabled(c,enabled)) return false;
        apply(c);
        // A failed restore must stay visible instead of silently reporting disabled.
        return Collector.prefs(c).getString("limitError","").isEmpty();
    }
    static boolean saveEnabled(Context c,boolean enabled) {
        return Collector.prefs(c).edit().putBoolean("limitsEnabled",enabled).commit();
    }
    static void schedule(Context c) {
        AlarmManager alarms=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pending=PendingIntent.getBroadcast(c,8103,new Intent(c,LimitReceiver.class),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        if(!canHide(c) || (!enabled(c) && Collector.prefs(c).getStringSet("limitHidden",Collections.emptySet()).isEmpty())) {
            alarms.cancel(pending); return;
        }
        long next=nextBoundary(ZonedDateTime.now());
        try {
            if(Build.VERSION.SDK_INT<31 || alarms.canScheduleExactAlarms())
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending);
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending);
        } catch(SecurityException denied) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending); }
    }
    static JSONObject state(Context c) throws JSONException {
        boolean hiding=canHide(c),access=AppLimitService.connected();
        ComponentName service=new ComponentName(c,AppLimitService.class);
        String services=android.provider.Settings.Secure.getString(c.getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean granted=android.provider.Settings.Secure.getInt(c.getContentResolver(),
            android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,0)==1 && services!=null && Arrays.stream(services.split(":"))
            .anyMatch(id->service.equals(ComponentName.unflattenFromString(id)));
        AlarmManager alarms=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        return new JSONObject().put("enabled",enabled(c)).put("canHide",hiding).put("accessibility",access)
            .put("accessibilityGranted",granted)
            .put("exact",Build.VERSION.SDK_INT<31 || alarms.canScheduleExactAlarms())
            .put("error",Collector.prefs(c).getString("limitError",""))
            .put("hidden",new JSONArray(Collector.prefs(c).getStringSet("limitHidden",Collections.emptySet())))
            .put("bilibiliAllowed",allowed("tv.danmaku.bili",ZonedDateTime.now()))
            .put("douyinAllowed",allowed("com.ss.android.ugc.aweme",ZonedDateTime.now()));
    }
}
