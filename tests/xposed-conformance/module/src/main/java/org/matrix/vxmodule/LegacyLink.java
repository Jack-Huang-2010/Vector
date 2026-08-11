package org.matrix.vxmodule;

/**
 * Isolated so the legacy type is only resolved when {@link #touch()} is actually called, rather
 * than when the case class is loaded.
 */
final class LegacyLink {

    private LegacyLink() {}

    static void touch() {
        de.robv.android.xposed.XposedBridge.log("conformance harness probing the legacy API");
    }
}
