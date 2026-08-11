package org.matrix.vxtarget.fix;

/** Constructors of every shape the CtorInvoker cases need. */
public class Ctors {

    public final String tag;

    public Ctors() {
        this("default");
    }

    public Ctors(String tag) {
        this.tag = tag;
    }

    public Ctors(int n) {
        this.tag = "int:" + n;
    }

    /** Private, so newInstance has to bypass the access check to reach it. */
    private Ctors(long n) {
        this.tag = "private:" + n;
    }

    /** Throws, so the wrapping contract has a constructor of its own to be checked against. */
    public Ctors(boolean unused) {
        this.tag = "unreachable";
        throw new IllegalStateException("ctor-blew-up");
    }
}
