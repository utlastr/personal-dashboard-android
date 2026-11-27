package local.personaldashboard.mobile;

import android.content.*;
import android.content.pm.PackageInstaller;
import org.json.*;

public final class UpdateReceiver extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent) {
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        try {
            JSONObject state=Updates.state(context);
            if(status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent action=intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if(action==null) throw new IllegalStateException("Android did not provide an installation confirmation screen.");
                Collector.prefs(context).edit().putString("updateAction",action.toUri(0)).commit();
                Updates.save(context,state.put("needsAction",true).put("status","Update downloaded. Android requires installation confirmation."));
            } else {
                Collector.prefs(context).edit().remove("updateSession").remove("updateAction").commit();
                if(status==PackageInstaller.STATUS_SUCCESS) Updates.replaced(context);
                else Updates.save(context,state.put("needsAction",false).put("status","Installation failed. Tap Install update to retry."
                    +" Android status: "+status));
            }
        } catch(Exception error) { Updates.status(context,error.getMessage()); }
    }
}
