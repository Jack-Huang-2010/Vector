package org.matrix.vxmodule;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.CtorInvoker;
import io.github.libxposed.api.XposedInterface.Invoker;

/** CtorInvoker: newInstance, newInstanceSpecial, and the constructor as a method. */
final class CtorCases {

    private CtorCases() {}

    static void register(Suite suite) {
        suite.add(
                "ctor-invoker-access",
                "CtorInvoker bypasses constructor access checks",
                CtorCases::bypassesAccessChecks);
        suite.add("ctor-invoker", "constructor invoker", CtorCases::constructorInvoker);
        suite.add(
                "invoker-ite-ctor",
                "constructor Invoker target exception wrapping",
                CtorCases::targetExceptionWrapping);
        suite.add(
                "ctor-invoker-order",
                "constructor Invoker modes and ordering",
                CtorCases::modesAndOrdering);
    }

    private static void bypassesAccessChecks(Suite.Ctx ctx) throws Throwable {
        Constructor<?> priv = ctx.fix.ctor("Ctors", long.class);

        // The documented difference, without which "the invoker reached it" would also be
        // satisfied by a runtime that checks nothing at all.
        Check.contrast(
                IllegalAccessException.class,
                "reflection cannot reach the private constructor",
                () -> priv.newInstance(5L));

        Object built = ctx.invoker(priv).newInstance(5L);
        Check.eq("a private constructor is reachable", "private:5", Fix.tag(built));
        Check.eq(
                "and through Type.ORIGIN too",
                "private:6",
                Fix.tag(ctx.invoker(priv, Invoker.Type.ORIGIN).newInstance(6L)));
    }

    private static void constructorInvoker(Suite.Ctx ctx) throws Throwable {
        Constructor<?> byString = ctx.fix.ctor("Ctors", String.class);
        Constructor<?> byInt = ctx.fix.ctor("Ctors", int.class);
        Constructor<?> noArgs = ctx.fix.ctor("Ctors");
        Class<?> sub = ctx.fix.cls("SubCtor");

        Check.eq("newInstance", "tag", Fix.tag(ctx.invoker(byString).newInstance("tag")));
        Check.eq("newInstance with no arguments", "default", Fix.tag(ctx.invoker(noArgs).newInstance()));

        // The conversion matrix reaches constructors too, and Constructor#newInstance - what
        // CtorInvoker#newInstance is documented against - decides each row alongside the table.
        Check.eq(
                "Constructor#newInstance widens the same way",
                "int:7",
                Fix.tag(Conversions.reflectively(byInt, (byte) 7)));
        Check.eq(
                "widening into a constructor",
                "int:7",
                Fix.tag(ctx.invoker(byInt).newInstance((byte) 7)));
        Check.eq(
                "identity into a constructor", "int:8", Fix.tag(ctx.invoker(byInt).newInstance(8)));
        refusesLike("a narrowing argument", ctx.invoker(byInt), byInt, 5_000_000_000L);
        refusesLike("a mistyped argument", ctx.invoker(byString), byString, 42);
        refusesLike("the wrong arity", ctx.invoker(byString), byString);

        // "Creates a new instance of the given subclass, but initializes it with a parent
        // constructor. This could leave the object in an invalid state" - so it has to.
        Object special = ctx.invoker(byString).newInstanceSpecial(sub, "special");
        Check.yes("newInstanceSpecial answers an instance of the subclass", sub.isInstance(special));
        Check.eq("the parent constructor ran", "special", Fix.tag(special));
        Check.eq("the subclass constructor did not", null, Fix.field(special, "subField"));

        // An abstract class has a reachable constructor and no instances.
        Constructor<?> abstractCtor = ctx.fix.ctor("AbstractCtor");
        Check.expectExactly(
                InstantiationException.class,
                "Constructor#newInstance refuses an abstract class",
                () -> Conversions.reflectively(abstractCtor));
        Check.expectExactly(
                InstantiationException.class,
                "newInstance refuses an abstract class",
                () -> ctx.invoker(abstractCtor).newInstance());

        // "Invokes the method (or the constructor as a method)": the body runs against a receiver
        // that already exists, and the answer is null.
        Object existing = ctx.invoker(byString).newInstance("first");
        Check.eq("the constructor as a method answers null", null, ctx.invoker(byString).invoke(existing, "second"));
        Check.eq("and it ran against the receiver", "second", Fix.tag(existing));

        // A reading, not the spec, and last in the case for that reason: a failure here ends the
        // case, and everything above it is quotable. The javadoc says only "Creates a new instance
        // of the given subclass" and never says what a class which is not one costs.
        // IllegalArgumentException is what Method#invoke answers for a receiver of the wrong type
        // and the invoker is documented against Method#invoke, but ClassCastException and
        // InstantiationException are both defensible.
        Check.reading(
                "newInstanceSpecial's \"the given subclass\"",
                () ->
                        Check.expectExactly(
                                IllegalArgumentException.class,
                                "newInstanceSpecial rejects a class that is not a subclass",
                                () -> ctx.invoker(byString).newInstanceSpecial(String.class, "x")));
    }

    private static void targetExceptionWrapping(Suite.Ctx ctx) throws Throwable {
        Constructor<?> thrower = ctx.fix.ctor("Ctors", boolean.class);

        wraps("unhooked Chain newInstance", ctx.invoker(thrower));
        wraps("unhooked Origin newInstance", ctx.invoker(thrower, Invoker.Type.ORIGIN));

        ctx.hook(thrower, chain -> chain.proceed());
        wraps("hooked Chain newInstance", ctx.invoker(thrower));
        wraps("hooked Origin newInstance", ctx.invoker(thrower, Invoker.Type.ORIGIN));
    }

    /** The constructor half of the trace notation, through both entry points. */
    private static void modesAndOrdering(Suite.Ctx ctx) throws Throwable {
        Constructor<?> ctor = ctx.fix.ctor("ChainedCtor", int.class);
        Class<?> sub = ctx.fix.cls("ChainedCtorSub");
        ctx.hook(ctor, XposedInterface.PRIORITY_HIGHEST, Hookers.trace("H"));
        ctx.hook(ctor, XposedInterface.PRIORITY_DEFAULT, Hookers.trace("M"));
        ctx.hook(ctor, XposedInterface.PRIORITY_LOWEST, Hookers.trace("L"));

        Marks.take();
        Object full = ctx.invoker(ctor, Invoker.Type.Chain.FULL).newInstance(1);
        Check.eq("newInstance trace", "H>M>L>O<L<M<H", Marks.take());
        Check.eq("newInstance ran the constructor", 1, Fix.field(full, "seed"));

        ctx.invoker(ctor, new Invoker.Type.Chain(XposedInterface.PRIORITY_DEFAULT)).newInstance(2);
        Check.eq("newInstance filtered trace", "M>L>O<L<M", Marks.take());

        ctx.invoker(ctor, Invoker.Type.ORIGIN).newInstance(3);
        Check.eq("newInstance Origin trace", "O", Marks.take());

        ctx.step("newInstanceSpecial");
        Object special = ctx.invoker(ctor, Invoker.Type.Chain.FULL).newInstanceSpecial(sub, 4);
        Check.eq("newInstanceSpecial trace", "H>M>L>O<L<M<H", Marks.take());
        Check.yes("newInstanceSpecial answers the subclass", sub.isInstance(special));
        Check.eq("newInstanceSpecial ran the parent constructor", 4, Fix.field(special, "seed"));

        ctx.invoker(ctor, new Invoker.Type.Chain(XposedInterface.PRIORITY_LOWEST))
                .newInstanceSpecial(sub, 5);
        Check.eq("newInstanceSpecial filtered trace", "L>O<L", Marks.take());

        ctx.invoker(ctor, Invoker.Type.ORIGIN).newInstanceSpecial(sub, 6);
        Check.eq("newInstanceSpecial Origin trace", "O", Marks.take());
    }

    /**
     * One construction both are expected to refuse. Reflection's refusal is asserted to be exactly
     * IllegalArgumentException, because that is what the matrix claims; the invoker's is then
     * asserted against reflection's, which carries that constant through - a refusal whose shape
     * matches one asserted to be exactly IllegalArgumentException is exactly one too - and adds the
     * cause chain, which the constant on its own would not see. Stating the constant twice would be
     * an assertion that cannot fail.
     */
    private static void refusesLike(
            String what, CtorInvoker<?> invoker, Constructor<?> ctor, Object... arguments) {
        Throwable oracle =
                Check.expectExactly(
                        IllegalArgumentException.class,
                        "Constructor#newInstance must refuse " + what,
                        () -> Conversions.reflectively(ctor, arguments));
        Throwable thrown =
                Check.expectAny(
                        "newInstance must refuse " + what, () -> invoker.newInstance(arguments));
        Check.eq(
                "newInstance must refuse " + what + " the way Constructor#newInstance refuses it",
                Conversions.shape(oracle),
                Conversions.shape(thrown));
    }

    private static void wraps(String label, CtorInvoker<?> invoker) {
        Throwable thrown =
                Check.expectExactly(
                        InvocationTargetException.class,
                        label + " wraps what the constructor threw",
                        () -> invoker.newInstance(true));
        Check.eq(label + " cause", IllegalStateException.class, thrown.getCause().getClass());
        Check.eq(label + " cause message", "ctor-blew-up", thrown.getCause().getMessage());
    }
}
