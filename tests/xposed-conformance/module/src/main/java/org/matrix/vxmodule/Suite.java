package org.matrix.vxmodule;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.CtorInvoker;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedInterface.Invoker;

/**
 * The case registry, and the one object the hooked app can see.
 *
 * <p>The app calls {@link #run(String)} reflectively for one case at a time and writes the answer
 * where the driver can read it. One case per call is what keeps a case that aborts the runtime
 * from costing anything but its own result.
 */
public final class Suite {

    static final String TAG = "VXConf";

    /** A case body. Failing means throwing; passing means returning. */
    interface Body {
        void run(Ctx ctx) throws Throwable;
    }

    private static final class Entry {

        final String name;
        final Body body;

        Entry(String name, Body body) {
            this.name = name;
            this.body = body;
        }
    }

    private final XposedInterface xposed;
    private final ClassLoader loader;
    private final Fix fix;
    private final Map<String, Entry> cases = new LinkedHashMap<>();

    Suite(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        this.fix = new Fix(loader);
        HookCases.register(this);
        InvokeCases.register(this);
        SpecialCases.register(this);
        CtorCases.register(this);
        ReplaceCases.register(this);
        EnvCases.register(this);
    }

    /** Registers a case. The id is what the driver drives; the name is what the report calls it. */
    void add(String id, String name, Body body) {
        cases.put(id, new Entry(name, body));
    }

    /** {@code PASS|id<TAB>name;id<TAB>name;...} - the driver's copy of the suite order. */
    public String list() {
        StringBuilder listing = new StringBuilder();
        for (Map.Entry<String, Entry> entry : cases.entrySet()) {
            listing.append(entry.getKey()).append('\t').append(entry.getValue().name).append(';');
        }
        return "PASS|" + listing;
    }

    /**
     * Runs one case and answers {@code PASS|}, or one of the three ways a case can not pass:
     * {@code FAIL|why} for the framework doing the wrong thing, {@code READING|why} for an
     * assertion the spec does not actually make, and {@code SETUP|why} for a case the harness could
     * not put together - which is not a conformance answer at all.
     */
    public String run(String id) {
        Entry entry = cases.get(id);
        if (entry == null) {
            return "FAIL|unknown case: " + id;
        }
        Ctx ctx = new Ctx(id, xposed, loader, fix);
        Log.i(TAG, "START " + id);
        try {
            entry.body.run(ctx);
            return "PASS|";
        } catch (Check.Reading reading) {
            return "READING|" + reading.getMessage();
        } catch (Broken broken) {
            return "SETUP|" + describe(broken);
        } catch (Throwable t) {
            return "FAIL|" + describe(t);
        } finally {
            ctx.undoAll();
        }
    }

    /**
     * An assertion carries its whole story in the message; anything else is a surprise, and for a
     * surprise the frame it came from is worth more than the message.
     */
    private static String describe(Throwable t) {
        String rendered = Check.show(t);
        if (t instanceof AssertionError || t.getStackTrace().length == 0) {
            return rendered;
        }
        return rendered + " at " + t.getStackTrace()[0];
    }

    /** Everything a case is handed: the framework, the fixtures, and hook bookkeeping. */
    static final class Ctx {

        final String id;
        final XposedInterface xposed;
        final ClassLoader loader;
        final Fix fix;

        private final List<HookHandle> handles = new ArrayList<>();

        Ctx(String id, XposedInterface xposed, ClassLoader loader, Fix fix) {
            this.id = id;
            this.xposed = xposed;
            this.loader = loader;
            this.fix = fix;
        }

        HookHandle hook(Executable origin, Hooker hooker) {
            return keep(xposed.hook(origin).intercept(hooker));
        }

        HookHandle hook(Executable origin, int priority, Hooker hooker) {
            return keep(xposed.hook(origin).setPriority(priority).intercept(hooker));
        }

        HookHandle hook(Executable origin, int priority, ExceptionMode mode, Hooker hooker) {
            return keep(
                    xposed.hook(origin).setPriority(priority).setExceptionMode(mode)
                            .intercept(hooker));
        }

        HookHandle hookWithId(Executable origin, String hookId, Hooker hooker) {
            return keep(xposed.hook(origin).setId(hookId).intercept(hooker));
        }

        /** Records a handle a case built itself, so it is undone whatever the case does next. */
        HookHandle keep(HookHandle handle) {
            handles.add(handle);
            return handle;
        }

        Invoker<?, Method> invoker(Method method) {
            return xposed.getInvoker(method);
        }

        /** Uses what setType answers rather than the invoker it was called on: the interface says
         * it sets the type and returns something to chain from, not that the two are the same
         * object. */
        Invoker<?, Method> invoker(Method method, Invoker.Type type) {
            return xposed.getInvoker(method).setType(type);
        }

        CtorInvoker<?> invoker(Constructor<?> constructor) {
            return xposed.getInvoker(constructor);
        }

        CtorInvoker<?> invoker(Constructor<?> constructor, Invoker.Type type) {
            return xposed.getInvoker(constructor).setType(type);
        }

        /**
         * A breadcrumb. The only thing left of a case that kills the process is the log, so every
         * step that can take the runtime down says so before it tries.
         */
        void step(String what) {
            Log.i(TAG, "STEP " + id + " " + what);
        }

        void undoAll() {
            for (int i = handles.size() - 1; i >= 0; i--) {
                try {
                    handles.get(i).unhook();
                } catch (Throwable t) {
                    Log.w(TAG, "could not undo a hook of " + id, t);
                }
            }
            handles.clear();
        }
    }
}
