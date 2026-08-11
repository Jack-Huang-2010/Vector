// Compile-only stubs of the legacy Xposed API.
//
// Never packaged. Their whole purpose is to put *type references* to de.robv.android.xposed and
// android.content.res.X* in the module's dex with no definition behind them, so that resolving
// them at runtime has to go through the module class loader - which is where API 102's "modules
// targeting 102 cannot call legacy APIs" rule is enforced, and where the resource API happens to
// be refused along with it.
//
// This is the only form of the probe that survives dex obfuscation. A Class.forName with a literal
// name proves nothing there: the framework rewrites those package names per boot, so the literal
// resolves to nothing whether or not the rule is enforced. A type reference, by contrast, is
// rewritten along with everything else, so the loader is asked for the name the module would
// really use.
plugins { id("java-library") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
