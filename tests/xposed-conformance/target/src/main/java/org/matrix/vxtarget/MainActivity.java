package org.matrix.vxtarget;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Exists so the app can be launched once after installation. A freshly installed package is in the
 * stopped state and receives no broadcast until something starts it, which otherwise reads as a
 * framework failure rather than as an install artefact.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setPadding(48, 96, 48, 48);
        text.setGravity(Gravity.START);
        text.setText(
                "XP conformance target\n"
                        + "pid "
                        + Process.myPid()
                        + "\n"
                        + "module in this process: "
                        + (Bridge.suite != null)
                        + "\n\n"
                        + "The suite runs in the :suite process; drive it with run.sh.");
        setContentView(text);
    }
}
