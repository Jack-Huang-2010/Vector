package org.matrix.vxmodule;

/**
 * The module's own JNI library.
 *
 * <p>Loading it is what makes the framework notice the name in {@code native_init.list} and call
 * {@code native_init}, so the load has to happen from module code rather than at install time.
 */
final class NativeBridge {

    private static volatile String report = "not attempted";
    private static volatile boolean loaded;

    private NativeBridge() {}

    static synchronized boolean load() {
        if (loaded) {
            return true;
        }
        try {
            System.loadLibrary("vxmodhook");
            loaded = true;
            report = "loaded";
        } catch (Throwable t) {
            report = Check.show(t);
        }
        return loaded;
    }

    static String loadReport() {
        return report;
    }

    /** True once the framework has handed native_init its entries. */
    static native boolean ready();

    static native int apiVersion();

    static native boolean install(long address);

    static native boolean remove();
}
