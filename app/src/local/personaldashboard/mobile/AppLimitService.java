package local.personaldashboard.mobile;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.*;
import android.os.*;
import android.view.accessibility.*;

public final class AppLimitService extends AccessibilityService {
    private static volatile boolean active;
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final Runnable check=this::enforce;
    static boolean connected() { return active; }
    public void onServiceConnected() {
        active=true;
        enforce();
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
        if(!AppLimits.enabled(this) || ((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()) return false;
        return AppLimits.restricted(pkg) && !AppLimits.allowed(pkg,java.time.ZonedDateTime.now())
            && performGlobalAction(GLOBAL_ACTION_HOME);
    }
    private void enforce() {
        timer.removeCallbacks(check);
        if(!AppLimits.enabled(this) || ((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()) return;
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
    public boolean onUnbind(Intent intent) { active=false; timer.removeCallbacksAndMessages(null); return super.onUnbind(intent); }
    public void onDestroy() { active=false; timer.removeCallbacksAndMessages(null); super.onDestroy(); }
}
