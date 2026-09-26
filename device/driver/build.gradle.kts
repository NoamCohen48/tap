plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.noamcohen48.tap.driver"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.noamcohen48.tap.driver"
        minSdk = 26
        targetSdk = 36
        // From gradle.properties tap.version.engine: 1.2.3 -> 10203 (pre-release suffixes ignored).
        versionName = project.version.toString()
        versionCode = versionName!!.substringBefore('-').split('.').map { it.toInt() }
            .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation(project(":contracts:protocol"))
    androidTestImplementation(project(":device:driver:command-engine"))
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
