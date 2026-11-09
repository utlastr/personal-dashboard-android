// MPL-2.0. JNI declarations adapted from ActivityWatch/aw-android v0.14.3.
package net.activitywatch.android;

import android.content.Context;
import android.system.Os;

public final class RustInterface {
    private static RustInterface instance;
    public static synchronized RustInterface get(Context context) throws Exception {
        if (instance == null) {
            Os.setenv("SQLITE_TMPDIR", context.getCacheDir().getAbsolutePath(), true);
            System.loadLibrary("aw_server");
            RustInterface ready = new RustInterface();
            ready.initialize();
            ready.setDataDir(context.getFilesDir().getAbsolutePath());
            ready.setVersionOverride("Personal Dashboard "+context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName+" (rust)");
            instance = ready;
        }
        return instance;
    }
    private native void initialize();
    private native void setDataDir(String path);
    private native void setVersionOverride(String version);
    public native void startServer(int port);
    public native String getBuckets();
    public native String createBucket(String bucket);
    public native String getEvents(String bucket, int limit);
    public native String heartbeat(String bucket, String event, double pulse);
    public native String query(String query, String timeperiods);
}
