package org.matrix.bindertest;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

/**
 * Lives in a second process purely so the app's uid outlives the process holding the framework
 * binder. Killing the main process then leaves the uid alive, which is the case the delivery
 * bookkeeping has to survive.
 */
public final class KeepAliveProvider extends ContentProvider {
    @Override public boolean onCreate() {
        Log.i("BinderTestModule", "keepalive process up, pid=" + Process.myPid());
        return true;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) { return new Bundle(); }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
