package org.matrix.vxmodule;

import java.lang.reflect.Method;

/**
 * The module's end of the app's trace buffer.
 *
 * <p>Interceptors write their token here and so does the origin, which is the only way a trace of
 * {@code H>M>L>O<L<M<H} can prove that the executable itself ran between the two halves of the
 * chain rather than some interceptor claiming it did.
 */
final class Marks {

    private static volatile Method mark;
    private static volatile Method take;

    private Marks() {}

    static void bind(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> trace = loader.loadClass("org.matrix.vxtarget.fix.Trace");
        mark = trace.getDeclaredMethod("mark", String.class);
        take = trace.getDeclaredMethod("take");
    }

    static void mark(String token) {
        try {
            mark.invoke(null, token);
        } catch (ReflectiveOperationException e) {
            throw new Broken("cannot write the trace", e);
        }
    }

    /** Reads the trace and clears it. */
    static String take() {
        try {
            return (String) take.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new Broken("cannot read the trace", e);
        }
    }
}
