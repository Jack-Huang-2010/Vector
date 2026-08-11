package org.matrix.vxmodule;

/**
 * The harness could not set a case up.
 *
 * <p>A missing fixture, a JNI library the app would not load, a bridge that answered nothing: none
 * of it is evidence about the framework, so it travels back under its own status rather than as a
 * failure. Anyone diffing a run against the report has to be able to tell "the framework did the
 * wrong thing" from "the case never ran", and only the case itself knows which of the two it is.
 */
final class Broken extends RuntimeException {

    Broken(String what) {
        super(what);
    }

    Broken(String what, Throwable cause) {
        super(what, cause);
    }
}
