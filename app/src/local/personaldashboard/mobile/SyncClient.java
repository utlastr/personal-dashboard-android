package local.personaldashboard.mobile;

import android.content.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class SyncClient {
    static String validate(String value) throws Exception {
        URI uri=new URI(value.trim());
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
            || !(uri.getScheme().equals("http")||uri.getScheme().equals("https"))
            || !(uri.getPath().isEmpty()||uri.getPath().equals("/"))) throw new IllegalArgumentException("请输入完整的服务根地址");
        if(uri.getScheme().equals("http")) {
            String host=uri.getHost();
            if(!host.equals("localhost")&&!host.matches("127\\.\\d+\\.\\d+\\.\\d+")&&!host.matches("10\\.\\d+\\.\\d+\\.\\d+")
                &&!host.matches("192\\.168\\.\\d+\\.\\d+")&&!host.matches("172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+"))
                throw new IllegalArgumentException("公网连接必须使用 HTTPS");
        }
        return value.trim().replaceAll("/+$","");
    }
    static JSONObject request(Context c,String path,JSONObject payload) throws Exception {
        String base=validate(Collector.prefs(c).getString("server",""));
        HttpURLConnection connection=(HttpURLConnection)new URL(base+path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
        connection.setRequestProperty("Authorization","Bearer "+Collector.prefs(c).getString("token",""));
        connection.setRequestProperty("Content-Type","application/json");
        try {
            if(payload!=null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                try(OutputStream stream=connection.getOutputStream()) { stream.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int code=connection.getResponseCode();
            if(code!=200) throw new IOException("服务返回 "+code);
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            try(InputStream input=connection.getInputStream()) {
                byte[] buffer=new byte[8192]; int length;
                while((length=input.read(buffer))!=-1) { output.write(buffer,0,length); if(output.size()>4*1024*1024) throw new IOException("响应过大"); }
            }
            return new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }
    static synchronized void sync(Context context) throws Exception {
        if(Collector.prefs(context).getString("server","").isEmpty()) return;
        try(Store store=new Store(context)) {
            JSONArray pending;
            do {
                pending=store.pending("synced",300);
                JSONObject response=request(context,"/mobile/v1/sync",new JSONObject().put("device",Collector.device(context))
                    .put("name",Collector.prefs(context).getString("name",android.os.Build.MODEL))
                    .put("role",Collector.prefs(context).getString("role","personal")).put("sessions",pending));
                if(response.getInt("accepted")!=pending.length()) throw new IOException("同步数量未确认");
                for(int i=0;i<pending.length();i++) store.marked(pending.getJSONObject(i).getString("id"),"synced");
            } while(pending.length()==300);
        }
        Collector.prefs(context).edit().putLong("synced",System.currentTimeMillis())
            .remove("shared").putString("error","").apply();
    }
}
