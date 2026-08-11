package org.matrix.vxmodule;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.view.LayoutInflater;
import android.view.View;

import java.lang.reflect.Method;

/** The three cases that are about the framework's surroundings rather than about hooking. */
final class EnvCases {

    private static final String TARGET = "org.matrix.vxtarget";

    private EnvCases() {}

    static void register(Suite suite) {
        suite.add("legacy-api", "Legacy API/method hook", EnvCases::legacyApi);
        suite.add("native-api", "Native API/function hook", EnvCases::nativeApi);
        suite.add("res-hook", "ResHook/layout replacement", EnvCases::layoutReplacement);
    }

    /**
     * API 102: "Libxposed modules can not call legacy de.robv.android.xposed APIs." The probe is a
     * type reference rather than a Class.forName, because the framework rewrites those names per
     * boot: a literal would resolve to nothing whether or not the rule is enforced.
     */
    private static void legacyApi(Suite.Ctx ctx) {
        Throwable refused = Check.capture(LegacyLink::touch);
        if (refused == null) {
            throw new AssertionError(
                    "a module targeting API 102 reached de.robv.android.xposed.XposedBridge");
        }
        String rendered = Check.show(refused);
        Check.yes(
                "the legacy API is refused with a linkage failure, got " + rendered,
                refused instanceof NoClassDefFoundError
                        || refused instanceof ClassNotFoundException);
        // Any absent class fails the same way, so the linkage error on its own would also pass on a
        // mis-rewritten dex or a packaging mistake, neither of which has anything to do with the
        // rule. What names the rule is the refusal the module loader issues; ART carries that
        // ClassNotFoundException through as the cause of the NoClassDefFoundError it throws at the
        // offending instruction, and Check.show renders the whole chain.
        Check.yes(
                "the refusal is the API 102 rule and not a missing class: " + rendered,
                rendered.contains("targeting Xposed API 102"));
    }

    /**
     * The native module API: Vector calls native_init on a library named in native_init.list and
     * hands it an inline hooker. Whether the hook took is read back through the app's own JNI, so
     * the whole case is observable from Java.
     */
    private static void nativeApi(Suite.Ctx ctx) throws Throwable {
        HookCases.loadNatives(ctx);
        Method add = ctx.fix.method("Natives", "nativeAdd", int.class, int.class);
        Method address = ctx.fix.method("Natives", "addFunctionAddress");

        ctx.step("loading the module's own library");
        // load() is what writes the report, and the message argument is evaluated first, so the
        // load has to happen on its own line for the failure to carry the linkage error.
        boolean loaded = NativeBridge.load();
        Check.yes("the module's library loads: " + NativeBridge.loadReport(), loaded);
        Check.yes(
                "the framework called native_init and handed over its entries",
                NativeBridge.ready());
        Check.yes("the entries carry a version", NativeBridge.apiVersion() > 0);

        Check.eq("baseline", 5, Fix.call(add, null, 2, 3));

        ctx.step("installing an inline hook");
        long target = (Long) Fix.call(address, null);
        if (target == 0) {
            // Nothing to hook and nothing to conclude: the app never handed the address over.
            throw new Broken("the app did not hand over the address of vx_probe_add");
        }
        Check.yes("the inline hook installs", NativeBridge.install(target));
        Check.eq("the hooked function answers", 1005, Fix.call(add, null, 2, 3));

        ctx.step("removing it");
        Check.yes("the inline hook is removed", NativeBridge.remove());
        Check.eq("the original answers again", 5, Fix.call(add, null, 2, 3));
    }

    /**
     * Resource replacement.
     *
     * <p>The whole case is a reading, and not a close one: the 102 interface has no resource
     * surface at all - {@code XposedInterface} does not contain the word - so nothing here can be
     * quoted against it. What it measures is the legacy {@code android.content.res.XResources}
     * extension Vector carries, and a red row is an answer about that extension rather than a
     * conformance defect, which is what {@link Check#reading} says and why this does not fail the
     * run. Fixture problems still travel as {@link Broken}, which a reading passes through.
     *
     * <p>The order is the one the legacy API forces. Replacing an app's own resource needs an
     * XResources for that app; only a system-wide registration installs the hook that builds one;
     * and that has to have happened before the app's Resources existed, which is why {@link
     * ResProbe#install} runs at onPackageLoaded and this case picks up where it left off.
     *
     * <p>Three separate things are read back afterwards. The tag says whose document was inflated.
     * The root's id says whether the references inside it were translated, and the second inflation
     * whether they were translated a second time. The attribute name resource id says whether the
     * other half of the native rewrite - the one that renames the document's own attributes rather
     * than its values - ran at all; nothing else in the suite reaches it.
     */
    private static void layoutReplacement(Suite.Ctx ctx) throws Throwable {
        Check.reading(
                "the legacy XResources API, which the 102 interface never mentions",
                () -> replaceLayout(ctx));
    }

    private static void replaceLayout(Suite.Ctx ctx) throws Throwable {
        if (!ResProbe.ready()) {
            // Nothing has been asked of the framework yet, so this is the harness losing its own
            // footing rather than an answer about resource replacement.
            throw new Broken("the module never located its own APK: " + ResProbe.report());
        }

        Context context = (Context) ctx.fix.app(TARGET + ".Bridge").getField("context").get(null);
        if (context == null) {
            throw new Broken("the app never handed over a context");
        }
        Resources resources = context.getResources();

        // Everything the case needs out of the two APKs, resolved before anything is asked of the
        // framework. Not only because a drifted fixture must not be reported as a framework answer:
        // ResProbe.replace() resolves layout/probe itself, inside setReplacement, and would throw
        // NotFoundException first - which is the shape of the bug this case had before.
        int layout = resources.getIdentifier("probe", "layout", TARGET);
        if (layout == 0) {
            throw new Broken("the app does not declare layout/probe");
        }
        int hostId = resources.getIdentifier("probe_root", "id", TARGET);
        if (hostId == 0) {
            throw new Broken("the app does not declare id/probe_root");
        }
        int hostAttr = resources.getIdentifier("vxProbe", "attr", TARGET);
        if (hostAttr == 0) {
            throw new Broken("the app does not declare attr/vxProbe");
        }
        // aapt2 numbers the entries of a type in name order, so the two packages agreeing on a
        // number is a real possibility rather than a hypothetical one, and each agreement below
        // disarms one assertion without changing what it prints. Naming which one is what keeps a
        // fixture drift from arriving as a green row.
        if (hostId == ResProbe.moduleRootId()) {
            throw new Broken(
                    "both packages number id/probe_root "
                            + hex(hostId)
                            + ", so an untranslated reference reads like a translated one");
        }
        if (hostId != ResProbe.moduleOnlyId()) {
            throw new Broken(
                    "the app's id/probe_root "
                            + hex(hostId)
                            + " is not the module's id/module_only "
                            + hex(ResProbe.moduleOnlyId())
                            + ", so translating the document a second time would be a no-op");
        }
        if (hostAttr == ResProbe.moduleAttrId()) {
            throw new Broken(
                    "both packages number attr/vxProbe "
                            + hex(hostAttr)
                            + ", so an untranslated attribute name reads like a translated one");
        }

        ctx.step("checking the system-wide half");
        Check.yes(
                "the module could register a system-wide replacement: " + ResProbe.report(),
                ResProbe.systemWideRegistered());
        // The registration succeeding is not enough on its own: installing the resource hook is
        // its side effect, and that install returns instead of throwing when its native half
        // refuses to come up. The last thing it does is swap the static system Resources, so this
        // is the only thing that separates a hook that is up from one that quietly is not.
        Check.yes(
                "installing the resource hook converted the system Resources, got "
                        + Resources.getSystem().getClass().getName(),
                ResProbe.converted(Resources.getSystem()));
        if (!ResProbe.converted(resources)) {
            // The hook is up and this Resources still is not one of the framework's, which means
            // it was built before the registration - the harness registering too late, not the
            // framework failing to convert anything.
            throw new Broken(
                    "the app's Resources predates the registration: "
                            + resources.getClass().getName());
        }
        Check.eq(
                "the framework answers the system-wide replacement",
                ResProbe.SYSTEM_WIDE_TEXT,
                resources.getString(android.R.string.ok));

        ctx.step("registering the app's own layout");
        String refused = ResProbe.replace(resources);
        Check.yes(
                "the framework accepts a replacement for the app's own layout: " + refused,
                refused.isEmpty());

        ctx.step("inflating it");
        View inflated = LayoutInflater.from(context).inflate(layout, null);
        Check.eq("the inflated layout comes from the module", "REPLACED", inflated.getTag());
        Check.yes(
                "the module's id reference is translated to the app's: expected "
                        + hex(hostId)
                        + ", got "
                        + hex(inflated.getId()),
                inflated.getId() == hostId);

        // The rewrite mutates the binary XML in place and that document outlives the parser, so the
        // second inflation is what shows whether it was translated twice. A second pass reads the
        // app's number back out of the document and resolves it against the module's table, which
        // is only observable if the module's table has a different entry at that number - hence the
        // guard above requiring the app's probe_root to be the module's module_only, and the id
        // sorting ahead of probe_root in res/layout/replacement.xml that puts it there. Where they
        // do not line up a second pass returns the id unchanged and this check would pass whatever
        // the framework did, which is why that case is SETUP rather than green.
        ctx.step("inflating it again");
        View again = LayoutInflater.from(context).inflate(layout, null);
        Check.eq("and still comes from the module", "REPLACED", again.getTag());
        Check.yes(
                "and still carries the app's id: expected "
                        + hex(hostId)
                        + ", got "
                        + hex(again.getId()),
                again.getId() == hostId);

        // The attribute half writes the document's attribute name map, which the inflater keeps
        // nothing of, so it has to be read off a parser over the same document. Last, because
        // asking for the layout again is itself a request the framework may answer by rewriting.
        ctx.step("reading the attribute name back");
        int rewritten = attributeNameResource(resources.getLayout(layout), "vxProbe");
        Check.yes(
                "the replacement's own attribute is renamed to the app's attr/vxProbe: expected "
                        + hex(hostAttr)
                        + ", got "
                        + hex(rewritten)
                        + ", the module's own being "
                        + hex(ResProbe.moduleAttrId()),
                rewritten == hostAttr);
    }

    /**
     * The name resource id the first tag gives the attribute it calls {@code name} - the one thing
     * the attribute half of the rewrite writes, and the only way to see it, since the inflater
     * keeps nothing of it.
     *
     * <p>Finding the attribute by its string is also what keeps the assertion about the framework.
     * Those strings live in the document's own pool and no rewrite touches them, so an attribute
     * that cannot be found by name means the toolchain compiled no names at all - the rewrite would
     * have had nothing to look an id up by, which is an answer about the fixture and not about the
     * framework.
     */
    private static int attributeNameResource(XmlResourceParser parser, String name)
            throws Exception {
        try {
            int event = parser.getEventType();
            while (event != XmlResourceParser.START_TAG
                    && event != XmlResourceParser.END_DOCUMENT) {
                event = parser.next();
            }
            if (event != XmlResourceParser.START_TAG) {
                throw new Broken("the replacement document has no start tag");
            }
            for (int i = 0; i < parser.getAttributeCount(); i++) {
                if (name.equals(parser.getAttributeName(i))) {
                    return parser.getAttributeNameResource(i);
                }
            }
            throw new Broken(
                    "the compiled document names no attribute "
                            + name
                            + ", so the rewrite had nothing to look an id up by");
        } finally {
            parser.close();
        }
    }

    private static String hex(int id) {
        return "0x" + Integer.toHexString(id);
    }
}
