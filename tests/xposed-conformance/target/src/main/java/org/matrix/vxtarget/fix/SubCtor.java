package org.matrix.vxtarget.fix;

/**
 * newInstanceSpecial's fixture: constructing one of these through {@code Ctors(String)} runs the
 * parent constructor only, which is exactly the "could leave the object in an invalid state" the
 * interface warns about - {@link #subField} stays null.
 */
public class SubCtor extends Ctors {

    public String subField = "SUB";

    public SubCtor() {
        super("sub");
    }
}
