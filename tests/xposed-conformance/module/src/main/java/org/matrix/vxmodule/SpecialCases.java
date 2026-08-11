package org.matrix.vxmodule;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface.Invoker;

/**
 * Invoker#invokeSpecial.
 *
 * <p>Its preconditions are the ones JNI does not report: CallNonvirtual&lt;Type&gt;MethodA aborts
 * the runtime on a foreign receiver or a mistyped argument rather than throwing, and CheckJNI is
 * on in every debuggable process. Each of those checks is fenced with a breadcrumb, because if the
 * process does go down the log is the only thing left.
 */
final class SpecialCases {

    private SpecialCases() {}

    static void register(Suite suite) {
        suite.add(
                "special-primitives",
                "invokeSpecial all primitive types",
                SpecialCases::primitives);
        suite.add(
                "special-target-exception",
                "invokeSpecial target exception",
                SpecialCases::targetException);
        suite.add(
                "special-access",
                "invokeSpecial bypasses access checks",
                SpecialCases::bypassesAccessChecks);
        suite.add(
                "special-dispatch",
                "invokeSpecial non-virtual dispatch",
                SpecialCases::nonVirtualDispatch);
        suite.add(
                "special-conversions",
                "invokeSpecial conversions/errors",
                SpecialCases::conversionsAndErrors);
    }

    private static void primitives(Suite.Ctx ctx) throws Throwable {
        Object prims = ctx.fix.make("Prims");
        Conversions.Caller special = InvokeCases.specialCaller(ctx, prims, null);

        Check.eq("boolean", Boolean.TRUE, special.call(echo(ctx, "echoBoolean", boolean.class), true));
        Check.eq("byte", (byte) -8, special.call(echo(ctx, "echoByte", byte.class), (byte) -8));
        Check.eq("char", 'z', special.call(echo(ctx, "echoChar", char.class), 'z'));
        Check.eq(
                "short", (short) -300, special.call(echo(ctx, "echoShort", short.class), (short) -300));
        Check.eq("int", 70000, special.call(echo(ctx, "echoInt", int.class), 70000));
        Check.eq(
                "long", 5_000_000_000L, special.call(echo(ctx, "echoLong", long.class), 5_000_000_000L));
        Check.eq("float", 1.5f, special.call(echo(ctx, "echoFloat", float.class), 1.5f));
        Check.eq("double", 2.5d, special.call(echo(ctx, "echoDouble", double.class), 2.5d));
        Check.eq("void answers null", null, special.call(ctx.fix.method("Prims", "echoVoid")));
        Check.eq(
                "reference", "ref:abc", special.call(echo(ctx, "echoRef", CharSequence.class), "abc"));
        Check.eq(
                "array",
                new int[] {1, 2, 3},
                special.call(echo(ctx, "echoArray", int[].class), new int[] {1, 2, 3}));
    }

    private static void targetException(Suite.Ctx ctx) throws Throwable {
        Object sample = ctx.fix.make("Sample");
        Method thrower = ctx.fix.method("Sample", "throwsInstance");

        Throwable thrown =
                Check.expectExactly(
                        InvocationTargetException.class,
                        "invokeSpecial wraps the target exception",
                        () -> ctx.invoker(thrower).invokeSpecial(sample));
        Check.eq("cause type", IllegalStateException.class, thrown.getCause().getClass());
        Check.eq("cause message", "instance-blew-up", thrown.getCause().getMessage());

        ctx.hook(thrower, chain -> chain.proceed());
        Throwable hooked =
                Check.expectExactly(
                        InvocationTargetException.class,
                        "hooked invokeSpecial wraps the target exception",
                        () -> ctx.invoker(thrower).invokeSpecial(sample));
        Check.eq("hooked cause type", IllegalStateException.class, hooked.getCause().getClass());
    }

    /**
     * Each half is asserted against the plain reflective call it is documented to differ from,
     * because "invokeSpecial reached it" on its own would also be satisfied by a runtime that
     * checks nothing at all.
     */
    private static void bypassesAccessChecks(Suite.Ctx ctx) throws Throwable {
        Object sample = ctx.fix.make("Sample");
        Method secret = ctx.fix.method("Sample", "secret", int.class);
        Method packagePrivate = ctx.fix.method("Sample", "packagePrivate");

        Check.expectExactly(
                IllegalAccessException.class,
                "reflection cannot reach the private method",
                () -> secret.invoke(sample, 9));
        Check.eq("private method", "secret:9", ctx.invoker(secret).invokeSpecial(sample, 9));

        Check.expectExactly(
                IllegalAccessException.class,
                "reflection cannot reach the package-private method",
                () -> packagePrivate.invoke(sample));
        Check.eq(
                "package-private method",
                "pkg",
                ctx.invoker(packagePrivate).invokeSpecial(sample));

        Object hidden = Fix.call(ctx.fix.method("Sample", "hidden"), null);
        Method reveal = hidden.getClass().getDeclaredMethod("reveal");
        Check.contrast(
                IllegalAccessException.class,
                "reflection cannot reach a public method of a non-public class",
                () -> reveal.invoke(hidden));
        Check.eq(
                "public method of a non-public class",
                "hidden",
                ctx.invoker(reveal).invokeSpecial(hidden));
    }

    /**
     * The point of the entry point: the body of the class the executable was taken from runs, not
     * the override. Everything after that is the precondition set - a foreign receiver is the one
     * JNI answers with a process abort where Method#invoke answers with IllegalArgumentException.
     *
     * <p>The half that only exists because {@code invokeSpecial} does is that {@code invoke} is
     * <b>not</b> special: it is documented with {@code @see Method#invoke}, and reflection on
     * {@code Base.name} with a Derived receiver reaches the override. Installing a hook must not
     * turn that into a non-virtual call, which is exactly what an implementation that routes a
     * hooked invoke through its non-virtual backup would do - so the same call is made once more
     * with the hook in place.
     */
    private static void nonVirtualDispatch(Suite.Ctx ctx) throws Throwable {
        Object derived = ctx.fix.make("Derived");
        Method name = ctx.fix.method("Base", "name");
        Method prot = ctx.fix.method("Base", "prot");

        ctx.step("invokeSpecial on an overridden method");
        Check.eq("invokeSpecial runs Base.name", "BASE", ctx.invoker(name).invokeSpecial(derived));
        Check.eq("invoke dispatches virtually", "DERIVED", ctx.invoker(name).invoke(derived));
        Check.eq(
                "invokeSpecial runs Base.prot",
                "BASE-PROT",
                ctx.invoker(prot).invokeSpecial(derived));

        ctx.step("invokeSpecial with a null receiver");
        Check.expectExactly(
                NullPointerException.class,
                "null receiver",
                () -> ctx.invoker(name).invokeSpecial(null));

        ctx.step("invokeSpecial with a receiver of an unrelated class");
        Check.expectExactly(
                IllegalArgumentException.class,
                "receiver of an unrelated class",
                () -> ctx.invoker(name).invokeSpecial(new Object()));

        ctx.step("invokeSpecial on a hooked method");
        ctx.hook(name, Hookers.wrap("H"));
        Check.eq(
                "hooked invokeSpecial still runs the class it was taken from",
                "H(BASE)",
                ctx.invoker(name).invokeSpecial(derived));
        Check.eq(
                "hooked Origin invokeSpecial skips the chain",
                "BASE",
                ctx.invoker(name, Invoker.Type.ORIGIN).invokeSpecial(derived));

        // Whether the chain on Base.name also wraps the answer is a reading either way; that the
        // override ran at all is not, so that is what is asserted.
        ctx.step("invoke on the same hooked method");
        Object reflected = Conversions.reflectively(name, derived);
        Check.yes(
                "reflection on the hooked Base.name reaches the override: "
                        + Check.show(reflected),
                String.valueOf(reflected).contains("DERIVED"));
        Object invoked = ctx.invoker(name).invoke(derived);
        Check.yes(
                "hooked invoke still dispatches virtually: " + Check.show(invoked),
                String.valueOf(invoked).contains("DERIVED"));
    }

    private static void conversionsAndErrors(Suite.Ctx ctx) {
        Object prims = ctx.fix.make("Prims");
        Conversions.Caller special = InvokeCases.specialCaller(ctx, prims, null);

        ctx.step("the accepted half of the matrix");
        Conversions.accepted(ctx, "invokeSpecial", special, prims);
        ctx.step("the rejected half of the matrix");
        Conversions.rejected(ctx, "invokeSpecial", special, prims);
        ctx.step("arity");
        Conversions.arity(ctx, "invokeSpecial", special, prims);

        ctx.step("the same once the executable is hooked");
        Method echoInt = ctx.fix.method("Prims", "echoInt", int.class);
        ctx.hook(echoInt, chain -> chain.proceed());
        Check.expectExactly(
                IllegalArgumentException.class,
                "hooked invokeSpecial rejects a narrowing argument",
                () -> special.call(echoInt, 5_000_000_000L));
        Check.expectExactly(
                IllegalArgumentException.class,
                "hooked invokeSpecial rejects the wrong arity",
                () -> special.call(echoInt));
    }

    private static Method echo(Suite.Ctx ctx, String name, Class<?> parameter) {
        return ctx.fix.method("Prims", name, parameter);
    }
}
