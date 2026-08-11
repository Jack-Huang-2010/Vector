package org.matrix.vxmodule;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;

/** The two atomic replacement forms: by id, and by handle. */
final class ReplaceCases {

    private ReplaceCases() {}

    static void register(Suite suite) {
        suite.add("hook-id-replacement", "hook id replacement", ReplaceCases::byId);
        suite.add("handle-replacement", "handle replacement", ReplaceCases::byHandle);
    }

    /**
     * "A new hook with the same id in the same module on the executable will replace the old one
     * atomically, and the old hook handle will be invalid."
     */
    private static void byId(Suite.Ctx ctx) throws Throwable {
        Object sample = ctx.fix.make("Sample");
        Method greet = ctx.fix.method("Sample", "greet", String.class);

        HookHandle first = ctx.hookWithId(greet, "swap", Hookers.wrap("A"));
        Check.eq("the first hooker runs", "A(greet:x)", Fix.call(greet, sample, "x"));
        Check.eq("the id is readable", "swap", first.getId());

        HookHandle second = ctx.hookWithId(greet, "swap", Hookers.wrap("B"));
        Check.eq(
                "the same id replaces rather than adds",
                "B(greet:x)",
                Fix.call(greet, sample, "x"));
        Check.yes("a new handle came back", first != second);
        Check.eq("the id is kept", "swap", second.getId());
        Check.eq("the executable is kept", greet, second.getExecutable());

        // A reading, not the spec: the interface says the old handle "will be invalid" and that
        // replaceHook throws IllegalStateException when it is, but nothing about what unhook() on
        // it does. Cancelling the hook that replaced it is the only other reading, and it would
        // make atomic replacement useless - which is an argument, not a quotation, so a failure
        // here is a question about the interface. It also ends the case: nothing below runs.
        Check.reading(
                "\"the old hook handle will be invalid\"",
                () -> {
                    Check.expectExactly(
                            IllegalStateException.class,
                            "the superseded handle refuses replaceHook",
                            () -> first.replaceHook(Hookers.wrap("C")));
                    Check.none("the superseded handle still accepts unhook", first::unhook);
                    Check.eq(
                            "and unhooking it did not cancel the replacement",
                            "B(greet:x)",
                            Fix.call(greet, sample, "x"));
                });

        ctx.hookWithId(greet, "other", Hookers.wrap("D"));
        String both = String.valueOf(Fix.call(greet, sample, "x"));
        Check.yes(
                "a different id adds a hook rather than replacing one: " + both,
                both.contains("B(") && both.contains("D("));
    }

    /**
     * "The replacement keeps the executable, priority, exception handling mode, and id of this
     * hook", and the handle it replaced stops being usable.
     */
    private static void byHandle(Suite.Ctx ctx) throws Throwable {
        Object chained = ctx.fix.make("Chained");
        Method origin = ctx.fix.method("Chained", "origin");

        HookHandle high =
                ctx.keep(
                        ctx.xposed
                                .hook(origin)
                                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                                .setId("top")
                                .intercept(Hookers.trace("H")));
        ctx.hook(origin, XposedInterface.PRIORITY_DEFAULT, Hookers.trace("M"));

        Marks.take();
        Fix.call(origin, chained);
        Check.eq("baseline", "H>M>O<M<H", Marks.take());

        HookHandle replaced = ctx.keep(high.replaceHook(Hookers.trace("R")));
        Fix.call(origin, chained);
        Check.eq("the replacement keeps the priority", "R>M>O<M<R", Marks.take());
        Check.eq("the executable is kept", origin, replaced.getExecutable());
        Check.eq("the id is kept", "top", replaced.getId());

        // The same reading as in the id case, and the same consequence: a failure here is about
        // what "no longer valid" means, and the check below it does not run.
        Check.reading(
                "\"this hook handle is no longer valid\"",
                () ->
                        Check.expectExactly(
                                IllegalStateException.class,
                                "the replaced handle is no longer valid",
                                () -> high.replaceHook(Hookers.trace("Z"))));

        replaced.unhook();
        Fix.call(origin, chained);
        Check.eq("unhooking the replacement removes it", "M>O<M", Marks.take());
    }
}
