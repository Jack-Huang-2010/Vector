package org.matrix.vxtarget.fix;

/**
 * The third shape of static initializer. Its initializer reaches a private member of a nested class
 * and that class reaches a private member back - and dex has no nestmates at any API level, so D8
 * desugars each direction into a synthetic accessor placed in the class that <b>declares</b> the
 * private member: one here for {@link #outerSecret()}, one in {@code Inner} for {@code secret()}.
 * So this class's ArtMethod array carries a synthetic entry that {@link ClinitProbe}'s does not.
 *
 * <p>Where in the array that entry lands is not claimed here, and the case does not depend on it;
 * {@code HookCases.requireShape} asserts only that the accessor exists, because without it this
 * fixture is a copy of {@link ClinitProbe} and its row would be decoration.
 *
 * <p>Nothing in the app touches it, for the same reason as {@link ClinitProbe}.
 */
public class ClinitNest {

    public static String value;

    static {
        Trace.mark("CLINIT");
        value = Inner.secret();
    }

    private static String outerSecret() {
        return "INIT";
    }

    private static final class Inner {

        private static String secret() {
            return outerSecret();
        }
    }
}
