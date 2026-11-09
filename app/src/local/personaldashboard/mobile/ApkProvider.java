package local.personaldashboard.mobile;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

public final class ApkProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        if("/sessions.json".equals(uri.getPath())) return new File(getContext().getCacheDir(),"dashboard-export.json");
        if(!"/update.apk".equals(uri.getPath())) throw new FileNotFoundException();
        return Updates.file(getContext());
    }
    public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!mode.equals("r")) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public String getType(Uri uri) { return "/sessions.json".equals(uri.getPath())?"application/json":"application/vnd.android.package-archive"; }
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        try {
            File apk=file(uri);
            MatrixCursor result=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
            result.addRow(new Object[]{"/sessions.json".equals(uri.getPath())?"PersonalDashboard-sessions.json":"PersonalDashboard.apk",apk.length()}); return result;
        } catch(IOException error) { return null; }
    }
    public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    public int update(Uri uri,ContentValues values,String where,String[] args) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri,String where,String[] args) { throw new UnsupportedOperationException(); }
}
