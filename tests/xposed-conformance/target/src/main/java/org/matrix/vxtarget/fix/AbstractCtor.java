package org.matrix.vxtarget.fix;

/**
 * Abstract, and its constructor is reachable through reflection, so {@code newInstance} on it is a
 * call a module can make. Constructor#newInstance answers InstantiationException, which is what
 * CtorInvoker#newInstance declares.
 */
public abstract class AbstractCtor {

    public final String tag;

    public AbstractCtor() {
        this.tag = "abstract";
    }
}
