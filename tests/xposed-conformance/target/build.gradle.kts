plugins { id("com.android.application") }

android {
    namespace = "org.matrix.vxtarget"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "org.matrix.vxtarget"
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        // A Pixel 6 and the standard emulator images between them cover both. Add
        // "armeabi-v7a"/"x86" here if the suite ever has to run in a 32-bit process; the module's
        // libvxmodhook.so has to carry the same ABIs, because it is loaded into this process.
        ndk { abiFilters.addAll(listOf("arm64-v8a", "x86_64")) }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    buildTypes { release { isMinifyEnabled = false } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
}
