plugins {
    id("com.android.library")
}

/*
 * The driver's product code: the TAP1 server, the command engine binding and every UiAutomator
 * command. An ordinary Android library so lint and JVM unit tests apply to it; `:device:driver`
 * only adds the thin instrumentation entry point that runs it inside the driver test APK.
 */
android {
    namespace = "io.github.noamcohen48.tap.driver.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    api(project(":contracts:protocol"))
    api(project(":device:driver:command-engine"))
    api(libs.androidx.uiautomator)

    testImplementation(libs.junit4)
}
