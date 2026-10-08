plugins { id("com.android.application") }
android {
    namespace = "io.github.noamcohen48.tap.explorer.machine"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.noamcohen48.tap.explorer.machine"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
