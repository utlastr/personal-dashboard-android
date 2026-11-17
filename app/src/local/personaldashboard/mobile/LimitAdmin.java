package local.personaldashboard.mobile;

import android.app.admin.DeviceAdminReceiver;
import android.content.*;

public final class LimitAdmin extends DeviceAdminReceiver {
    public void onEnabled(Context context,Intent intent) { LimitJob.schedule(context); AppLimits.apply(context); }
    public void onProfileProvisioningComplete(Context context,Intent intent) {
        LimitJob.schedule(context); AppLimits.apply(context);
    }
    public void onDisabled(Context context,Intent intent) { AppLimits.apply(context); }
}
