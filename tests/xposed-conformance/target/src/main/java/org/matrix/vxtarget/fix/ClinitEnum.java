package org.matrix.vxtarget.fix;

/**
 * The second shape of static initializer. An enum's {@code <clinit>} also builds the synthetic
 * {@code $VALUES} array, and the compiler adds {@code values()} and {@code valueOf(String)} to the
 * class - so a framework that locates {@code <clinit>} by finding the hole in the class's ArtMethod
 * array is looking at a different array here than in {@link ClinitProbe}. {@code
 * HookCases.requireShape} asserts the synthetic field is really there before the case leans on it.
 *
 * <p>Nothing in the app touches it, for the same reason as ClinitProbe: an already-initialized
 * class is specified never to fire the hook.
 */
public enum ClinitEnum {
    FIRST,
    SECOND;

    public static String value;

    static {
        Trace.mark("CLINIT");
        value = "INIT";
    }
}
