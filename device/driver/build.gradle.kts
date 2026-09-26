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

    /*
     * `product` is the driver the daemon bundles: fault injection is not in it. `validation`
     * wires the fault controller (androidTestValidation) into the same entry point for the
     * host fault-validation suite. Both keep one application id, so the host installs and
     * instruments either the same way.
     */
    flavorDimensions += "faults"
    productFlavors {
        create("product") { dimension = "faults" }
        create("validation") { dimension = "faults" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation(project(":device:driver:core"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
