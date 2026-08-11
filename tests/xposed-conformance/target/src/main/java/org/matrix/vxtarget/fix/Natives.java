package org.matrix.vxtarget.fix;

/**
 * The kinds of native method the reported failures distinguish.
 *
 * <ul>
 *   <li>{@link #resolvedNative()} and {@link #unresolvedNative()} both have a {@code Java_} symbol.
 *       The suite calls the first one before hooking and never calls the second, so one is hooked
 *       with its JNI binding already resolved and the other with the dlsym lookup stub still in
 *       place.
 *   <li>{@link #dynamicNative()} is bound by RegisterNatives from JNI_OnLoad, i.e. before any
 *       hook, and has no symbol to fall back on.
 *   <li>{@link #lateNative()} is bound by {@link #registerLate()}, so the suite can drive the
 *       other order: hook first, register afterwards. {@link #registerLateAgain()} binds it to a
 *       second implementation answering a different string, which is what lets a case tell a
 *       re-registration that took effect from one that was dropped.
 *   <li>{@link #twinNative()} is bound both ways at once - a {@code Java_} symbol answering
 *       {@code SYMBOL} and a JNI_OnLoad registration answering {@code TWIN}. A hook that loses the
 *       registered pointer therefore answers the wrong string here rather than throwing, which is
 *       the control the symbol-less methods cannot be.
 * </ul>
 */
public class Natives {

    static {
        System.loadLibrary("vxprobe");
    }

    /** Loading is in a static initializer, so this is how a caller forces it to have happened. */
    public static void ensureLoaded() {}

    public static native String resolvedNative();

    public static native String unresolvedNative();

    public static native String dynamicNative();

    public static native String lateNative();

    public static native String twinNative();

    public static native void registerLate();

    public static native void registerLateAgain();

    public static native int nativeAdd(int a, int b);

    /** Address of the C function nativeAdd calls, for the native module API case. */
    public static native long addFunctionAddress();
}
