package local.personaldashboard.mobile;

import android.content.*;
import android.content.pm.*;
import android.app.PendingIntent;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.json.*;

final class Updates {
    static final int VERSION=5;
    static final String NAME="0.1.4";
    static final String REPOSITORY="utlastr/personal-dashboard-android";
    static final String RELEASES="https://github.com/"+REPOSITORY+"/releases/";
    static final String API="https://api.github.com/repos/"+REPOSITORY+"/releases/";
    static final long DAY=24*60*60*1000L;
    private static volatile boolean busy;

    static JSONObject state(Context context) {
        try {
            JSONObject value=new JSONObject(Collector.prefs(context).getString("updateState","{}"));
            if(value.optInt("versionCode")<=VERSION) {
                value.remove("available"); value.remove("ready");
            }
            return value.put("busy",busy).put("source","GitHub")
                .put("checked",Collector.prefs(context).getLong("updateChecked",0));
        } catch(Exception error) { return new JSONObject(); }
    }
    static void save(Context context,JSONObject state) {
        Collector.prefs(context).edit().putString("updateState",state.toString()).commit();
    }
    static void status(Context context,String message) {
        try { save(context,state(context).put("status",message)); } catch(JSONException ignored) {}
    }
    static boolean due(long now,long checked,long attempted) {
        return (checked==0 || now<checked || now-checked>=DAY)
            && (attempted==0 || now<attempted || now-attempted>=60*60*1000L);
    }
    static JSONObject validateManifest(JSONObject manifest) throws Exception {
        if(!"local.personaldashboard.mobile".equals(manifest.getString("packageName"))
            || manifest.getInt("versionCode")<1 || manifest.getString("versionName").isEmpty()
            || !manifest.getString("sha256").matches("[0-9a-fA-F]{64}")
            || !manifest.getString("apk").matches("PersonalDashboard-[0-9A-Za-z._-]+\\.apk"))
            throw new IOException("GitHub 更新清单格式无效");
        return manifest;
    }
    static HttpURLConnection connect(String address) throws Exception {
        // GitHub redirects release assets to a signed HTTPS download host.
        for(int redirects=0;redirects<6;redirects++) {
            URL url=new URL(address);
            String host=url.getHost();
            if(!"https".equals(url.getProtocol()) || url.getUserInfo()!=null
                || (url.getPort()!=-1 && url.getPort()!=443)
                || !(host.equals("github.com") || host.equals("api.github.com") || host.equals("release-assets.githubusercontent.com")
                    || host.equals("objects.githubusercontent.com") || host.equals("releases.githubusercontent.com")))
                throw new IOException("更新下载地址不受信任");
            if(host.equals("github.com") && !url.getPath().startsWith("/"+REPOSITORY+"/releases/"))
                throw new IOException("更新仓库不匹配");
            if(host.equals("api.github.com") && !url.getPath().startsWith("/repos/"+REPOSITORY+"/releases/"))
                throw new IOException("更新仓库不匹配");
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent","PersonalDashboard/"+NAME);
            connection.setRequestProperty("Cache-Control","no-cache");
            connection.setRequestProperty("Accept",url.getPath().contains("/releases/assets/")?"application/octet-stream":"application/vnd.github+json");
            int code=connection.getResponseCode();
            if(code==301 || code==302 || code==303 || code==307 || code==308) {
                String location=connection.getHeaderField("Location"); connection.disconnect();
                if(location==null) throw new IOException("更新重定向地址为空");
                address=new URL(url,location).toString(); continue;
            }
            if(code!=200) { connection.disconnect(); throw new IOException("GitHub HTTP "+code); }
            return connection;
        }
        throw new IOException("更新重定向次数过多");
    }
    static JSONObject readJson(String address) throws Exception {
        HttpURLConnection connection=connect(address);
        try(InputStream input=connection.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int count;
            while((count=input.read(buffer))!=-1) {
                bytes.write(buffer,0,count);
                if(bytes.size()>1024*1024) throw new IOException("更新清单过大");
            }
            return new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8).replace("\uFEFF",""));
        } finally { connection.disconnect(); }
    }
    static JSONObject check(Context context) throws Exception {
        JSONObject release=readJson(API+"latest");
        if(release.optBoolean("draft") || release.optBoolean("prerelease")) throw new IOException("当前没有正式更新");
        JSONArray assets=release.getJSONArray("assets");
        String manifestUrl="";
        for(int i=0;i<assets.length();i++) {
            JSONObject asset=assets.getJSONObject(i);
            if("update-manifest.json".equals(asset.getString("name"))) manifestUrl=API+"assets/"+asset.getLong("id");
        }
        if(manifestUrl.isEmpty()) throw new IOException("GitHub 发布缺少更新清单");
        JSONObject manifest=validateManifest(readJson(manifestUrl));
        if(!("v"+manifest.getString("versionName")).equals(release.getString("tag_name")))
            throw new IOException("GitHub 发布标签与清单不匹配");
        for(int i=0;i<assets.length();i++) {
            JSONObject asset=assets.getJSONObject(i);
            if(manifest.getString("apk").equals(asset.getString("name")))
                manifest.put("assetUrl",API+"assets/"+asset.getLong("id"));
        }
        if(!manifest.has("assetUrl")) throw new IOException("GitHub 发布缺少安装包");
        if(manifest.getInt("versionCode")<=VERSION)
            return manifest.put("status","已是最新版本 "+NAME).put("available",false);
        return manifest.put("available",true).put("status","发现新版本 "+manifest.getString("versionName"));
    }
    static boolean run(Context context,boolean force) {
        synchronized(Updates.class) { if(busy) return true; busy=true; }
        try {
            SharedPreferences prefs=Collector.prefs(context);
            long now=System.currentTimeMillis();
            if(!force && !due(now,prefs.getLong("updateChecked",0),prefs.getLong("updateAttempted",0))) return true;
            prefs.edit().putLong("updateAttempted",now).commit();
            status(context,"正在检查 GitHub 版本…");
            JSONObject manifest=check(context);
            if(manifest.optBoolean("available") && state(context).optInt("versionCode")==manifest.getInt("versionCode")) {
                int sessionId=prefs.getInt("updateSession",-1);
                if(sessionId>=0 && context.getPackageManager().getPackageInstaller().getSessionInfo(sessionId)!=null) {
                    status(context,state(context).optBoolean("needsAction")?"新版已下载；安卓系统要求确认安装":"正在安装更新…");
                    prefs.edit().putLong("updateChecked",now).commit();
                    return true;
                }
            }
            save(context,manifest);
            prefs.edit().putLong("updateChecked",now).commit();
            if(manifest.optBoolean("available")) {
                status(context,"正在自动下载 "+manifest.getString("versionName")+"…");
                download(context,manifest);
                save(context,manifest.put("ready",true).put("status","安装包已校验，准备更新"));
                requestInstall(context);
            }
            return true;
        } catch(Exception error) {
            status(context,"更新失败："+error.getMessage()+"；稍后自动重试，也可手动检查");
            Collector.prefs(context).edit().putLong("updateChecked",0).commit();
            return false;
        } finally { synchronized(Updates.class) { busy=false; } }
    }
    static File file(Context context) { return new File(context.getFilesDir(),"dashboard-update.apk"); }
    static File download(Context context,JSONObject manifest) throws Exception {
        validateManifest(manifest);
        String tag="v"+manifest.getString("versionName");
        if(!tag.matches("v[0-9A-Za-z._-]+")) throw new IOException("更新版本名称无效");
        String address=manifest.getString("assetUrl");
        if(!address.startsWith(API+"assets/") || !address.substring((API+"assets/").length()).matches("[0-9]+"))
            throw new IOException("更新资源地址无效");
        HttpURLConnection connection=connect(address);
        try { return download(context,manifest,connection); } finally { connection.disconnect(); }
    }
    static File download(Context context,JSONObject manifest,HttpURLConnection connection) throws Exception {
        File partial=new File(context.getFilesDir(),"dashboard-update.part");
        long total=0;
        try(InputStream input=connection.getInputStream(); OutputStream target=new FileOutputStream(partial)) {
            byte[] buffer=new byte[65536]; int count;
            while((count=input.read(buffer))!=-1) {
                if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                total+=count; if(total>400*1024*1024L) throw new IOException("安装包过大");
                target.write(buffer,0,count);
            }
        }
        verify(context,manifest,partial);
        File output=file(context);
        if(!partial.renameTo(output)) throw new IOException("无法保存已校验的安装包");
        return output;
    }
    static void verify(Context context,JSONObject manifest,File apk) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(apk)) {
            byte[] buffer=new byte[65536]; int count;
            while((count=input.read(buffer))!=-1) digest.update(buffer,0,count);
        }
        StringBuilder hash=new StringBuilder();
        for(byte b:digest.digest()) hash.append(String.format(Locale.ROOT,"%02x",b&255));
        if(!hash.toString().equalsIgnoreCase(manifest.getString("sha256"))) throw new IOException("安装包完整性校验失败");
        PackageManager pm=context.getPackageManager();
        PackageInfo next=pm.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo current=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(next==null || !context.getPackageName().equals(next.packageName)
            || next.getLongVersionCode()!=manifest.getLong("versionCode")
            || next.signingInfo==null || current.signingInfo==null
            || !Arrays.equals(next.signingInfo.getApkContentsSigners(),current.signingInfo.getApkContentsSigners()))
            throw new IOException("更新包名称、版本或签名不匹配");
    }
    static void requestInstall(Context context) throws Exception {
        JSONObject manifest=state(context);
        if(!manifest.optBoolean("ready") || !manifest.optBoolean("available")) return;
        if(!context.getPackageManager().canRequestPackageInstalls()) {
            save(context,manifest.put("status","新版已下载；需要允许本应用安装更新").put("permissionRequired",true));
            return;
        }
        PackageInstaller installer=context.getPackageManager().getPackageInstaller();
        SharedPreferences prefs=Collector.prefs(context);
        int existing=prefs.getInt("updateSession",-1);
        if(existing>=0 && installer.getSessionInfo(existing)!=null) return;
        verify(context,manifest,file(context));
        PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName()); params.setSize(file(context).length());
        if(Build.VERSION.SDK_INT>=31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id=installer.createSession(params);
        boolean committed=false;
        try(PackageInstaller.Session session=installer.openSession(id)) {
            try(InputStream input=new FileInputStream(file(context)); OutputStream target=session.openWrite("base.apk",0,file(context).length())) {
                byte[] buffer=new byte[65536]; int count;
                while((count=input.read(buffer))!=-1) target.write(buffer,0,count);
                session.fsync(target);
            }
            Intent callback=new Intent(context,UpdateReceiver.class).setAction("local.personaldashboard.mobile.UPDATE_RESULT");
            PendingIntent pending=PendingIntent.getBroadcast(context,id,callback,
                PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0));
            prefs.edit().putInt("updateSession",id).remove("updateAction").commit();
            save(context,manifest.put("status","正在安装更新…").put("permissionRequired",false).put("needsAction",false));
            session.commit(pending.getIntentSender()); committed=true;
        } finally { if(!committed) { installer.abandonSession(id); prefs.edit().remove("updateSession").commit(); } }
    }
    static void replaced(Context context) {
        Collector.prefs(context).edit().remove("updateSession").remove("updateAction").remove("updateState").commit();
        status(context,"已更新至 "+NAME);
    }
}
