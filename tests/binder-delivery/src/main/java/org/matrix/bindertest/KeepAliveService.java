package org.matrix.bindertest;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

/**
 * Runs in a second process so the app's uid outlives the process that holds the framework binder.
 * That is the shape the delivery bookkeeping has to survive: killing the main process must not
 * leave the uid marked as served.
 */
public class KeepAliveService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("BinderTestModule", "keepalive process up, pid=" + Process.myPid());
        return START_STICKY;
    }
}
