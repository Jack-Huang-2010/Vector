plugins { id("com.android.application") }

android {
    namespace = "org.matrix.vxmodule"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "org.matrix.vxmodule"
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        // libvxmodhook.so is loaded into the hooked app's process, so it has to carry the same
        // ABIs the target does.
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

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    // Never packaged; see legacystub/build.gradle.kts for why linking against it is the only form
    // of the legacy-API probe that survives dex obfuscation.
    compileOnly(project(":legacystub"))
}
