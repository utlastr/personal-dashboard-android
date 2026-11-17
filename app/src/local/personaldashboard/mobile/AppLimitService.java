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
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.getEventType()==AccessibilityEvent.TYPE_WINDOWS_CHANGED) enforce();
    }
    private void enforce() {
        timer.removeCallbacks(check);
        if(!AppLimits.enabled(this) || ((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()) return;
        try {
            for(AccessibilityWindowInfo window:getWindows()) {
                if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION || (!window.isFocused()&&!window.isActive())) continue;
                AccessibilityNodeInfo root=window.getRoot();
                if(root==null) continue;
                String pkg;
                try { pkg=root.getPackageName()==null?"":root.getPackageName().toString(); }
                finally { root.recycle(); }
                if(!AppLimits.restricted(pkg)) continue;
                if(!AppLimits.allowed(pkg,java.time.ZonedDateTime.now())) performGlobalAction(GLOBAL_ACTION_HOME);
                // Keep the boundary effective even when no new window event arrives.
                timer.postDelayed(check,1000);
                return;
            }
        } catch(RuntimeException ignored) { timer.postDelayed(check,1000); }
    }
    public void onInterrupt() {}
    public boolean onUnbind(Intent intent) { active=false; timer.removeCallbacksAndMessages(null); return super.onUnbind(intent); }
    public void onDestroy() { active=false; timer.removeCallbacksAndMessages(null); super.onDestroy(); }
}
