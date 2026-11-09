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
                if(action==null) throw new IllegalStateException("系统未提供安装确认入口");
                Collector.prefs(context).edit().putString("updateAction",action.toUri(0)).commit();
                Updates.save(context,state.put("needsAction",true).put("status","新版已下载；安卓系统要求确认安装"));
            } else {
                Collector.prefs(context).edit().remove("updateSession").remove("updateAction").commit();
                if(status==PackageInstaller.STATUS_SUCCESS) Updates.replaced(context);
                else Updates.save(context,state.put("needsAction",false).put("status","系统未完成安装："
                    +intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)+"；可点安装重试"));
            }
        } catch(Exception error) { Updates.status(context,error.getMessage()); }
    }
}
