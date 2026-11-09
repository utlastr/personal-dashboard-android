package local.personaldashboard.mobile;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.webkit.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;
import net.activitywatch.android.RustInterface;

public final class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService updater=Executors.newSingleThreadExecutor();
    private final Handler timer=new Handler(Looper.getMainLooper());
    private volatile String operation="";
    private static volatile boolean started;
    private static final String ORIGIN="http://127.0.0.1:5667";
    private final Runnable periodic=new Runnable() {
        public void run() { refresh(false); checkUpdate(false); timer.postDelayed(this,60000); }
    };
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(244,246,248));
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        LinearLayout shell=new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.rgb(244,246,248)); shell.setFitsSystemWindows(true);
        web=new WebView(this); shell.addView(web,new LinearLayout.LayoutParams(-1,0,1)); setContentView(shell);
        web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false); web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new Bridge(),"Dashboard");
        web.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                return !trusted(request.getUrl());
            }
            public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request) {
                Uri uri=request.getUrl();
                if(!trusted(uri)) return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",null,new ByteArrayInputStream(new byte[0]));
                if(uri.getPath().startsWith("/api/")) return null;
                if(uri.getPath().equals("/mobile-icon")) {
                    try {
                        android.graphics.drawable.Drawable icon=getPackageManager().getApplicationIcon(uri.getQueryParameter("package"));
                        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(72,72,android.graphics.Bitmap.Config.ARGB_8888);
                        icon.setBounds(0,0,72,72); icon.draw(new android.graphics.Canvas(bitmap));
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes); bitmap.recycle();
                        return new WebResourceResponse("image/png",null,new ByteArrayInputStream(bytes.toByteArray()));
                    } catch(Exception ignored) {}
                }
                String path=uri.getPath().equals("/")?"index.html":uri.getPath().substring(1);
                if(path.contains("..")) return null;
                try {
                    String mime=URLConnection.guessContentTypeFromName(path);
                    if(path.endsWith(".js")) mime="application/javascript";
                    if(path.endsWith(".css")) mime="text/css";
                    if(path.endsWith(".woff2")) mime="font/woff2";
                    if(path.endsWith(".json")) mime="application/json";
                    if(mime==null) mime="application/octet-stream";
                    return new WebResourceResponse(mime,"UTF-8",getAssets().open("dashboard/"+path));
                } catch(IOException error) {
                    return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",null,new ByteArrayInputStream(new byte[0]));
                }
            }
        });
        web.loadUrl(ORIGIN+"/mobile.html");
        CollectJob.schedule(this);
        UpdateJob.schedule(this);
        checkUpdate(false);
        io.submit(()->{
            try {
                RustInterface rust=RustInterface.get(this);
                synchronized(MainActivity.class) {
                    if(!started) {
                        started=true;
                        Thread server=new Thread(()->{
                            try {
                                try(ServerSocket probe=new ServerSocket()) { probe.bind(new InetSocketAddress("127.0.0.1",5667)); }
                                rust.startServer(5667);
                            } catch(Throwable error) { operation="本机数据服务启动失败："+error.getMessage(); }
                            finally { started=false; }
                        },"ActivityWatch-server"); server.setDaemon(true); server.start();
                    }
                }
                refresh(false);
            } catch(Throwable error) { operation="数据引擎启动失败："+error.getMessage(); }
        });
    }
    private boolean trusted(Uri uri) {
        return "http".equals(uri.getScheme())&&"127.0.0.1".equals(uri.getHost())&&uri.getPort()==5667;
    }
    public void onResume() {
        super.onResume(); timer.postDelayed(periodic,1000);
        updater.submit(()->{
            try {
                if(Updates.state(this).optBoolean("permissionRequired") && getPackageManager().canRequestPackageInstalls())
                    Updates.requestInstall(this);
            } catch(Exception error) { Updates.status(this,error.getMessage()); }
        });
    }
    public void onPause() { timer.removeCallbacks(periodic); super.onPause(); }
    public void onDestroy() {
        timer.removeCallbacksAndMessages(null); io.shutdownNow(); updater.shutdown();
        if(web!=null) { web.removeJavascriptInterface("Dashboard"); web.destroy(); }
        super.onDestroy();
    }
    public void onBackPressed() {
        if(web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
    private void refresh(boolean checkUpdate) {
        io.submit(()->{
            try {
                Collector.collect(this); SyncClient.sync(this);
                operation="";
            } catch(Throwable error) { operation=error.getMessage()==null?error.toString():error.getMessage(); }
        });
        if(checkUpdate) checkUpdate(true);
    }
    private void checkUpdate(boolean force) { updater.submit(()->Updates.run(this,force)); }
    private final class Bridge {
        @JavascriptInterface public String state() {
            try(Store store=new Store(MainActivity.this)) {
                SharedPreferences p=Collector.prefs(MainActivity.this);
                return store.summary().put("permission",Collector.allowed(MainActivity.this))
                    .put("device",Collector.device(MainActivity.this)).put("name",p.getString("name",Build.MODEL))
                    .put("role",p.getString("role","personal")).put("server",p.getString("server",""))
                    .put("paired",!p.getString("token","").isEmpty()).put("version",Updates.NAME)
                    .put("synced",p.getLong("synced",0)).put("collected",p.getLong("collected",0))
                    .put("shared",new JSONObject(p.getString("shared","{}")))
                    .put("message",operation).put("update",Updates.state(MainActivity.this)).toString();
            } catch(Exception error) { return "{\"message\":"+JSONObject.quote(error.toString())+"}"; }
        }
        @JavascriptInterface public void permission() {
            runOnUiThread(()->startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        }
        @JavascriptInterface public void refresh() { MainActivity.this.refresh(false); }
        @JavascriptInterface public void checkUpdate() { MainActivity.this.checkUpdate(true); }
        @JavascriptInterface public void mode(String purpose) {
            try(Store store=new Store(MainActivity.this)) { store.mode(purpose); MainActivity.this.refresh(false); }
            catch(Exception error) { operation=error.getMessage(); }
        }
        @JavascriptInterface public String save(String data) {
            try {
                JSONObject config=new JSONObject(data);
                String role=config.getString("role");
                if(!role.equals("work")&&!role.equals("personal")) throw new IllegalArgumentException("设备用途无效");
                String server=config.optString("server").trim();
                if(!server.isEmpty()) server=SyncClient.validate(server);
                SharedPreferences p=Collector.prefs(MainActivity.this);
                String token=config.optString("token").trim();
                if(token.isEmpty() && !server.equals(p.getString("server",""))) throw new IllegalArgumentException("更换服务地址时需要重新输入配对码");
                SharedPreferences.Editor editor=p.edit().putString("role",role).putString("name",config.getString("name"))
                    .putString("server",server);
                if(!token.isEmpty()) editor.putString("token",token);
                editor.commit(); MainActivity.this.refresh(true); return "";
            } catch(Exception error) { return error.getMessage(); }
        }
        @JavascriptInterface public void install() {
            updater.submit(()->{
                try {
                    if(!Updates.state(MainActivity.this).optBoolean("ready")) { Updates.run(MainActivity.this,true); return; }
                    if(!getPackageManager().canRequestPackageInstalls()) {
                        runOnUiThread(()->startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()))));
                        return;
                    }
                    String pending=Collector.prefs(MainActivity.this).getString("updateAction","");
                    if(!pending.isEmpty()) {
                        Intent action=Intent.parseUri(pending,0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        runOnUiThread(()->{
                            try { startActivity(action); }
                            catch(Exception error) { Updates.status(MainActivity.this,error.getMessage()); }
                        });
                    } else Updates.requestInstall(MainActivity.this);
                } catch(Exception error) { Updates.status(MainActivity.this,error.getMessage()); }
            });
        }
        @JavascriptInterface public void export() {
            io.submit(()->{
                try(Store store=new Store(MainActivity.this)) {
                    JSONArray sessions=new JSONArray();
                    try(android.database.Cursor cursor=store.getReadableDatabase().rawQuery("SELECT id,start,end,pkg,app,activity,purpose,role FROM sessions ORDER BY start",null)) {
                        while(cursor.moveToNext()) {
                            JSONObject row=new JSONObject();
                            for(String key:new String[]{"id","pkg","app","activity","purpose","role"}) row.put(key,cursor.getString(cursor.getColumnIndexOrThrow(key)));
                            row.put("start",cursor.getLong(1)).put("end",cursor.getLong(2)); sessions.put(row);
                        }
                    }
                    String json=new JSONObject().put("device",Collector.device(MainActivity.this)).put("sessions",sessions).toString();
                    try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(getCacheDir(),"dashboard-export.json")),StandardCharsets.UTF_8)) {
                        writer.write(json);
                    }
                    runOnUiThread(()->{
                        Uri uri=Uri.parse("content://local.personaldashboard.mobile.updates/sessions.json");
                        Intent share=new Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM,uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        share.setClipData(ClipData.newRawUri("Personal Dashboard",uri));
                        startActivity(Intent.createChooser(share,"导出使用记录"));
                    });
                } catch(Exception error) { operation=error.getMessage(); }
            });
        }
    }
}
