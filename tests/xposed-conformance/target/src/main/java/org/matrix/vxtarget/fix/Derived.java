package org.matrix.vxtarget.fix;

/** Overrides everything Base declares, so invoke and invokeSpecial must answer differently. */
public class Derived extends Base {

    @Override
    public String name() {
        return "DERIVED";
    }

    @Override
    protected String prot() {
        return "DERIVED-PROT";
    }
}
