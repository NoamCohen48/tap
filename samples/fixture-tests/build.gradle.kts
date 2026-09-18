plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(project(":host:junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/*
 * Real-device sample suite. Run with:
 *   ./gradlew :samples:fixture-tests:test -Ptap.serials=emulator-5554[,SERIAL]
 * Without -Ptap.serials the task is skipped. The fixture app and the driver APKs are built
 * and installed automatically.
 */
val serials = providers.gradleProperty("tap.serials")
val fixtureApk = layout.projectDirectory.file("../../fixture-app/build/outputs/apk/debug/fixture-app-debug.apk")
val driverApk = layout.projectDirectory.file("../../driver/build/outputs/apk/debug/driver-debug.apk")
val driverTestApk = layout.projectDirectory.file("../../driver/build/outputs/apk/androidTest/debug/driver-debug-androidTest.apk")

tasks.test {
    useJUnitPlatform()
    enabled = serials.isPresent
    dependsOn(":fixture-app:assembleDebug", ":driver:assembleDebug", ":driver:assembleDebugAndroidTest")
    outputs.upToDateWhen { false }
    systemProperty("tap.serials", serials.getOrElse(""))
    systemProperty("tap.autPackage", "com.company.tap.fixture")
    systemProperty("tap.driverApk", driverApk.asFile.absolutePath)
    systemProperty("tap.driverTestApk", driverTestApk.asFile.absolutePath)
    systemProperty("tap.fixtureApk", fixtureApk.asFile.absolutePath)
    systemProperty("tap.artifactsDir", layout.buildDirectory.dir("tap-artifacts").get().asFile.absolutePath)
    // Devices are the unit of parallelism; JUnit runs test classes concurrently when configured.
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
    testLogging {
        events("passed", "failed", "skipped")
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
