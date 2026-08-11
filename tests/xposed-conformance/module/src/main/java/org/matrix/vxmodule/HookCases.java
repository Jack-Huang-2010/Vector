package org.matrix.vxmodule;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Invoker;

/** What can be hooked, and what a hooker's exceptions do. */
final class HookCases {

    /** Calls per phase of the intrinsic case; lsplant's own drives 10000 across two of them. */
    private static final int INTRINSIC_ROUNDS = 5000;

    private HookCases() {}

    static void register(Suite suite) {
        suite.add("proxy-generated-method", "proxy generated method", HookCases::proxyMethod);
        suite.add("class-initializer", "class initializer", HookCases::classInitializer);
        suite.add("native-resolved", "resolved Java native method", HookCases::resolvedNative);
        suite.add(
                "native-unresolved",
                "unresolved Java native method",
                HookCases::unresolvedNative);
        suite.add(
                "native-dynamic",
                "dynamically registered native method",
                HookCases::dynamicallyRegisteredNative);
        suite.add("framework-intrinsic", "framework intrinsic method", HookCases::intrinsic);
        suite.add("unhook-idempotent", "unhook is idempotent", HookCases::unhookIsIdempotent);
        suite.add(
                "protective-before",
                "protective: hooker throws before proceed",
                HookCases::protectiveBeforeProceed);
        suite.add(
                "protective-after",
                "protective: hooker throws after proceed",
                HookCases::protectiveAfterProceed);
        suite.add(
                "protective-reproceed",
                "protective: failed then successful proceed",
                HookCases::protectiveFailedThenSuccessfulProceed);
        suite.add(
                "protective-proceed-propagates",
                "protective: exception from proceed propagates",
                HookCases::protectiveProceedThrows);
        suite.add("passthrough-exception", "passthrough exception", HookCases::passthrough);
    }

    /** A runtime-generated method has no dex of its own, and is hookable all the same. */
    private static void proxyMethod(Suite.Ctx ctx) throws Throwable {
        Class<?> iface = ctx.fix.cls("ProxyIface");
        InvocationHandler handler = (proxy, method, args) -> "PROXY:" + args[0];
        Object instance = Proxy.newProxyInstance(ctx.loader, new Class<?>[] {iface}, handler);
        Method declared = instance.getClass().getDeclaredMethod("call", String.class);
        Method through = iface.getMethod("call", String.class);

        Check.eq("unhooked proxy", "PROXY:x", Fix.call(through, instance, "x"));
        ctx.hook(declared, Hookers.wrap("P"));
        Check.eq("hooked proxy", "P(PROXY:x)", Fix.call(through, instance, "x"));
    }

    /**
     * The static initializer is specified to arrive as a regular static no-argument method: no
     * receiver, no arguments, and a proceed that answers null.
     *
     * <p>Three fixtures, because a framework that locates {@code <clinit>} by finding the hole in
     * the class's ArtMethod array finds a different hole in each: a plain class carries only its
     * own methods, an enum carries the synthetic members that go with {@code $VALUES}, and a class
     * whose nest D8 had to desugar carries a synthetic accessor of its own.
     */
    private static void classInitializer(Suite.Ctx ctx) throws Throwable {
        initializerIsHookable(ctx, "ClinitProbe");
        initializerIsHookable(ctx, "ClinitEnum");
        initializerIsHookable(ctx, "ClinitNest");
    }

    private static void initializerIsHookable(Suite.Ctx ctx, String fixture) throws Throwable {
        Class<?> probe = ctx.fix.cls(fixture);
        requireShape(fixture, probe);
        Marks.take();
        Object[] seen = new Object[3];
        ctx.step("hooking the initializer of " + fixture);
        ctx.keep(
                ctx.xposed
                        .hookClassInitializer(probe)
                        .intercept(
                                chain -> {
                                    seen[0] = chain.getThisObject();
                                    seen[1] = chain.getArgs();
                                    seen[2] = chain.proceed();
                                    Marks.mark("HOOK");
                                    return null;
                                }));

        Class.forName(probe.getName(), true, ctx.loader);

        Check.eq(
                fixture + ": the initializer ran once, inside the hook",
                "CLINITHOOK",
                Marks.take());
        Check.eq(fixture + ": thisObject is null", null, seen[0]);
        Check.yes(fixture + ": args are empty", ((List<?>) seen[1]).isEmpty());
        Check.eq(fixture + ": proceed answers null", null, seen[2]);
        Check.eq(fixture + ": the class is initialized", "INIT", probe.getField("value").get(null));
    }

    /**
     * A fixture is only a second or a third shape if the compiler really gave it one, and nothing
     * downstream would notice if it had not - a ClinitNest with no synthetic accessor is a copy of
     * ClinitProbe wearing another name, and its green row would be a duplicate claiming to be
     * coverage. So each fixture proves its own shape before the case leans on it, and one that lost
     * its shape reports SETUP rather than passing.
     *
     * <p>Reflection over declared members does not initialize a class, so asking is free here.
     */
    private static void requireShape(String fixture, Class<?> probe) {
        switch (fixture) {
            case "ClinitEnum" -> {
                // The synthetic member is $VALUES, which is what this <clinit> builds.
                if (!probe.isEnum() || !anySynthetic(probe.getDeclaredFields())) {
                    throw new Broken(
                            fixture
                                    + " is not an enum carrying a synthetic field, so it is the"
                                    + " same ArtMethod shape as ClinitProbe and its row would be a"
                                    + " duplicate");
                }
            }
            case "ClinitNest" -> {
                // Dex has no nestmates, so D8 rewrites every private cross-nest access into a
                // synthetic bridge in the class that declares the member: one here for
                // outerSecret, one in Inner for secret.
                if (!anySynthetic(probe.getDeclaredMethods())) {
                    throw new Broken(
                            fixture
                                    + " declares no synthetic accessor, so D8 did not desugar the"
                                    + " nest into one and the fixture is the same ArtMethod shape"
                                    + " as ClinitProbe");
                }
            }
            default -> {}
        }
    }

    private static boolean anySynthetic(Member[] members) {
        for (Member member : members) {
            if (member.isSynthetic()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hooked after a call of its own has resolved the {@code Java_} symbol, so the binding already
     * in place is what the hook lands on.
     */
    private static void resolvedNative(Suite.Ctx ctx) throws Throwable {
        loadNatives(ctx);
        Method resolved = ctx.fix.method("Natives", "resolvedNative");

        ctx.step("resolving the binding before the hook");
        Check.eq("baseline", "RESOLVED", Fix.call(resolved, null));

        ctx.hook(resolved, Hookers.wrap("R"));
        Check.eq("hooked after its binding resolved", "R(RESOLVED)", Fix.call(resolved, null));
    }

    /**
     * Never called before the hook, so the hook lands while the dlsym lookup stub is still in place
     * and the binding is resolved for the first time from inside a hooked method. Its own case
     * rather than a second half of the resolved one, because each case gets a fresh process and
     * calling it once would spend the fixture.
     */
    private static void unresolvedNative(Suite.Ctx ctx) throws Throwable {
        loadNatives(ctx);
        Method unresolved = ctx.fix.method("Natives", "unresolvedNative");

        ctx.hook(unresolved, Hookers.wrap("U"));
        Check.eq("hooked before its binding resolved", "U(UNRESOLVED)", Fix.call(unresolved, null));
    }

    /**
     * A method bound by RegisterNatives has no symbol to fall back on, so a hook that loses the
     * binding cannot hide: the call throws UnsatisfiedLinkError. Both orders are driven, because
     * registering before the hook and registering after it reach the binding by different routes.
     *
     * <p>{@code twinNative} is what makes the case falsifiable rather than merely red-or-green. It
     * is registered from JNI_OnLoad exactly like {@code dynamicNative} and hooked and asserted the
     * same way, but it also carries a {@code Java_} symbol answering a <b>different</b> string - so
     * a hook that lost the registered pointer answers {@code SYMBOL} here where it throws over
     * there. A run can therefore separate "hooking a native method is broken", which fails both,
     * from "the hook lost the registered binding", which the twin reports as the wrong answer and
     * the symbol-less method as an UnsatisfiedLinkError.
     *
     * <p>What comes after the two orders is the part they alone do not reach: the backup an Origin
     * invoker calls through, a second RegisterNatives over a hooked binding - to a second
     * implementation, so the value says which of the two registrations the call found - and what
     * unhook leaves behind.
     */
    private static void dynamicallyRegisteredNative(Suite.Ctx ctx) throws Throwable {
        loadNatives(ctx);
        Method dynamic = ctx.fix.method("Natives", "dynamicNative");
        Method twin = ctx.fix.method("Natives", "twinNative");
        Method late = ctx.fix.method("Natives", "lateNative");
        Method registerLate = ctx.fix.method("Natives", "registerLate");
        Method registerLateAgain = ctx.fix.method("Natives", "registerLateAgain");

        Check.eq("baseline before any hook", "DYNAMIC", Fix.call(dynamic, null));
        Check.eq("the twin answers its registration and not its symbol", "TWIN",
                Fix.call(twin, null));

        ctx.step("hooking a method bound before the hook");
        HookHandle onDynamic = ctx.hook(dynamic, Hookers.wrap("D"));
        ctx.hook(twin, Hookers.wrap("T"));
        Check.eq("registered before the hook", "D(DYNAMIC)", Fix.call(dynamic, null));
        Check.eq("the twin still answers its registration", "T(TWIN)", Fix.call(twin, null));

        ctx.step("binding a method after it was hooked");
        ctx.hook(late, Hookers.wrap("L"));
        Fix.call(registerLate, null);
        Check.eq("registered after the hook", "L(LATE)", Fix.call(late, null));

        // "Invokes the original executable, skipping all hooks" reaches the implementation through
        // the backup rather than through the chain. A symbol-less method reads that path as an
        // UnsatisfiedLinkError when the backup did not carry the registered pointer; the twin reads
        // it as SYMBOL, which is the same defect saying its own name.
        ctx.step("reaching the registered implementation through Type.ORIGIN");
        Check.eq(
                "Origin invoke reaches the implementation registered before the hook",
                "DYNAMIC",
                ctx.invoker(dynamic, Invoker.Type.ORIGIN).invoke(null));
        Check.eq(
                "Origin invoke on the twin reaches the registration, not the symbol",
                "TWIN",
                ctx.invoker(twin, Invoker.Type.ORIGIN).invoke(null));
        Check.eq(
                "Origin invoke reaches the one registered after it",
                "LATE",
                ctx.invoker(late, Invoker.Type.ORIGIN).invoke(null));

        // RegisterNatives over a method that is already hooked and already bound, to a second
        // implementation: the answer is what says whether the runtime wrote the new pointer into
        // the ArtMethod the call finds, or wrote it somewhere the call never looks. Re-registering
        // the same pointer would answer LATE either way and prove nothing.
        ctx.step("registering again over a hooked binding");
        Fix.call(registerLateAgain, null);
        Check.eq("the second registration is what the call finds", "L(LATE2)",
                Fix.call(late, null));

        // "Cancels the hook" has to leave the binding behind it, and a symbol-less method cannot
        // paper over an unhook that dropped the registered pointer.
        ctx.step("unhooking");
        onDynamic.unhook();
        Check.eq("the binding survives the unhook", "DYNAMIC", Fix.call(dynamic, null));
    }

    /**
     * ART marks some boot-classpath methods as intrinsics and compiles calls to them into inline
     * code, so a hook on one only fires if the framework told the runtime to stop doing that.
     *
     * <p>{@code Integer.reverse} is the one driven here. It is in ART's {@code intrinsics_list.h}
     * as {@code IntegerReverse}, it lowers to a single instruction, and nothing in the platform or
     * in the framework's own dispatch path calls it - unlike {@code StringBuilder.toString}, where
     * a hooker that builds a message would call the method it is hooking.
     *
     * <p>What comes back is checked against {@link #referenceSum}, computed without {@code
     * Integer.reverse} at all. Comparing the two phases to each other instead would compare a
     * deterministic function's answer to itself and hold for any framework, including one whose
     * hooked intrinsic returns a wrong-but-consistent value.
     */
    private static void intrinsic(Suite.Ctx ctx) throws Throwable {
        refuseWhereSubstitutionCannotHappen(ctx);

        Method reverse = Integer.class.getDeclaredMethod("reverse", int.class);
        int[] hits = new int[1];
        int reference = referenceSum(INTRINSIC_ROUNDS);

        ctx.step("hooking " + reverse);
        Throwable refused = Check.capture(() -> ctx.hook(reverse, Hookers.counting(hits)));
        if (refused != null) {
            throw new AssertionError("framework method hook refused: " + Check.show(refused));
        }

        ctx.step("driving " + INTRINSIC_ROUNDS + " calls from a caller that is not compiled yet");
        int cold = spin(INTRINSIC_ROUNDS);
        Check.eq("the hook does not change the answer", reference, cold);
        Check.yes("framework method hook fired: " + hits[0], hits[0] >= INTRINSIC_ROUNDS);

        // The loop above is what makes the caller hot; this is what gives the JIT thread time to
        // finish compiling it, so the second phase enters compiled code from the top. An intrinsic
        // that is still marked as one is substituted there and never reaches the hook.
        ctx.step("waiting for the JIT to compile the caller");
        Thread.sleep(5000);

        int before = hits[0];
        ctx.step("driving " + INTRINSIC_ROUNDS + " more from the compiled caller");
        int hot = spin(INTRINSIC_ROUNDS);
        Check.eq("the compiled caller does not change it either", reference, hot);
        Check.yes(
                "framework method hook still fires from a compiled caller: "
                        + (hits[0] - before)
                        + " of "
                        + INTRINSIC_ROUNDS,
                hits[0] - before >= INTRINSIC_ROUNDS);
    }

    /**
     * The caller. It accumulates, because an intrinsic declared to have no side effects and to
     * throw nothing is one the compiler deletes outright when nothing reads its result, and it
     * varies its argument, because a loop-invariant one would be hoisted out of the loop.
     */
    private static int spin(int rounds) {
        int acc = 0;
        for (int i = 1; i <= rounds; i++) {
            acc += Integer.reverse(i);
        }
        return acc;
    }

    /**
     * The same sum, computed without going anywhere near {@code Integer.reverse}, so that what came
     * back out of the hooked calls is checked against a value no hook was installed on. It is not
     * {@code spin()} run before the hook, because that would leave the caller warm for the phase
     * this case needs cold.
     */
    private static int referenceSum(int rounds) {
        int acc = 0;
        for (int i = 1; i <= rounds; i++) {
            int reversed = 0;
            int bits = i;
            for (int bit = 0; bit < 32; bit++) {
                reversed = (reversed << 1) | (bits & 1);
                bits >>>= 1;
            }
            acc += reversed;
        }
        return acc;
    }

    /**
     * What this establishes is narrow, and so is what a green row above it is worth: that neither
     * of the two inputs readable from Java makes the process Java-debuggable. ART substitutes an
     * intrinsic only where it compiles the caller and it compiles nothing in a Java-debuggable
     * process, so a hook that fires in one fires because nothing inlined it, whatever the framework
     * did - and both the app's own flag and the build type feed that. An eng or userdebug build
     * sets {@code ro.debuggable}, which makes every process on it Java-debuggable whatever the APK
     * says, and the emulator images this suite otherwise runs on are userdebug. {@code Build.TYPE}
     * is the public reading of that property; {@code Build.IS_DEBUGGABLE} is not public API.
     *
     * <p>It does <b>not</b> establish that ART substituted anything in this run - nothing
     * observable from Java does. A case that gets past here reports that the hook fired somewhere
     * substitution was possible, not that a substitution was attempted and beaten.
     */
    private static void refuseWhereSubstitutionCannotHappen(Suite.Ctx ctx) {
        Object context;
        try {
            context = ctx.fix.app("org.matrix.vxtarget.Bridge").getField("context").get(null);
        } catch (ReflectiveOperationException e) {
            throw new Broken("cannot read the app's context", e);
        }
        if (context == null) {
            throw new Broken("the app never handed over a context");
        }
        ApplicationInfo info = ((Context) context).getApplicationInfo();
        if ((info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            throw new Broken(
                    "the target is debuggable, so ART substitutes no intrinsics in it and a hook"
                            + " that fires there says nothing; the case needs a target built with"
                            + " isDebuggable=false, which costs CheckJNI everywhere else");
        }
        if (!"user".equals(Build.TYPE)) {
            throw new Broken(
                    "this is a "
                            + Build.TYPE
                            + " build, so ro.debuggable is set and every process on it is"
                            + " Java-debuggable whatever the APK says; the case needs a user"
                            + " build");
        }
    }

    private static void unhookIsIdempotent(Suite.Ctx ctx) throws Throwable {
        Object sample = ctx.fix.make("Sample");
        Method greet = ctx.fix.method("Sample", "greet", String.class);
        HookHandle handle = ctx.hook(greet, Hookers.wrap("A"));

        Check.eq("hooked", "A(greet:x)", Fix.call(greet, sample, "x"));
        handle.unhook();
        Check.eq("unhooked", "greet:x", Fix.call(greet, sample, "x"));
        Check.none("unhook again", handle::unhook);
        Check.eq("still unhooked", "greet:x", Fix.call(greet, sample, "x"));
    }

    /**
     * "If the exception is thrown before proceed, the framework will continue the chain without
     * the hook" - so the interceptor below it still runs, and so does the original.
     */
    private static void protectiveBeforeProceed(Suite.Ctx ctx) throws Throwable {
        Object chained = ctx.fix.make("Chained");
        Method origin = ctx.fix.method("Chained", "origin");
        Marks.take();

        ctx.hook(origin, XposedInterface.PRIORITY_HIGHEST, ExceptionMode.DEFAULT,
                Hookers.throwingBefore("crash-before-proceed"));
        ctx.hook(origin, XposedInterface.PRIORITY_DEFAULT, ExceptionMode.DEFAULT,
                Hookers.trace("M"));

        Check.eq("the call proceeds as if no hook exists", "O", Fix.call(origin, chained));
        Check.eq("the rest of the chain still ran", "M>O<M", Marks.take());
    }

    /**
     * "If the exception is thrown after proceed, the framework will return the value / exception
     * proceeded as the result."
     */
    private static void protectiveAfterProceed(Suite.Ctx ctx) throws Throwable {
        Object sample = ctx.fix.make("Sample");
        Method greet = ctx.fix.method("Sample", "greet", String.class);
        ctx.hook(greet, XposedInterface.PRIORITY_DEFAULT, ExceptionMode.DEFAULT,
                Hookers.throwingAfter("crash-after-proceed"));

        Check.eq("the proceeded value is the result", "greet:x", Fix.call(greet, sample, "x"));

        Method thrower = ctx.fix.method("Sample", "throwsIllegalState");
        ctx.hook(thrower, XposedInterface.PRIORITY_DEFAULT, ExceptionMode.DEFAULT,
                chain -> {
                    Check.capture(chain::proceed);
                    throw new IllegalStateException("crash-after-proceed");
                });
        Throwable thrown =
                Check.expectExactly(
                        IllegalStateException.class,
                        "the proceeded exception is the result",
                        () -> Fix.call(thrower, null));
        Check.eq("and it is the original's", "target-blew-up", thrown.getMessage());
    }

    /**
     * A re-entrant proceed: the hooker proceeds, the proceed throws, the hooker swallows it and
     * proceeds again.
     *
     * <p>PROTECTIVE catches what the <b>hooker</b> throws, and this hooker throws nothing, so none
     * of that engages. What does is the other sentence - "exceptions thrown by proceed will always
     * be propagated" - and the only place a proceed exception can be propagated to is the
     * interceptor that called it, unchanged and unwrapped. That half is quotable and asserted as
     * such, together with the first proceed reaching the interceptor below and then the origin.
     *
     * <p>The second proceed is not. The interface forbids reuse only "after {@code
     * Hooker#intercept(Chain)} ends" and {@code proceed} says only "proceeds to the next
     * interceptor in the chain" - neither sentence says a repeat call inside one intercept is
     * allowed, nor, if it is, whether it re-enters the interceptors below or only the origin. So
     * everything that turns on it is reported as a reading, and last, so a disagreement about the
     * interface does not swallow the checks that quote it.
     */
    private static void protectiveFailedThenSuccessfulProceed(Suite.Ctx ctx) throws Throwable {
        Object flaky = ctx.fix.make("Flaky");
        Method once = ctx.fix.method("Flaky", "once");
        Marks.take();

        Throwable[] swallowed = new Throwable[1];
        Object[] answered = new Object[1];
        ctx.hook(once, XposedInterface.PRIORITY_HIGHEST, ExceptionMode.DEFAULT,
                chain -> {
                    Marks.mark("R>");
                    swallowed[0] = Check.capture(chain::proceed);
                    Marks.mark("|");
                    try {
                        return chain.proceed();
                    } finally {
                        Marks.mark("<R");
                    }
                });
        ctx.hook(once, XposedInterface.PRIORITY_DEFAULT, ExceptionMode.DEFAULT, Hookers.trace("M"));

        Throwable refused = Check.capture(() -> answered[0] = Fix.call(once, flaky));
        String trace = Marks.take();
        // The whole story in one line, because a hooker that never ran leaves the assertions below
        // with nothing to say and the driver prints only their message.
        String outcome =
                (refused == null
                                ? "answered " + Check.show(answered[0])
                                : "threw " + Check.show(refused))
                        + " and traced "
                        + trace;

        Check.yes("the first proceed threw into the hooker; the call " + outcome,
                swallowed[0] != null);
        Check.eq("unwrapped", IllegalStateException.class, swallowed[0].getClass());
        Check.eq("and unchanged", "first-proceed-blew-up", swallowed[0].getMessage());
        int split = trace.indexOf('|');
        Check.yes("the first proceed threw back into the hooker and not past it: " + outcome,
                split >= 0);
        Check.eq(
                "the first proceed entered the rest of the chain",
                "R>M>O1<M|",
                trace.substring(0, split + 1));

        Check.reading(
                "Chain's \"cannot be reused after Hooker#intercept(Chain) ends\", and proceed's"
                        + " \"the next interceptor in the chain\" on a second call",
                () -> {
                    Check.none(
                            "a second proceed inside the same intercept",
                            () -> {
                                if (refused != null) {
                                    throw refused;
                                }
                            });
                    Check.eq("the second proceed's value is the result", "OK", answered[0]);
                    Check.eq(
                            "it re-entered the whole rest of the chain",
                            "M>O2<M<R",
                            trace.substring(split + 1));
                    Check.eq("the origin ran once per proceed", 2, Fix.field(flaky, "calls"));
                });
    }

    /** "Exceptions thrown by proceed will always be propagated." */
    private static void protectiveProceedThrows(Suite.Ctx ctx) throws Throwable {
        Method thrower = ctx.fix.method("Sample", "throwsIllegalState");
        ctx.hook(thrower, XposedInterface.PRIORITY_DEFAULT, ExceptionMode.DEFAULT,
                chain -> chain.proceed());

        Throwable thrown =
                Check.expectExactly(
                        IllegalStateException.class,
                        "an exception from proceed reaches the caller",
                        () -> Fix.call(thrower, null));
        Check.eq("unchanged", "target-blew-up", thrown.getMessage());
    }

    /** PASSTHROUGH: the hooker's own exception is the caller's. */
    private static void passthrough(Suite.Ctx ctx) throws Throwable {
        Object sample = ctx.fix.make("Sample");
        Method greet = ctx.fix.method("Sample", "greet", String.class);
        ctx.hook(greet, XposedInterface.PRIORITY_DEFAULT, ExceptionMode.PASSTHROUGH,
                Hookers.throwingBefore("passthrough-please"));

        Throwable thrown =
                Check.expectExactly(
                        IllegalStateException.class,
                        "the hooker's exception reaches the caller",
                        () -> Fix.call(greet, sample, "x"));
        Check.eq("unchanged", "passthrough-please", thrown.getMessage());
    }

    /**
     * The app's own library, loaded before any hook exists: a failure here is the harness losing
     * its footing rather than a result about the framework. Shared with the native module API case,
     * which reads its answer back through the same library.
     */
    static void loadNatives(Suite.Ctx ctx) {
        Throwable failed =
                Check.capture(() -> Fix.call(ctx.fix.method("Natives", "ensureLoaded"), null));
        if (failed != null) {
            throw new Broken("the app could not load its own JNI library: " + Check.show(failed));
        }
    }
}
