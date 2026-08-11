package org.matrix.vxtarget;

import android.content.Context;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The only thing the hooked app and the module agree on.
 *
 * <p>The module publishes its suite object here from {@code onPackageLoaded} and the app calls
 * back into it reflectively, because the two live in different class loaders and share no types.
 * Nothing in this class is hooked: the trigger path has to keep working even when the case being
 * run is one that breaks hooking.
 */
public final class Bridge {

    /** The module's suite object, or null when the module was never loaded into this process. */
    public static volatile Object suite;

    /** Set by {@link RunReceiver} before the first case, so cases can inflate layouts. */
    public static volatile Context context;

    private Bridge() {}

    /** Runs one case by id and answers {@code STATUS|detail}. */
    public static String run(String caseId) {
        return call("run", caseId);
    }

    /** Answers the case list as {@code STATUS|id<TAB>name;id<TAB>name;...}. */
    public static String list() {
        return call("list", null);
    }

    private static String call(String method, String argument) {
        Object target = suite;
        if (target == null) {
            return "SKIP|module not loaded into this process";
        }
        try {
            Method m =
                    argument == null
                            ? target.getClass().getMethod(method)
                            : target.getClass().getMethod(method, String.class);
            Object result = argument == null ? m.invoke(target) : m.invoke(target, argument);
            return String.valueOf(result);
        } catch (InvocationTargetException e) {
            return "FAIL|suite threw " + describe(e.getCause());
        } catch (Throwable t) {
            return "FAIL|bridge could not reach the suite: " + describe(t);
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        return t.getClass().getName() + ": " + t.getMessage();
    }
}
