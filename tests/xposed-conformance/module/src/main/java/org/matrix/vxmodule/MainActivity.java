package org.matrix.vxmodule;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Only here so the module app can be launched and enabled from the manager like any other. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setPadding(48, 96, 48, 48);
        text.setGravity(Gravity.START);
        text.setText(
                "libxposed API 102 conformance harness\n\n"
                        + "Enable this module, scope it to org.matrix.vxtarget, and drive the"
                        + " suite with run.sh.");
        setContentView(text);
    }
}
