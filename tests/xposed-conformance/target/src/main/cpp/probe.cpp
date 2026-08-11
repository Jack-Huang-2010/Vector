// The native half of the hooked app.
//
// It exists for three of the reported failures: a native method bound by RegisterNatives before
// the hook, one bound after it, and a plain C function that the module's own native module hooks
// inline. Everything here is deliberately tiny and side-effect free, because a hook that loses a
// method's JNI binding shows up as an UnsatisfiedLinkError rather than as a wrong answer, and
// anything else in the way would only blur that.
//
// Two of the implementations below exist only so that a case can fail for a nameable reason rather
// than for any reason at all: LateImpl2, so a second RegisterNatives answers a different string
// from the first, and TwinImpl beside the twinNative symbol, so a lost binding answers the wrong
// string where a symbol-less method could only throw.

#include <jni.h>

#include <cstdint>

namespace {

constexpr const char *kNativesClass = "org/matrix/vxtarget/fix/Natives";

// Bound by RegisterNatives from JNI_OnLoad. The name is deliberately NOT Java_..._dynamicNative,
// so ART has no symbol to fall back on: if a hook moves the binding off the ArtMethod that ends up
// being invoked, the call throws UnsatisfiedLinkError instead of quietly re-resolving itself.
jstring DynamicImpl(JNIEnv *env, jclass) { return env->NewStringUTF("DYNAMIC"); }

// The same, registered only when the app asks for it, so the suite can drive the register-after-
// hook order as well as the register-before-hook one.
jstring LateImpl(JNIEnv *env, jclass) { return env->NewStringUTF("LATE"); }

// A second implementation of lateNative, for the re-registration over an already-hooked binding.
// It answers a different string on purpose: registering LateImpl twice would answer LATE whether
// the second RegisterNatives landed on the ArtMethod the call finds, was dropped, or was routed to
// the backup, so the assertion would hold no matter what happened.
jstring LateImpl2(JNIEnv *env, jclass) { return env->NewStringUTF("LATE2"); }

// twinNative is registered to this from JNI_OnLoad, while the Java_ symbol below answers SYMBOL.
// That pairing is the control for the dynamically-registered case: dynamicNative has no symbol, so
// a hook that loses its registered pointer can only throw, which is indistinguishable from hooking
// natives being broken outright. Here a lost pointer re-resolves through dlsym and answers SYMBOL -
// the same defect, saying its own name.
jstring TwinImpl(JNIEnv *env, jclass) { return env->NewStringUTF("TWIN"); }

}  // namespace

extern "C" {

// The inline-hook target for the native module API test. Kept out of line and long enough to hold
// a trampoline: Dobby patches the first instructions, and a two-instruction leaf would leave it
// nowhere to write.
__attribute__((noinline, visibility("default"))) int vx_probe_add(int a, int b) {
    volatile int acc = 0;
    for (int i = 0; i < 4; ++i) {
        acc += (a + b) - acc / 2;
    }
    return a + b + (acc - acc);
}

JNIEXPORT jstring JNICALL Java_org_matrix_vxtarget_fix_Natives_resolvedNative(JNIEnv *env, jclass) {
    return env->NewStringUTF("RESOLVED");
}

JNIEXPORT jstring JNICALL Java_org_matrix_vxtarget_fix_Natives_unresolvedNative(JNIEnv *env,
                                                                                jclass) {
    return env->NewStringUTF("UNRESOLVED");
}

// Answered only when the RegisterNatives binding below has been lost, which is the whole point of
// the twin: the suite asserts TWIN everywhere and SYMBOL is what a lost binding reads as.
JNIEXPORT jstring JNICALL Java_org_matrix_vxtarget_fix_Natives_twinNative(JNIEnv *env, jclass) {
    return env->NewStringUTF("SYMBOL");
}

JNIEXPORT void JNICALL Java_org_matrix_vxtarget_fix_Natives_registerLate(JNIEnv *env, jclass cls) {
    JNINativeMethod method = {"lateNative", "()Ljava/lang/String;",
                              reinterpret_cast<void *>(&LateImpl)};
    env->RegisterNatives(cls, &method, 1);
}

JNIEXPORT void JNICALL Java_org_matrix_vxtarget_fix_Natives_registerLateAgain(JNIEnv *env,
                                                                              jclass cls) {
    JNINativeMethod method = {"lateNative", "()Ljava/lang/String;",
                              reinterpret_cast<void *>(&LateImpl2)};
    env->RegisterNatives(cls, &method, 1);
}

JNIEXPORT jint JNICALL Java_org_matrix_vxtarget_fix_Natives_nativeAdd(JNIEnv *, jclass, jint a,
                                                                     jint b) {
    return vx_probe_add(a, b);
}

// Handing the address to the module rather than letting it dlsym for the symbol keeps the test
// about the hook: the module's library and this one are opened by different class loaders, so a
// dlsym miss would look exactly like a hook that never installed.
JNIEXPORT jlong JNICALL Java_org_matrix_vxtarget_fix_Natives_addFunctionAddress(JNIEnv *, jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&vx_probe_add));
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass cls = env->FindClass(kNativesClass);
    if (cls == nullptr) {
        return JNI_ERR;
    }
    JNINativeMethod methods[] = {
        {"dynamicNative", "()Ljava/lang/String;", reinterpret_cast<void *>(&DynamicImpl)},
        {"twinNative", "()Ljava/lang/String;", reinterpret_cast<void *>(&TwinImpl)},
    };
    jint result = env->RegisterNatives(cls, methods, 2);
    env->DeleteLocalRef(cls);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
}
