package local.personaldashboard.mobile;

import android.content.*;

public final class LimitReceiver extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent) {
        PendingResult result=goAsync();
        new Thread(()->{
            try { AppLimits.apply(context); LimitJob.schedule(context); }
            finally { result.finish(); }
        },"Dashboard-app-limits").start();
    }
}
