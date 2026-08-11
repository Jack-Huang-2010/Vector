package de.robv.android.xposed;

/** Stub. See legacystub/build.gradle.kts - this is never packaged and never runs. */
public final class XposedBridge {

    private XposedBridge() {}

    public static void log(String text) {
        throw new UnsupportedOperationException("stub");
    }
}
