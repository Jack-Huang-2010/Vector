package org.matrix.vxtarget.fix;

/**
 * Fails the first call and answers the second, so an interceptor can proceed, have the proceed
 * throw, swallow it and proceed again. Each call marks itself with its own number, which is how the
 * trace can show that a second proceed really re-entered the chain rather than replaying a cached
 * answer.
 */
public class Flaky {

    public int calls;

    public String once() {
        calls++;
        Trace.mark("O" + calls);
        if (calls == 1) {
            throw new IllegalStateException("first-proceed-blew-up");
        }
        return "OK";
    }
}
