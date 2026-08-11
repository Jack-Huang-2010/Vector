package org.matrix.vxtarget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Runs exactly one case per broadcast, in the {@code :suite} process, and leaves the answer in a
 * file the driver can read.
 *
 * <p>One case per broadcast is the whole isolation story: a case that aborts the runtime loses its
 * own result and nothing else, and the next broadcast starts a fresh process.
 */
public class RunReceiver extends BroadcastReceiver {

    public static final String TAG = "VXConf";

    /** Nothing may take longer than this; a case that does is reported and left behind. */
    private static final long CASE_TIMEOUT_MS = 20_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        final String caseId = intent.getStringExtra("case");
        final Context app = context.getApplicationContext();
        Bridge.context = app;
        final PendingResult pending = goAsync();
        new Thread(
                        () -> {
                            try {
                                deliver(app, caseId);
                            } finally {
                                pending.finish();
                            }
                        },
                        "vx-dispatch")
                .start();
    }

    private void deliver(Context app, String caseId) {
        if (caseId == null) {
            Log.w(TAG, "RUN without a case extra");
            return;
        }
        String answer;
        if ("@list".equals(caseId)) {
            answer = Bridge.list();
        } else {
            answer = runIsolated(caseId);
        }
        String status = answer;
        String detail = "";
        int cut = answer.indexOf('|');
        if (cut >= 0) {
            status = answer.substring(0, cut);
            detail = answer.substring(cut + 1);
        }
        detail = detail.replace('\n', ' ').replace('\r', ' ');
        Log.i(TAG, "RESULT " + caseId + " " + status + " pid=" + Process.myPid() + " " + detail);
        write(app, caseId, status + "|" + detail);
    }

    /**
     * A case that hangs must not hang the run. The worker is abandoned rather than interrupted:
     * it may be blocked inside the framework, and the next case gets a fresh process anyway.
     */
    private String runIsolated(String caseId) {
        final String[] slot = new String[1];
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                slot[0] = Bridge.run(caseId);
                            } catch (Throwable t) {
                                slot[0] = "FAIL|case escaped the bridge: " + t;
                            }
                        },
                        "vx-case-" + caseId);
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(CASE_TIMEOUT_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (slot[0] == null) {
            return "TIMEOUT|still running after " + CASE_TIMEOUT_MS + "ms";
        }
        return slot[0];
    }

    private void write(Context app, String caseId, String line) {
        File dir = new File(app.getFilesDir(), "results");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.e(TAG, "cannot create " + dir);
            return;
        }
        File tmp = new File(dir, caseId + ".tmp");
        File out = new File(dir, caseId);
        try (Writer w =
                new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(line);
            w.write('\n');
        } catch (Throwable t) {
            Log.e(TAG, "cannot write " + out, t);
            return;
        }
        // The driver polls for the file, so it must never observe a half-written one.
        if (!tmp.renameTo(out)) {
            Log.e(TAG, "cannot rename " + tmp + " to " + out);
        }
    }
}
