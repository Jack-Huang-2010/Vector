package org.matrix.vxmodule;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * The module entry.
 *
 * <p>It installs nothing of its own. All it does is publish the suite where the hooked app can
 * reach it, so that every hook in the run is one a case asked for and undoes again - a leftover
 * hook from a previous case would quietly change what the next one measures.
 */
public class ModuleMain extends XposedModule {

    static final String TAG = "VXConf";

    private static final String TARGET = "org.matrix.vxtarget";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Log.i(
                TAG,
                "module loaded in "
                        + param.getProcessName()
                        + " api="
                        + getApiVersion()
                        + " framework="
                        + getFrameworkName()
                        + " "
                        + getFrameworkVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET.equals(param.getPackageName())) {
            return;
        }
        ClassLoader loader = param.getDefaultClassLoader();
        try {
            Marks.bind(loader);
            loader.loadClass(TARGET + ".Bridge")
                    .getField("suite")
                    .set(null, new Suite(this, loader));
            Log.i(TAG, "suite published into " + param.getPackageName());
        } catch (Throwable t) {
            Log.e(TAG, "could not publish the suite", t);
        }
        // The system-wide half of the resource replacement has to be registered before the app
        // builds its Resources, which is why it happens here rather than in the case that reads it
        // (see ResProbe). It is kept out of the block above because it is the one thing here that
        // reaches the legacy XResources surface: now that the API 102 guard names only de.robv,
        // the module resolves those types instead of being refused, so what used to end in a
        // linkage error is a real registration that installs the framework's resource hook. Every
        // case after this one therefore runs with that hook up - Resources swapped, and
        // ResourcesManager and TypedArray.obtain hooked - and anything that throws out of it must
        // cost this one case its result rather than all of them.
        try {
            ResProbe.install(this, TARGET);
        } catch (Throwable t) {
            Log.w(TAG, "could not install the resource probe", t);
        }
    }
}
