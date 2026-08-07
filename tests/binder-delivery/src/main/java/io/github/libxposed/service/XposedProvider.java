package io.github.libxposed.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

/**
 * The module side of the framework's binder handshake, reduced to what the handshake needs.
 *
 * The real class lives in libxposed-service; this reimplements only its contract so a test module
 * can be built without pulling that in: the framework forges a call with method "SendBinder" and a
 * Bundle carrying the binder, and reads a non-null reply as "the app took it".
 */
public final class XposedProvider extends ContentProvider {

    private static final String TAG = "BinderTestModule";

    /** Kept so the reference is live for the life of the process, as a real module's would be. */
    private static volatile IBinder sService;

    @Override
    public boolean onCreate() {
        Log.i(TAG, "provider created, pid=" + android.os.Process.myPid());
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"SendBinder".equals(method) || extras == null) return null;
        IBinder binder = extras.getBinder("binder");
        if (binder == null) {
            Log.w(TAG, "SendBinder with no binder in the extras");
            return new Bundle();
        }
        sService = binder;
        Log.i(TAG, "received framework binder: " + binder + " alive=" + binder.isBinderAlive());
        return new Bundle();
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
