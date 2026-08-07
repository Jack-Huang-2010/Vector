plugins { id("com.android.application") version "9.3.1" }
android {
    namespace = "org.matrix.bindertest"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.matrix.bindertest"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs { getByName("debug") { } }
    buildTypes { getByName("debug") { isMinifyEnabled = false } }
}
