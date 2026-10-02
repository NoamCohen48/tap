plugins {
    id("tap.kotlin-jvm")
    application
}

kotlin {
    // Validation and fault injection run arbitrary ADB and raw driver requests on purpose;
    // product code may not.
    compilerOptions.optIn.addAll(
        "io.github.noamcohen48.tap.host.RawAdb",
        "io.github.noamcohen48.tap.host.ValidationApi",
    )
}

application {
    // The only product entry point here: the read-only probe against a third-party AUT
    // (host/validation/build/install/tap-product-probe/bin/tap-product-probe).
    applicationName = "tap-product-probe"
    mainClass = "io.github.noamcohen48.tap.host.validation.ProductProbeMainKt"
}

dependencies {
    implementation(project(":host:core"))
    implementation(libs.coroutines.core)
}

/*
 * Real-device validation of host core + driver (fault injection, fencing, recovery, selectors).
 * Run with:
 *   ./gradlew :host:validation:deviceTest -Ptap.serials=emulator-5554[,SERIAL...]
 * Without -Ptap.serials every test is skipped. APKs default to the validation-flavor driver and
 * the fixture app, which the task builds; override with -Ptap.driverApk / -Ptap.driverTestApk /
 * -Ptap.fixtureApk. The destructive, rebooting scenario is tagged "reboot" and only runs with
 * -Ptap.reboot=true. Journals live in $TAP_STATE_DIR/sessions (default ~/.tap/sessions).
 */
val deviceProperties =
    mapOf(
        "tap.serials" to "",
        "tap.driverApk" to "device/driver/build/outputs/apk/validation/debug/driver-validation-debug.apk",
        "tap.driverTestApk" to "device/driver/build/outputs/apk/androidTest/validation/debug/driver-validation-debug-androidTest.apk",
        "tap.fixtureApk" to "fixture-app/build/outputs/apk/debug/fixture-app-debug.apk",
    )
val allowReboot = providers.gradleProperty("tap.reboot").map(String::toBoolean).getOrElse(false)

testing {
    suites {
        register<JvmTestSuite>("deviceTest") {
            useJUnitJupiter(libs.versions.junit.asProvider())
            dependencies {
                implementation(project(":host:core"))
                implementation(libs.coroutines.core)
            }
            targets.all {
                testTask.configure {
                    description = "Runs the device validation suite against -Ptap.serials."
                    outputs.upToDateWhen { false }
                    outputs.cacheIf { false }
                    dependsOn(
                        ":device:driver:assembleValidationDebug",
                        ":device:driver:assembleValidationDebugAndroidTest",
                        ":fixture-app:assembleDebug",
                    )
                    deviceProperties.forEach { (name, default) ->
                        val value = providers.gradleProperty(name).getOrElse(default)
                        val resolved =
                            if (name == "tap.serials" || value.isEmpty()) {
                                value
                            } else {
                                rootProject.file(value).absolutePath
                            }
                        systemProperty(name, resolved)
                    }
                    systemProperty("tap.allowReboot", allowReboot.toString())
                    useJUnitPlatform {
                        if (!allowReboot) excludeTags("reboot")
                    }
                    testLogging {
                        events("passed", "failed", "skipped")
                        showExceptions = true
                        showStandardStreams = true
                        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    }
                }
            }
        }
    }
}
