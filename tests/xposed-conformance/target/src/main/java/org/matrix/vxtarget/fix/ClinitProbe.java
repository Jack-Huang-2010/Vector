package org.matrix.vxtarget.fix;

/**
 * Nothing in the app touches this class, so its static initializer is still unrun when the suite
 * hooks it. A hook on an already-initialized class is specified never to fire, so a fixture that
 * anything else reads would silently make the case vacuous.
 */
public class ClinitProbe {

    public static String value;

    static {
        Trace.mark("CLINIT");
        value = "INIT";
    }

    public static String touch() {
        return value;
    }
}
