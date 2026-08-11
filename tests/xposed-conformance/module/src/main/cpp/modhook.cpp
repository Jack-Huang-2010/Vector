// The native module half of the harness.
//
// Vector notices this library being loaded, looks for native_init and hands it the inline hooking
// entries. The Java side then asks for a hook on one C function in the target app and reads the
// answer back through an ordinary JNI call, so the whole case is observable from Java: hooked,
// vx_probe_add answers 1000 too many.

#include <jni.h>

#include <cstdint>

namespace {

// Mirrors NativeAPIEntries in native/include/core/native_api.h. It is a published ABI for native
// modules, so it is copied rather than included - a module has no access to the framework's
// headers.
struct NativeAPIEntries {
    uint32_t version;
    int (*hookFunc)(void *func, void *replace, void **backup);
    int (*unhookFunc)(void *func);
};

const NativeAPIEntries *g_entries = nullptr;
int (*g_backup)(int, int) = nullptr;
void *g_target = nullptr;

// Safe from the instant the patch lands rather than from the instant install() finishes: hookFunc
// publishes the redirect before g_backup is assigned, and remove() clears it while a call may still
// be inside here. In either window there is nothing to forward to, so the backup is read once and
// vx_probe_add's own sum stands in for it. Jumping through a null pointer instead would cost the
// run a crash, and one whose breadcrumb points at the wrong step.
int Replacement(int a, int b) {
    int (*original)(int, int) = g_backup;
    return (original != nullptr ? original(a, b) : a + b) + 1000;
}

}  // namespace

extern "C" {

// Vector calls this once, from its dlopen hook, before System.loadLibrary returns. Returning null
// declines the "another library was loaded" callback, which this harness has no use for.
JNIEXPORT void *native_init(const NativeAPIEntries *entries) {
    g_entries = entries;
    return nullptr;
}

JNIEXPORT jboolean JNICALL Java_org_matrix_vxmodule_NativeBridge_ready(JNIEnv *, jclass) {
    return g_entries != nullptr && g_entries->hookFunc != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_org_matrix_vxmodule_NativeBridge_apiVersion(JNIEnv *, jclass) {
    return g_entries == nullptr ? -1 : static_cast<jint>(g_entries->version);
}

JNIEXPORT jboolean JNICALL Java_org_matrix_vxmodule_NativeBridge_install(JNIEnv *, jclass,
                                                                        jlong address) {
    if (g_entries == nullptr || g_entries->hookFunc == nullptr || address == 0) {
        return JNI_FALSE;
    }
    g_target = reinterpret_cast<void *>(static_cast<uintptr_t>(address));
    void *backup = nullptr;
    if (g_entries->hookFunc(g_target, reinterpret_cast<void *>(&Replacement), &backup) != 0 ||
        backup == nullptr) {
        g_target = nullptr;
        return JNI_FALSE;
    }
    g_backup = reinterpret_cast<int (*)(int, int)>(backup);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_org_matrix_vxmodule_NativeBridge_remove(JNIEnv *, jclass) {
    if (g_entries == nullptr || g_entries->unhookFunc == nullptr || g_target == nullptr) {
        return JNI_FALSE;
    }
    bool ok = g_entries->unhookFunc(g_target) == 0;
    g_target = nullptr;
    g_backup = nullptr;
    return ok ? JNI_TRUE : JNI_FALSE;
}
}
