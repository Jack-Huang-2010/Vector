package org.matrix.vxmodule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The argument conversion matrix, written once and driven through every entry point, against the
 * oracle the interface itself names.
 *
 * <p>The table below is the matrix {@code Method#invoke} implements: the identity conversion, plus
 * the widening primitive conversions of JLS 5.1.2, applied to the eight wrapper classes and nothing
 * else. But a table is only ever our reading of the JLS, so no row is decided by it alone. Every
 * call is made twice - once through the invoker and once through a plain reflective copy of the
 * same executable, which is what {@code @see Method#invoke} on {@link
 * io.github.libxposed.api.XposedInterface.Invoker#invoke} points at - and the invoker has to answer
 * what reflection answered, or fail with the class reflection failed with.
 *
 * <p>A row where the two disagree is a defect. A row where <b>reflection</b> disagrees with the
 * table is our reading of the JLS being wrong, and it says so in the message rather than blaming
 * the framework.
 *
 * <p>One limit, because a green row is worth exactly what it could have caught: on a <b>hooked</b>
 * executable the reflective copy travels through the same chain as the invoker, so it is not an
 * independent reading of the value that comes back. There it can only catch the two entry points
 * disagreeing, and the table's own constant is what decides the row. Every caller that drives a
 * hooked executable states the constant beside the differential for that reason.
 */
final class Conversions {

    /** One way of reaching an executable: an invoker mode, an entry point, and a receiver. */
    interface Caller {
        Object call(Method method, Object... arguments) throws Throwable;
    }

    private Conversions() {}

    /**
     * The oracle: the same executable reached through plain reflection.
     *
     * <p>{@code setAccessible(true)} is what makes the two comparable at all, since the invoker
     * bypasses access checks by contract and reflection does not. It is called on a fresh copy and
     * never on an executable handed to the framework - {@link Fix} deliberately hands out untouched
     * ones, because a suppressed access check would quietly satisfy the very thing the invoker is
     * supposed to do on its own.
     */
    static Object reflectively(Method method, Object receiver, Object... arguments)
            throws Throwable {
        Method copy =
                method.getDeclaringClass()
                        .getDeclaredMethod(method.getName(), method.getParameterTypes());
        copy.setAccessible(true);
        return copy.invoke(receiver, arguments);
    }

    /** The same oracle for a constructor, which {@code CtorInvoker} is documented against. */
    static Object reflectively(Constructor<?> constructor, Object... arguments) throws Throwable {
        Constructor<?> copy =
                constructor
                        .getDeclaringClass()
                        .getDeclaredConstructor(constructor.getParameterTypes());
        copy.setAccessible(true);
        return copy.newInstance(arguments);
    }

    /**
     * One call through both, whatever the outcome. The receiver is passed rather than taken from
     * the caller so that the oracle makes the identical call - a caller carries its own.
     */
    static void agrees(
            String what, Caller caller, Method method, Object receiver, Object... arguments) {
        Object answered = null;
        Throwable refused = null;
        try {
            answered = reflectively(method, receiver, arguments);
        } catch (Throwable t) {
            refused = t;
        }
        if (refused != null) {
            Throwable thrown =
                    Check.expectAny(
                            what + ": Method#invoke refused it with " + Check.show(refused),
                            () -> caller.call(method, arguments));
            Check.eq(what + " must fail the way Method#invoke does", shape(refused), shape(thrown));
            return;
        }
        Object expected = answered;
        Object actual;
        try {
            actual = caller.call(method, arguments);
        } catch (Throwable t) {
            throw new AssertionError(
                    what
                            + ": Method#invoke answered "
                            + Check.show(expected)
                            + " and the invoker threw "
                            + Check.show(t));
        }
        Check.eq(what + " must answer what Method#invoke answers", expected, actual);
    }

    /** Every pair reflection accepts, with the value that must come back out. */
    static void accepted(Suite.Ctx ctx, String label, Caller caller, Object receiver) {
        pass(
                ctx, label, caller, receiver, "echoBoolean", boolean.class, Boolean.TRUE,
                Boolean.TRUE);
        pass(ctx, label, caller, receiver, "echoChar", char.class, 'q', 'q');

        pass(ctx, label, caller, receiver, "echoByte", byte.class, (byte) 7, (byte) 7);

        pass(ctx, label, caller, receiver, "echoShort", short.class, (byte) 7, (short) 7);
        pass(ctx, label, caller, receiver, "echoShort", short.class, (short) 300, (short) 300);

        pass(ctx, label, caller, receiver, "echoInt", int.class, (byte) 7, 7);
        pass(ctx, label, caller, receiver, "echoInt", int.class, (short) 300, 300);
        pass(ctx, label, caller, receiver, "echoInt", int.class, 'A', 65);
        pass(ctx, label, caller, receiver, "echoInt", int.class, 70000, 70000);

        pass(ctx, label, caller, receiver, "echoLong", long.class, (byte) 7, 7L);
        pass(ctx, label, caller, receiver, "echoLong", long.class, (short) 300, 300L);
        pass(ctx, label, caller, receiver, "echoLong", long.class, 'A', 65L);
        pass(ctx, label, caller, receiver, "echoLong", long.class, 70000, 70000L);
        pass(ctx, label, caller, receiver, "echoLong", long.class, 5_000_000_000L, 5_000_000_000L);

        pass(ctx, label, caller, receiver, "echoFloat", float.class, (byte) 7, 7f);
        pass(ctx, label, caller, receiver, "echoFloat", float.class, (short) 300, 300f);
        pass(ctx, label, caller, receiver, "echoFloat", float.class, 'A', 65f);
        pass(ctx, label, caller, receiver, "echoFloat", float.class, 70000, 70000f);
        pass(ctx, label, caller, receiver, "echoFloat", float.class, 5_000_000_000L, 5.0E9f);
        pass(ctx, label, caller, receiver, "echoFloat", float.class, 1.5f, 1.5f);

        pass(ctx, label, caller, receiver, "echoDouble", double.class, (byte) 7, 7d);
        pass(ctx, label, caller, receiver, "echoDouble", double.class, (short) 300, 300d);
        pass(ctx, label, caller, receiver, "echoDouble", double.class, 'A', 65d);
        pass(ctx, label, caller, receiver, "echoDouble", double.class, 70000, 70000d);
        pass(ctx, label, caller, receiver, "echoDouble", double.class, 5_000_000_000L, 5.0E9d);
        pass(ctx, label, caller, receiver, "echoDouble", double.class, 1.5f, 1.5d);
        pass(ctx, label, caller, receiver, "echoDouble", double.class, 2.5d, 2.5d);

        pass(ctx, label, caller, receiver, "echoRef", CharSequence.class, "abc", "ref:abc");
        pass(ctx, label, caller, receiver, "echoRef", CharSequence.class, null, "ref:null");
        pass(
                ctx,
                label,
                caller,
                receiver,
                "echoArray",
                int[].class,
                new int[] {1, 2, 3},
                new int[] {1, 2, 3});
    }

    /** Every pair reflection refuses. Narrowing is the bulk of it; the rest is plain mistyping. */
    static void rejected(Suite.Ctx ctx, String label, Caller caller, Object receiver) {
        // A boolean converts to nothing and nothing converts to a boolean.
        reject(ctx, label, caller, receiver, "echoBoolean", boolean.class, 1);
        reject(ctx, label, caller, receiver, "echoBoolean", boolean.class, 'x');
        reject(ctx, label, caller, receiver, "echoInt", int.class, Boolean.TRUE);
        reject(ctx, label, caller, receiver, "echoDouble", double.class, Boolean.TRUE);

        // char widens to int and above; nothing widens to char.
        reject(ctx, label, caller, receiver, "echoChar", char.class, 65);
        reject(ctx, label, caller, receiver, "echoChar", char.class, (byte) 65);
        reject(ctx, label, caller, receiver, "echoByte", byte.class, 'a');
        reject(ctx, label, caller, receiver, "echoShort", short.class, 'a');

        // Narrowing, one step at a time.
        reject(ctx, label, caller, receiver, "echoByte", byte.class, (short) 7);
        reject(ctx, label, caller, receiver, "echoByte", byte.class, 300);
        reject(ctx, label, caller, receiver, "echoByte", byte.class, 7L);
        reject(ctx, label, caller, receiver, "echoShort", short.class, 70000);
        reject(ctx, label, caller, receiver, "echoShort", short.class, 7L);
        reject(ctx, label, caller, receiver, "echoInt", int.class, 5_000_000_000L);
        reject(ctx, label, caller, receiver, "echoInt", int.class, 3.9f);
        reject(ctx, label, caller, receiver, "echoInt", int.class, 3.9d);
        reject(ctx, label, caller, receiver, "echoLong", long.class, 3.9f);
        reject(ctx, label, caller, receiver, "echoLong", long.class, 3.9d);
        reject(ctx, label, caller, receiver, "echoFloat", float.class, 3.9d);

        // A Number that is not one of the eight wrappers is not a wrapper at all.
        reject(ctx, label, caller, receiver, "echoInt", int.class, new BigDecimal("7"));
        reject(ctx, label, caller, receiver, "echoInt", int.class, BigInteger.valueOf(7));
        reject(ctx, label, caller, receiver, "echoLong", long.class, new AtomicInteger(7));

        // null is not a primitive.
        reject(ctx, label, caller, receiver, "echoInt", int.class, (Object) null);
        reject(ctx, label, caller, receiver, "echoBoolean", boolean.class, (Object) null);

        // Reference parameters are checked against the declared type, arrays included.
        reject(ctx, label, caller, receiver, "echoRef", CharSequence.class, 5);
        reject(ctx, label, caller, receiver, "echoArray", int[].class, "not an array");
        reject(ctx, label, caller, receiver, "echoArray", int[].class, new String[] {"a"});
    }

    /** The arity rule, which reflection reports the same way. */
    static void arity(Suite.Ctx ctx, String label, Caller caller, Object receiver) {
        Method echoInt = ctx.fix.method("Prims", "echoInt", int.class);
        refusesLike(label + " echoInt() with too few arguments", caller, echoInt, receiver);
        refusesLike(label + " echoInt(1, 2) with too many arguments", caller, echoInt, receiver, 1,
                2);
    }

    private static void pass(
            Suite.Ctx ctx,
            String label,
            Caller caller,
            Object receiver,
            String name,
            Class<?> parameter,
            Object argument,
            Object expected) {
        Method method = ctx.fix.method("Prims", name, parameter);
        String what = label + " " + name + "(" + Check.show(argument) + ")";

        Object oracle;
        try {
            oracle = reflectively(method, receiver, argument);
        } catch (Throwable t) {
            throw new AssertionError(
                    what
                            + ": the accepted table is wrong, Method#invoke refuses it with "
                            + Check.show(t));
        }
        Check.eq(what + ": the accepted table and Method#invoke disagree", expected, oracle);

        Object actual;
        try {
            actual = caller.call(method, argument);
        } catch (Throwable t) {
            throw new AssertionError(what + " must be accepted, got " + Check.show(t));
        }
        Check.eq(what, expected, actual);
    }

    private static void reject(
            Suite.Ctx ctx,
            String label,
            Caller caller,
            Object receiver,
            String name,
            Class<?> parameter,
            Object argument) {
        Method method = ctx.fix.method("Prims", name, parameter);
        refusesLike(
                label + " " + name + "(" + Check.show(argument) + ")",
                caller,
                method,
                receiver,
                argument);
    }

    /**
     * One call both are expected to refuse. Reflection's refusal is asserted to be exactly
     * IllegalArgumentException, because that is what the rejected table claims and a caller has to
     * be able to tell a refused argument from an exception the target threw; the invoker's is then
     * asserted against reflection's, which carries the same constant through - a refusal whose
     * shape matches an exception asserted to be exactly IllegalArgumentException is exactly one
     * too - plus the whole cause chain, which the constant alone would not catch.
     *
     * <p>The first message does not blame the table, because on a hooked executable a reflective
     * call travels through the framework's chain and this line can go red for the framework's own
     * reasons.
     */
    private static void refusesLike(
            String what, Caller caller, Method method, Object receiver, Object... arguments) {
        Throwable oracle =
                Check.expectExactly(
                        IllegalArgumentException.class,
                        what
                                + ": Method#invoke did not refuse it the way the rejected table"
                                + " says, and on a hooked executable reflection travels through the"
                                + " chain",
                        () -> reflectively(method, receiver, arguments));
        Throwable thrown =
                Check.expectAny(what + " must be refused", () -> caller.call(method, arguments));
        Check.eq(
                what + " must be refused the way Method#invoke refuses it",
                shape(oracle),
                shape(thrown));
    }

    /**
     * The exception's class and its whole cause chain, which is what "the same exception" has to
     * mean here: a framework that collapses or adds a wrapping level around a target exception is
     * throwing the same class and a different thing.
     */
    static String shape(Throwable thrown) {
        StringBuilder rendered = new StringBuilder();
        Throwable at = thrown;
        for (int depth = 0; at != null && depth < 8; depth++) {
            if (depth > 0) {
                rendered.append(" caused by ");
            }
            rendered.append(at.getClass().getName());
            at = at.getCause() == at ? null : at.getCause();
        }
        return rendered.toString();
    }
}
