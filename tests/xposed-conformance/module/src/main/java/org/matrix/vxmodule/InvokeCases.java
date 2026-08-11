package org.matrix.vxmodule;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Invoker;

/** Invoker#invoke: access, chain type, conversions, validation and exception wrapping. */
final class InvokeCases {

    private InvokeCases() {}

    static void register(Suite suite) {
        suite.add(
                "invoker-access",
                "Invoker bypasses method access checks",
                InvokeCases::bypassesAccessChecks);
        suite.add(
                "invoker-full-and-origin",
                "invoker full chain and origin",
                InvokeCases::fullChainAndOrigin);
        suite.add(
                "invoke-origin-primitives",
                "invoke Origin all primitive types",
                InvokeCases::originPrimitives);
        suite.add(
                "invoke-origin-target-exception",
                "invoke Origin target exception",
                InvokeCases::originTargetException);
        suite.add("invoke-widening", "invoke widening conversions", InvokeCases::widening);
        suite.add(
                "invoke-rejects-invalid",
                "invoke rejects invalid arguments",
                InvokeCases::rejectsInvalid);
        suite.add(
                "invoker-widening-matrix",
                "Invoker primitive widening matrix",
                InvokeCases::wideningMatrix);
        suite.add(
                "invoker-rejected-matrix",
                "Invoker rejected conversion matrix",
                InvokeCases::rejectedMatrix);
        suite.add(
                "invoker-validation",
                "Invoker reflection-compatible validation",
                InvokeCases::reflectionCompatibleValidation);
        suite.add(
                "invoker-ite-method",
                "method Invoker target exception wrapping",
                InvokeCases::targetExceptionWrapping);
        suite.add(
                "invoker-chain-order",
                "Invoker filtered hook chain ordering",
                InvokeCases::filteredChainOrdering);
    }

    /**
     * "Invocations through invokers will bypass access checks", on executables carrying no hook.
     *
     * <p>Each half is asserted against the plain reflective call it is documented to differ from,
     * because "the invoker reached it" on its own would also be satisfied by a runtime that checks
     * nothing at all.
     */
    private static void bypassesAccessChecks(Suite.Ctx ctx) throws Throwable {
        Object sample = ctx.fix.make("Sample");
        Method secret = ctx.fix.method("Sample", "secret", int.class);
        Method packagePrivate = ctx.fix.method("Sample", "packagePrivate");

        Check.expectExactly(
                IllegalAccessException.class,
                "reflection cannot reach the private method",
                () -> secret.invoke(sample, 7));
        Check.eq("private method, default type", "secret:7", ctx.invoker(secret).invoke(sample, 7));
        Check.eq(
                "private method, Type.ORIGIN",
                "secret:7",
                ctx.invoker(secret, Invoker.Type.ORIGIN).invoke(sample, 7));

        Check.expectExactly(
                IllegalAccessException.class,
                "reflection cannot reach the package-private method",
                () -> packagePrivate.invoke(sample));
        Check.eq("package-private method", "pkg", ctx.invoker(packagePrivate).invoke(sample));

        // The other half of the rule ART enforces: a public method is still out of reach when its
        // declaring class is not public.
        Object hidden = Fix.call(ctx.fix.method("Sample", "hidden"), null);
        Method reveal = hidden.getClass().getDeclaredMethod("reveal");
        Check.contrast(
                IllegalAccessException.class,
                "reflection cannot reach a public method of a non-public class",
                () -> reveal.invoke(hidden));
        Check.eq("public method of a non-public class", "hidden", ctx.invoker(reveal).invoke(hidden));

        // And once the executable carries a hook, which is the path that goes through the backup.
        ctx.hook(secret, Hookers.wrap("H"));
        Check.eq("private method, hooked", "H(secret:8)", ctx.invoker(secret).invoke(sample, 8));
    }

    private static void fullChainAndOrigin(Suite.Ctx ctx) throws Throwable {
        Object chained = ctx.fix.make("Chained");
        Method origin = ctx.fix.method("Chained", "origin");
        installChain(ctx, origin);

        Marks.take();
        ctx.invoker(origin, Invoker.Type.Chain.FULL).invoke(chained);
        Check.eq("Chain.FULL runs every hook", "H>M>L>O<L<M<H", Marks.take());

        ctx.invoker(origin, Invoker.Type.ORIGIN).invoke(chained);
        Check.eq("Type.ORIGIN skips all hooks", "O", Marks.take());

        // The default type is documented as Chain.FULL.
        ctx.invoker(origin).invoke(chained);
        Check.eq("the default type is Chain.FULL", "H>M>L>O<L<M<H", Marks.take());
    }

    private static void originPrimitives(Suite.Ctx ctx) throws Throwable {
        Object prims = ctx.fix.make("Prims");
        Conversions.Caller origin = originCaller(ctx, prims);

        Check.eq("boolean", Boolean.FALSE, origin.call(echo(ctx, "echoBoolean", boolean.class), false));
        Check.eq("byte", (byte) -8, origin.call(echo(ctx, "echoByte", byte.class), (byte) -8));
        Check.eq("char", 'z', origin.call(echo(ctx, "echoChar", char.class), 'z'));
        Check.eq("short", (short) -300, origin.call(echo(ctx, "echoShort", short.class), (short) -300));
        Check.eq("int", 70000, origin.call(echo(ctx, "echoInt", int.class), 70000));
        Check.eq("long", 5_000_000_000L, origin.call(echo(ctx, "echoLong", long.class), 5_000_000_000L));
        Check.eq("float", 1.5f, origin.call(echo(ctx, "echoFloat", float.class), 1.5f));
        Check.eq("double", 2.5d, origin.call(echo(ctx, "echoDouble", double.class), 2.5d));
        Check.eq("void answers null", null, origin.call(ctx.fix.method("Prims", "echoVoid")));
        Check.eq(
                "reference",
                "ref:abc",
                origin.call(echo(ctx, "echoRef", CharSequence.class), "abc"));
        Check.eq(
                "array",
                new int[] {1, 2, 3},
                origin.call(echo(ctx, "echoArray", int[].class), new int[] {1, 2, 3}));
        Check.eq(
                "all of them at once",
                "true/1/c/2/3/4/5.0/6.0",
                origin.call(
                        ctx.fix.method(
                                "Prims",
                                "all",
                                boolean.class,
                                byte.class,
                                char.class,
                                short.class,
                                int.class,
                                long.class,
                                float.class,
                                double.class),
                        true,
                        (byte) 1,
                        'c',
                        (short) 2,
                        3,
                        4L,
                        5f,
                        6d));
    }

    private static void originTargetException(Suite.Ctx ctx) throws Throwable {
        Method thrower = ctx.fix.method("Sample", "throwsIllegalState");
        Throwable thrown =
                Check.expectExactly(
                        InvocationTargetException.class,
                        "Origin invoke wraps what the target threw",
                        () -> ctx.invoker(thrower, Invoker.Type.ORIGIN).invoke(null));
        Check.eq("cause type", IllegalStateException.class, thrown.getCause().getClass());
        Check.eq("cause message", "target-blew-up", thrown.getCause().getMessage());
    }

    private static void widening(Suite.Ctx ctx) {
        Object prims = ctx.fix.make("Prims");
        Conversions.accepted(ctx, "invoke Origin", originCaller(ctx, prims), prims);
        Conversions.accepted(ctx, "invoke", chainCaller(ctx, prims), prims);
    }

    private static void rejectsInvalid(Suite.Ctx ctx) {
        Object prims = ctx.fix.make("Prims");
        Conversions.rejected(ctx, "invoke Origin", originCaller(ctx, prims), prims);
        Conversions.arity(ctx, "invoke Origin", originCaller(ctx, prims), prims);
        Conversions.rejected(ctx, "invoke", chainCaller(ctx, prims), prims);
        Conversions.arity(ctx, "invoke", chainCaller(ctx, prims), prims);
    }

    /**
     * The same matrix through every entry point, plus the thing that makes Type.ORIGIN worth
     * having: with the executable hooked, an Origin invocation must reach the body and nothing
     * else. That half is the interface's - "invokes the original executable, skipping all hooks".
     *
     * <p>The reflective call beside it is not: that a hook installed through {@code
     * hook(Executable)} is visible to a plain reflective caller is what hooking <i>means</i> here
     * rather than a sentence in the interface. It is asserted all the same, because without it the
     * ORIGIN half would also pass on a framework that never installed the hook at all - and because
     * every case in this suite that reads a hook through {@code Fix.call} depends on it.
     */
    private static void wideningMatrix(Suite.Ctx ctx) throws Throwable {
        Object prims = ctx.fix.make("Prims");
        Conversions.accepted(ctx, "invoke", chainCaller(ctx, prims), prims);
        Conversions.accepted(ctx, "invoke Origin", originCaller(ctx, prims), prims);
        Conversions.accepted(ctx, "invokeSpecial", specialCaller(ctx, prims, null), prims);
        Conversions.accepted(
                ctx, "invokeSpecial Origin", specialCaller(ctx, prims, Invoker.Type.ORIGIN), prims);

        Method echoInt = ctx.fix.method("Prims", "echoInt", int.class);
        ctx.hook(echoInt, Hookers.constant(-1));
        Check.eq("the hook is installed", -1, ctx.invoker(echoInt).invoke(prims, 5));
        Check.eq("and reflection goes through it", -1, Conversions.reflectively(echoInt, prims, 5));
        Check.eq(
                "Origin invoke must bypass hook",
                5,
                ctx.invoker(echoInt, Invoker.Type.ORIGIN).invoke(prims, 5));
        Check.eq(
                "Origin invokeSpecial must bypass hook",
                5,
                ctx.invoker(echoInt, Invoker.Type.ORIGIN).invokeSpecial(prims, 5));
    }

    /** Rejections have to stay exactly IllegalArgumentException once the executable is hooked. */
    private static void rejectedMatrix(Suite.Ctx ctx) {
        Object prims = ctx.fix.make("Prims");
        Conversions.rejected(ctx, "invoke", chainCaller(ctx, prims), prims);
        Conversions.rejected(ctx, "invokeSpecial", specialCaller(ctx, prims, null), prims);

        hookEveryEcho(ctx);
        Conversions.rejected(ctx, "hooked invoke", chainCaller(ctx, prims), prims);
        Conversions.rejected(ctx, "hooked invokeSpecial", specialCaller(ctx, prims, null), prims);
        Conversions.arity(ctx, "hooked invoke", chainCaller(ctx, prims), prims);
    }

    /**
     * The rest of Method#invoke's contract: receivers, arity, null argument arrays.
     *
     * <p>Every row is stated twice: once against the value or the exception class the contract
     * names, and once as the same call made through a reflective copy. The literal is the one that
     * decides the row - the differential is an addition and never a replacement, because on a
     * <b>hooked</b> executable the reflective copy travels through the same chain and a framework
     * that corrupts the answer corrupts both readings identically. What the differential is still
     * worth there is the disagreement: a framework whose invoker and whose reflective entry point
     * answer differently for the same call.
     */
    private static void reflectionCompatibleValidation(Suite.Ctx ctx) throws Throwable {
        Object prims = ctx.fix.make("Prims");
        Method echoInt = ctx.fix.method("Prims", "echoInt", int.class);
        Method echoVoid = ctx.fix.method("Prims", "echoVoid");
        Method staticEcho = ctx.fix.method("Prims", "staticEcho", int.class);
        Object unrelated = new Object();

        Check.expectExactly(
                NullPointerException.class,
                "null receiver on an instance method",
                () -> ctx.invoker(echoInt).invoke(null, 1));
        Conversions.agrees(
                "null receiver on an instance method", chainCaller(ctx, null), echoInt, null, 1);
        Check.expectExactly(
                IllegalArgumentException.class,
                "receiver of an unrelated class",
                () -> ctx.invoker(echoInt).invoke(unrelated, 1));
        Conversions.agrees(
                "receiver of an unrelated class",
                chainCaller(ctx, unrelated),
                echoInt,
                unrelated,
                1);
        Check.eq(
                "a static method ignores the receiver",
                "static:1",
                ctx.invoker(staticEcho).invoke(unrelated, 1));
        Conversions.agrees(
                "a static method ignores the receiver",
                chainCaller(ctx, unrelated),
                staticEcho,
                unrelated,
                1);
        Check.eq(
                "a static method accepts a null receiver",
                "static:2",
                ctx.invoker(staticEcho).invoke(null, 2));
        Conversions.agrees(
                "a static method accepts a null receiver",
                chainCaller(ctx, null),
                staticEcho,
                null,
                2);

        // Method#invoke reads a null argument array as no arguments at all.
        Check.eq(
                "null argument array on a no-argument method",
                null,
                ctx.invoker(echoVoid).invoke(prims, (Object[]) null));
        Conversions.agrees(
                "null argument array on a no-argument method",
                chainCaller(ctx, prims),
                echoVoid,
                prims,
                (Object[]) null);
        Check.expectExactly(
                IllegalArgumentException.class,
                "null argument array on a method that takes one",
                () -> ctx.invoker(echoInt).invoke(prims, (Object[]) null));
        Conversions.agrees(
                "null argument array on a method that takes one",
                chainCaller(ctx, prims),
                echoInt,
                prims,
                (Object[]) null);

        Conversions.arity(ctx, "invoke", chainCaller(ctx, prims), prims);

        // And all of it again with the executable hooked, because that is the path where the
        // framework's own refusals travel back out through the chain.
        ctx.hook(echoInt, chain -> chain.proceed());
        ctx.hook(staticEcho, chain -> chain.proceed());
        Check.expectExactly(
                NullPointerException.class,
                "hooked: null receiver on an instance method",
                () -> ctx.invoker(echoInt).invoke(null, 1));
        Conversions.agrees(
                "hooked: null receiver on an instance method",
                chainCaller(ctx, null),
                echoInt,
                null,
                1);
        Check.expectExactly(
                IllegalArgumentException.class,
                "hooked: receiver of an unrelated class",
                () -> ctx.invoker(echoInt).invoke(unrelated, 1));
        Conversions.agrees(
                "hooked: receiver of an unrelated class",
                chainCaller(ctx, unrelated),
                echoInt,
                unrelated,
                1);
        Check.expectExactly(
                IllegalArgumentException.class,
                "hooked: wrong number of arguments",
                () -> ctx.invoker(echoInt).invoke(prims));
        Conversions.arity(ctx, "hooked invoke", chainCaller(ctx, prims), prims);
        Check.eq(
                "hooked: a static method ignores the receiver",
                "static:3",
                ctx.invoker(staticEcho).invoke(unrelated, 3));
        Conversions.agrees(
                "hooked: a static method ignores the receiver",
                chainCaller(ctx, unrelated),
                staticEcho,
                unrelated,
                3);
    }

    /**
     * Method#invoke reports whatever the target threw wrapped in exactly one
     * InvocationTargetException, and has no special case for a target that throws one itself.
     */
    private static void targetExceptionWrapping(Suite.Ctx ctx) throws Throwable {
        Method thrower = ctx.fix.method("Sample", "throwsIllegalState");
        Method nested = ctx.fix.method("Sample", "throwsInvocationTarget");

        plainThrow("unhooked Chain invoke", ctx.invoker(thrower));
        plainThrow("unhooked Origin invoke", ctx.invoker(thrower, Invoker.Type.ORIGIN));
        nestedThrow("unhooked Chain invoke", ctx.invoker(nested));
        nestedThrow("unhooked Origin invoke", ctx.invoker(nested, Invoker.Type.ORIGIN));

        ctx.hook(thrower, chain -> chain.proceed());
        ctx.hook(nested, chain -> chain.proceed());

        plainThrow("hooked Chain invoke", ctx.invoker(thrower));
        plainThrow("hooked Origin invoke", ctx.invoker(thrower, Invoker.Type.ORIGIN));
        nestedThrow("hooked Chain invoke", ctx.invoker(nested));
        nestedThrow("hooked Origin invoke", ctx.invoker(nested, Invoker.Type.ORIGIN));

        // Only worth making once the executables are hooked, and only about the two entry points
        // disagreeing: the invoker's InvocationTargetException is built by the framework, while
        // reflection's is built by ART's reflection layer around whatever came back out of the
        // chain. A framework that adds or collapses a wrapping level on its own entry point alone
        // is what this reads, and it is invisible above, where both are plain ART.
        Conversions.agrees(
                "hooked: both entry points report the target exception the same way",
                chainCaller(ctx, null),
                thrower,
                null);
        Conversions.agrees(
                "hooked: and the nested one",
                chainCaller(ctx, null),
                nested,
                null);
    }

    /** Trace notation: {@code H>M>L>O<L<M<H} is enter H, M, L, the origin, then the unwind. */
    private static void filteredChainOrdering(Suite.Ctx ctx) throws Throwable {
        Object chained = ctx.fix.make("Chained");
        Method origin = ctx.fix.method("Chained", "origin");
        installChain(ctx, origin);

        traceIs(ctx, "invoke", "H>M>L>O<L<M<H", origin, Invoker.Type.Chain.FULL, chained, false);
        traceIs(
                ctx,
                "invoke",
                "M>L>O<L<M",
                origin,
                new Invoker.Type.Chain(XposedInterface.PRIORITY_DEFAULT),
                chained,
                false);
        traceIs(
                ctx,
                "invoke",
                "L>O<L",
                origin,
                new Invoker.Type.Chain(XposedInterface.PRIORITY_LOWEST),
                chained,
                false);
        traceIs(ctx, "invoke", "O", origin, Invoker.Type.ORIGIN, chained, false);

        traceIs(
                ctx, "invokeSpecial", "H>M>L>O<L<M<H", origin, Invoker.Type.Chain.FULL, chained,
                true);
        traceIs(
                ctx,
                "invokeSpecial",
                "M>L>O<L<M",
                origin,
                new Invoker.Type.Chain(XposedInterface.PRIORITY_DEFAULT),
                chained,
                true);
        traceIs(
                ctx,
                "invokeSpecial",
                "L>O<L",
                origin,
                new Invoker.Type.Chain(XposedInterface.PRIORITY_LOWEST),
                chained,
                true);
        traceIs(ctx, "invokeSpecial", "O", origin, Invoker.Type.ORIGIN, chained, true);
    }

    // ---- shared helpers -------------------------------------------------------------------

    static void installChain(Suite.Ctx ctx, Method origin) {
        ctx.hook(origin, XposedInterface.PRIORITY_HIGHEST, Hookers.trace("H"));
        ctx.hook(origin, XposedInterface.PRIORITY_DEFAULT, Hookers.trace("M"));
        ctx.hook(origin, XposedInterface.PRIORITY_LOWEST, Hookers.trace("L"));
    }

    private static void traceIs(
            Suite.Ctx ctx,
            String entry,
            String expected,
            Method origin,
            Invoker.Type type,
            Object receiver,
            boolean special)
            throws Throwable {
        Marks.take();
        ctx.step(entry + " with " + type);
        Invoker<?, Method> invoker = ctx.invoker(origin, type);
        if (special) {
            invoker.invokeSpecial(receiver);
        } else {
            invoker.invoke(receiver);
        }
        String actual = Marks.take();
        Check.eq(entry + " trace with " + type, expected, actual);
    }

    private static void plainThrow(String label, Invoker<?, Method> invoker) {
        Throwable thrown =
                Check.expectExactly(
                        InvocationTargetException.class,
                        label + " wraps the target exception",
                        () -> invoker.invoke(null));
        Check.eq(label + " cause", IllegalStateException.class, thrown.getCause().getClass());
        Check.eq(label + " cause message", "target-blew-up", thrown.getCause().getMessage());
    }

    private static void nestedThrow(String label, Invoker<?, Method> invoker) {
        Throwable thrown =
                Check.expectExactly(
                        InvocationTargetException.class,
                        label + " nested ITE",
                        () -> invoker.invoke(null));
        Check.eq(
                label + " nested ITE cause",
                InvocationTargetException.class,
                thrown.getCause().getClass());
        Check.eq(
                label + " nested ITE inner cause",
                IllegalStateException.class,
                thrown.getCause().getCause().getClass());
        Check.eq(
                label + " nested ITE inner message",
                "inner",
                thrown.getCause().getCause().getMessage());
    }

    private static Method echo(Suite.Ctx ctx, String name, Class<?> parameter) {
        return ctx.fix.method("Prims", name, parameter);
    }

    private static void hookEveryEcho(Suite.Ctx ctx) {
        String[] names = {
            "echoBoolean", "echoByte", "echoChar", "echoShort", "echoInt", "echoLong", "echoFloat",
            "echoDouble", "echoRef", "echoArray"
        };
        Class<?>[] parameters = {
            boolean.class, byte.class, char.class, short.class, int.class, long.class, float.class,
            double.class, CharSequence.class, int[].class
        };
        for (int i = 0; i < names.length; i++) {
            ctx.hook(ctx.fix.method("Prims", names[i], parameters[i]), chain -> chain.proceed());
        }
    }

    static Conversions.Caller originCaller(Suite.Ctx ctx, Object receiver) {
        return (method, arguments) ->
                ctx.invoker(method, Invoker.Type.ORIGIN).invoke(receiver, arguments);
    }

    static Conversions.Caller chainCaller(Suite.Ctx ctx, Object receiver) {
        return (method, arguments) -> ctx.invoker(method).invoke(receiver, arguments);
    }

    static Conversions.Caller specialCaller(Suite.Ctx ctx, Object receiver, Invoker.Type type) {
        return (method, arguments) -> {
            Invoker<?, Method> invoker =
                    type == null ? ctx.invoker(method) : ctx.invoker(method, type);
            return invoker.invokeSpecial(receiver, arguments);
        };
    }
}
