package local.personaldashboard.mobile;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.KeyguardManager;
import android.content.*;
import android.os.*;
import android.view.accessibility.*;

public final class AppLimitService extends AccessibilityService {
    private static volatile boolean active;
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final Runnable check=this::enforce;
    private boolean listening;
    private static final String CHANNEL="app_limits";
    private final BroadcastReceiver wake=new BroadcastReceiver() {
        public void onReceive(Context context,Intent intent) { enforce(); }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener preference=(prefs,key)->{
        if("limitsEnabled".equals(key) && active) {
            timer.removeCallbacks(check);
            timer.post(this::updateForeground);
            timer.post(check);
        }
    };
    static boolean connected() { return active; }
    public void onServiceConnected() {
        if(!listening) {
            IntentFilter filter=new IntentFilter(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            if(Build.VERSION.SDK_INT>=33) registerReceiver(wake,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(wake,filter);
            Collector.prefs(this).registerOnSharedPreferenceChangeListener(preference);
            listening=true;
        }
        active=true;
        updateForeground();
        enforce();
    }
    private void updateForeground() {
        if(!active || !AppLimits.enabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            return;
        }
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"App Limits",NotificationManager.IMPORTANCE_LOW));
        PendingIntent dashboard=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Personal Dashboard App Limits")
            .setContentText("Daily app limits are active.")
            .setContentIntent(dashboard).setOngoing(true).build();
        try { startForeground(8104,notification); }
        catch(IllegalStateException | SecurityException error) {
            android.util.Log.w("DashboardAppLimits","Could not promote accessibility service",error);
        }
    }
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // A launcher transition can report the previous window list. The new
        // window-state event identifies the app even before its root is ready.
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.getPackageName()!=null) block(event.getPackageName().toString());
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.getEventType()==AccessibilityEvent.TYPE_WINDOWS_CHANGED) enforce();
    }
    private boolean block(String pkg) {
        if(!ready()) return false;
        return AppLimits.restricted(pkg) && !AppLimits.allowed(pkg,java.time.ZonedDateTime.now())
            && performGlobalAction(GLOBAL_ACTION_HOME);
    }
    private boolean ready() {
        return active && AppLimits.enabled(this)
            && ((PowerManager)getSystemService(POWER_SERVICE)).isInteractive()
            && !((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked();
    }
    private void enforce() {
        timer.removeCallbacks(check);
        if(!ready()) return;
        try {
            AccessibilityNodeInfo activeRoot=getRootInActiveWindow();
            if(activeRoot!=null) {
                try { if(activeRoot.getPackageName()!=null) block(activeRoot.getPackageName().toString()); }
                finally { activeRoot.recycle(); }
            }
            for(AccessibilityWindowInfo window:getWindows()) {
                if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION || (!window.isFocused()&&!window.isActive())) continue;
                AccessibilityNodeInfo root=window.getRoot();
                if(root==null) continue;
                String pkg;
                try { pkg=root.getPackageName()==null?"":root.getPackageName().toString(); }
                finally { root.recycle(); }
                block(pkg);
            }
        } catch(RuntimeException ignored) {
            // Retry after transient OEM window-query failures.
        } finally {
            // Keep polling through launcher animations and missing window roots.
            timer.postDelayed(check,750);
        }
    }
    public void onInterrupt() {}
    private void stopListening() {
        active=false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        timer.removeCallbacksAndMessages(null);
        if(listening) {
            Collector.prefs(this).unregisterOnSharedPreferenceChangeListener(preference);
            unregisterReceiver(wake);
            listening=false;
        }
    }
    public boolean onUnbind(Intent intent) { stopListening(); return super.onUnbind(intent); }
    public void onDestroy() { stopListening(); super.onDestroy(); }
}
