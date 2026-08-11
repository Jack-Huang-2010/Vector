package org.matrix.vxmodule;

import io.github.libxposed.api.XposedInterface.Hooker;

/** The handful of interceptor shapes the cases build everything else out of. */
final class Hookers {

    private Hookers() {}

    /**
     * Writes {@code X>} on the way in and {@code <X} on the way out, so a whole chain reads as
     * {@code H>M>L>O<L<M<H}. The exit mark is in a finally: a chain that unwinds through an
     * exception still has to unwind in order.
     */
    static Hooker trace(String token) {
        return chain -> {
            Marks.mark(token + ">");
            try {
                return chain.proceed();
            } finally {
                Marks.mark("<" + token);
            }
        };
    }

    /** Wraps a String-returning method's answer, so "did this hook run" is readable from it. */
    static Hooker wrap(String token) {
        return chain -> token + "(" + chain.proceed() + ")";
    }

    /** Never proceeds. */
    static Hooker constant(Object value) {
        return chain -> value;
    }

    static Hooker counting(int[] counter) {
        return chain -> {
            counter[0]++;
            return chain.proceed();
        };
    }

    static Hooker throwingBefore(String message) {
        return chain -> {
            throw new IllegalStateException(message);
        };
    }

    static Hooker throwingAfter(String message) {
        return chain -> {
            chain.proceed();
            throw new IllegalStateException(message);
        };
    }
}
