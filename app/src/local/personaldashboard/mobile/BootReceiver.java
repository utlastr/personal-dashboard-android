package local.personaldashboard.mobile;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent) {
        if(Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) Updates.replaced(context);
        CollectJob.schedule(context); UpdateJob.schedule(context);
    }
}
