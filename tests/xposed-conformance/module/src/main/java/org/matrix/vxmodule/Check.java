package org.matrix.vxmodule;

import java.util.Arrays;

/**
 * Assertions.
 *
 * <p>A failed one throws {@link AssertionError} carrying the whole story, because that message is
 * all the driver prints and all anyone reading a diff against the original report has to go on.
 * The exception assertions come in two strengths on purpose: the reflection contract distinguishes
 * "an IllegalArgumentException" from "exactly an IllegalArgumentException", and so does the report
 * we are reproducing.
 */
final class Check {

    interface Body {
        void run() throws Throwable;
    }

    private Check() {}

    static void yes(String what, boolean condition) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    static void eq(String what, Object expected, Object actual) {
        if (!equal(expected, actual)) {
            throw new AssertionError(
                    what + ": expected " + show(expected) + ", got " + show(actual));
        }
    }

    /** The exception's class must be the one named, not a subclass. */
    static Throwable expectExactly(Class<? extends Throwable> type, String what, Body body) {
        Throwable thrown = capture(body);
        if (thrown == null) {
            throw new AssertionError(
                    what + ": expected exactly " + type.getName() + ", nothing was thrown");
        }
        if (thrown.getClass() != type) {
            throw new AssertionError(
                    what + ": expected exactly " + type.getName() + ", got " + show(thrown));
        }
        return thrown;
    }

    /**
     * A contrast the case would like to draw against plain reflection, for the rows whose point is
     * that the invoker reaches something an unprivileged reflective call cannot.
     *
     * The contrast is the runtime's to offer, not the framework's: ART decides for itself which
     * members reflection refuses, and on SDK 37 it refuses fewer than the obvious reading of the
     * language suggests - a public member is accessible to it whatever its declaring class's
     * visibility. So a body that throws nothing means the contrast is unavailable here, which says
     * nothing about the framework and must not fail the run. A body that throws something else is
     * still a real disagreement and is reported.
     *
     * @return whether the contrast held, so a caller can say so in the assertion that follows
     */
    static boolean contrast(Class<? extends Throwable> type, String what, Body body) {
        Throwable thrown = capture(body);
        if (thrown == null) {
            return false;
        }
        if (thrown.getClass() != type) {
            throw new AssertionError(
                    what + ": expected exactly " + type.getName() + ", got " + show(thrown));
        }
        return true;
    }

    static Throwable expectKind(Class<? extends Throwable> type, String what, Body body) {
        Throwable thrown = capture(body);
        if (thrown == null) {
            throw new AssertionError(what + ": expected " + type.getName() + ", nothing was thrown");
        }
        if (!type.isInstance(thrown)) {
            throw new AssertionError(what + ": expected " + type.getName() + ", got " + show(thrown));
        }
        return thrown;
    }

    static Throwable expectAny(String what, Body body) {
        Throwable thrown = capture(body);
        if (thrown == null) {
            throw new AssertionError(what + ": nothing was thrown");
        }
        return thrown;
    }

    static void none(String what, Body body) {
        Throwable thrown = capture(body);
        if (thrown != null) {
            throw new AssertionError(what + ": " + show(thrown));
        }
    }

    /**
     * Wraps assertions that are our reading of the spec rather than its words, so that a failure
     * inside one comes back as a question about the interface instead of a proven defect. A setup
     * failure is passed through untouched: the harness being unable to run something says nothing
     * about the spec either way.
     */
    static void reading(String source, Body body) {
        Throwable thrown = capture(body);
        if (thrown instanceof Broken broken) {
            throw broken;
        }
        if (thrown != null) {
            throw new Reading(source, thrown);
        }
    }

    /** What {@link #reading} throws; the suite reports it under its own status. */
    static final class Reading extends AssertionError {

        Reading(String source, Throwable thrown) {
            super("a reading of " + source + ", not its words: " + show(thrown));
        }
    }

    static Throwable capture(Body body) {
        try {
            body.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /** Renders a value with its type, since half of these assertions are about the type. */
    static String show(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Throwable t) {
            String rendered = t.getClass().getName() + "(" + t.getMessage() + ")";
            return t.getCause() == null ? rendered : rendered + " caused by " + show(t.getCause());
        }
        if (value instanceof Class<?> c) {
            return c.getName();
        }
        if (value.getClass().isArray()) {
            return value.getClass().getSimpleName() + Arrays.deepToString(box(value));
        }
        return value.getClass().getName() + "(" + value + ")";
    }

    private static boolean equal(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected.getClass().isArray() && actual.getClass().isArray()) {
            return Arrays.deepEquals(box(expected), box(actual));
        }
        // Deliberately type strict: Integer(5) is not Long(5), and which wrapper comes back out of
        // an invoker is exactly what several of these cases are about.
        return expected.getClass() == actual.getClass() && expected.equals(actual);
    }

    private static Object[] box(Object array) {
        int length = java.lang.reflect.Array.getLength(array);
        Object[] boxed = new Object[length];
        for (int i = 0; i < length; i++) {
            boxed[i] = java.lang.reflect.Array.get(array, i);
        }
        return boxed;
    }
}
