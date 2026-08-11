package org.matrix.vxtarget.fix;

/**
 * Implemented by a {@link java.lang.reflect.Proxy} the module builds in this app's class loader,
 * so the hooked executable is a runtime-generated method with no dex of its own.
 */
public interface ProxyIface {

    String call(String argument);
}
