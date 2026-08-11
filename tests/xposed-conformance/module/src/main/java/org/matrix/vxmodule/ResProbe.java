package org.matrix.vxmodule;

import android.content.res.Resources;
import android.content.res.XModuleResources;
import android.content.res.XResources;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;

/**
 * The resource replacement, in the two halves the legacy {@code XResources} API forces it into.
 *
 * <p>None of this is in the 102 interface, which has no resource surface at all; what shapes it is
 * Vector's own legacy extension, and the case that reads it says so with its status.
 *
 * <p>Every reference to the legacy resource types lives here and nowhere else. Their super class is
 * generated on the device, so a class that names them is unusable until the framework has built it,
 * and a case that named them would take its neighbours down with it.
 *
 * <p>The first half runs at module load. Replacing an app's own resource needs an {@code
 * XResources} for that app; the framework only builds one for Resources created after its resource
 * hook is installed; and that hook is installed as a side effect of the first system-wide
 * registration. {@code onPackageLoaded} is the last moment before the app builds its Resources, so
 * the system-wide registration has to happen there - and it registers something real, so that the
 * case can tell a hook that never came up from a layout that did not come back.
 *
 * <p>The second half is the replacement the case is actually about, and it cannot run until the
 * object it attaches to exists, so the case drives it.
 */
final class ResProbe {

    /** What the system-wide half puts in place of {@code android:string/ok}. */
    static final String SYSTEM_WIDE_TEXT = "vx-system-wide";

    private static volatile String modulePath;
    private static volatile String targetPackage;
    private static volatile boolean systemWide;
    private static volatile String report = "not attempted";

    private ResProbe() {}

    static void install(XposedInterface xposed, String target) {
        targetPackage = target;
        try {
            modulePath = xposed.getModuleApplicationInfo().sourceDir;
        } catch (Throwable t) {
            report = "the module APK could not be located: " + Check.show(t);
            return;
        }
        try {
            XResources.setSystemWideReplacement("android", "string", "ok", SYSTEM_WIDE_TEXT);
            systemWide = true;
            report = "registered from " + modulePath;
        } catch (Throwable t) {
            report = "the system-wide registration was refused: " + Check.show(t);
        }
    }

    /** Whether the harness has what it needs; anything else here is the framework's answer. */
    static boolean ready() {
        return modulePath != null;
    }

    static boolean systemWideRegistered() {
        return systemWide;
    }

    static String report() {
        return report;
    }

    /**
     * Whether the framework converted this Resources into one of its own. Asked of the static
     * system Resources it is the one observable proof that the resource hook came up: installing it
     * ends by swapping that object, and {@code XposedInit.hookResources()} returns rather than
     * throwing when the native half refuses to initialise, so the registration succeeding says
     * nothing on its own.
     */
    static boolean converted(Resources resources) {
        // Class#isInstance rather than instanceof: the compile-time XResources is legacystub's, which
        // does not extend Resources, and the two are unrelated types to javac. The class literal is
        // rewritten by the obfuscator exactly like any other reference, so this asks about the real one.
        return XResources.class.isInstance(resources);
    }

    /** The module's own number for the id both layouts declare. */
    static int moduleRootId() {
        return R.id.probe_root;
    }

    /** The module's number for the id only it declares; see res/layout/replacement.xml. */
    static int moduleOnlyId() {
        return R.id.module_only;
    }

    /** The module's number for the attribute both packages declare. */
    static int moduleAttrId() {
        return R.attr.vxProbe;
    }

    /**
     * Puts the module's layout in place of the target's, on the Resources the app itself uses.
     * Answers an empty string, or why it could not.
     */
    static String replace(Resources appResources) {
        try {
            XModuleResources moduleResources = XModuleResources.createInstance(modulePath, null);
            Object forwarder = moduleResources.fwd(R.layout.replacement);
            // Reflection rather than a cast, because the cast is not the interesting part: only
            // XResources declares this method, so a Resources the framework never converted
            // answers with NoSuchMethodException, which is exactly the difference the case has to
            // report. The name survives the daemon's obfuscator, which rewrites class name
            // prefixes and nothing else (daemon/src/main/jni/obfuscation.cpp) - the framework's own
            // JNI lookups of translateResId and translateAttrId by string are the standing proof.
            Method setReplacement =
                    appResources
                            .getClass()
                            .getMethod(
                                    "setReplacement",
                                    String.class,
                                    String.class,
                                    String.class,
                                    Object.class);
            setReplacement.invoke(appResources, targetPackage, "layout", "probe", forwarder);
            return "";
        } catch (Throwable t) {
            return Check.show(t);
        }
    }
}
