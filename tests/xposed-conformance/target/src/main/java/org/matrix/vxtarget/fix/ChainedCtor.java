package org.matrix.vxtarget.fix;

/** The constructor half of the chain-ordering cases. Separate so a method trace cannot pick it up. */
public class ChainedCtor {

    public final int seed;

    public ChainedCtor(int seed) {
        Trace.mark("O");
        this.seed = seed;
    }
}
