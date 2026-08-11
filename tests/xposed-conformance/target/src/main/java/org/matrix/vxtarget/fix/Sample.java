package org.matrix.vxtarget.fix;

import java.lang.reflect.InvocationTargetException;

/** Ordinary methods, one of every access level, plus the two ways a target can throw. */
public class Sample {

    public static String hello(String who) {
        return "hello:" + who;
    }

    public String greet(String who) {
        return "greet:" + who;
    }

    /** Private and never hooked: an invoker is specified to reach it anyway. */
    private String secret(int n) {
        return "secret:" + n;
    }

    /** Package private, same reason. */
    String packagePrivate() {
        return "pkg";
    }

    /** Public method on a package-private class - the other half of the access rule. */
    public static Object hidden() {
        return new Hidden();
    }

    public static String throwsIllegalState() {
        throw new IllegalStateException("target-blew-up");
    }

    /** invokeSpecial needs a receiver, so the throwing target needs an instance form too. */
    public String throwsInstance() {
        throw new IllegalStateException("instance-blew-up");
    }

    /**
     * Throws the wrapper itself. Method#invoke has no special case for this, so it reports the
     * call as {@code InvocationTargetException(InvocationTargetException)} and an invoker must
     * too - collapsing the two is how a nesting level goes missing.
     */
    public static String throwsInvocationTarget() throws Exception {
        throw new InvocationTargetException(new IllegalStateException("inner"));
    }
}

/** Deliberately not public: ART refuses reflective access to its members from an unrelated class. */
class Hidden {

    public String reveal() {
        return "hidden";
    }
}
