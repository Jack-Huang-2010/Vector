package org.matrix.vxtarget.fix;

/**
 * The ordered trace the chain cases build.
 *
 * <p>It lives in the app rather than in the module because the origin has to be able to write to
 * it: a trace of {@code H>M>L>O<L<M<H} is only evidence if the {@code O} comes from the executable
 * itself and not from whichever interceptor happened to call proceed last.
 */
public final class Trace {

    private static final StringBuilder BUFFER = new StringBuilder();

    private Trace() {}

    public static synchronized void mark(String token) {
        BUFFER.append(token);
    }

    /** Reads the trace and clears it, so each case starts from an empty one. */
    public static synchronized String take() {
        String trace = BUFFER.toString();
        BUFFER.setLength(0);
        return trace;
    }
}
