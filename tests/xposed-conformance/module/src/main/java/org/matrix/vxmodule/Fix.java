package org.matrix.vxmodule;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reaches the fixtures in the hooked app.
 *
 * <p>Everything goes through the app's class loader by name: the two APKs share no types, and the
 * Method and Constructor objects a case hands to {@code hook} or {@code getInvoker} have to be the
 * ones a module would really have. In particular nothing here calls {@code setAccessible} - that
 * would quietly satisfy the access checks the invoker is supposed to bypass on its own.
 */
final class Fix {

    private static final String PACKAGE = "org.matrix.vxtarget.fix.";

    private final ClassLoader loader;

    Fix(ClassLoader loader) {
        this.loader = loader;
    }

    /** Loads without initializing, which is what the class-initializer case depends on. */
    Class<?> cls(String simpleName) {
        try {
            return loader.loadClass(PACKAGE + simpleName);
        } catch (ClassNotFoundException e) {
            throw new Broken("no fixture " + simpleName, e);
        }
    }

    Class<?> app(String qualifiedName) {
        try {
            return loader.loadClass(qualifiedName);
        } catch (ClassNotFoundException e) {
            throw new Broken("no app class " + qualifiedName, e);
        }
    }

    Method method(String simpleName, String method, Class<?>... parameters) {
        try {
            return cls(simpleName).getDeclaredMethod(method, parameters);
        } catch (NoSuchMethodException e) {
            throw new Broken("no fixture method " + simpleName + "." + method, e);
        }
    }

    Constructor<?> ctor(String simpleName, Class<?>... parameters) {
        try {
            return cls(simpleName).getDeclaredConstructor(parameters);
        } catch (NoSuchMethodException e) {
            throw new Broken("no fixture constructor " + simpleName, e);
        }
    }

    /** A fresh fixture instance through its public no-argument constructor. */
    Object make(String simpleName) {
        try {
            return cls(simpleName).getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new Broken("cannot instantiate " + simpleName, e);
        }
    }

    /** Plain reflection, for the before-and-after readings a case takes around a hook. */
    static Object call(Method method, Object receiver, Object... arguments) throws Throwable {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }

    /**
     * Every instance read this way came back out of an invoker, so a field that is not there is an
     * answer about the framework rather than a broken fixture - hence an assertion and not a
     * {@link Broken}.
     */
    static Object field(Object instance, String name) {
        try {
            return instance.getClass().getField(name).get(instance);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "cannot read " + name + " on " + Check.show(instance) + ": " + Check.show(e));
        }
    }

    /** The {@code tag} every Ctors fixture records, so a constructor case can say which one ran. */
    static Object tag(Object instance) {
        return field(instance, "tag");
    }
}
