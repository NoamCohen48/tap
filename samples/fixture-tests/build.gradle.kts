plugins {
    id("tap.kotlin-jvm")
}

dependencies {
    testImplementation(project(":clients:kotlin:junit5"))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

/*
 * Real-device sample suite. Run with:
 *   ./gradlew :samples:fixture-tests:test -Ptap.serials=emulator-5554[,SERIAL]
 * Without -Ptap.serials the task is skipped. The fixture app and the host daemon (which
 * bundles the driver) are built automatically; the tests use a running server or start (and
 * afterwards stop) the JVM distribution built here (`-Ptap.manageDaemon=false` to require one).
 */
val serials = providers.gradleProperty("tap.serials")
val fixtureApk = rootProject.layout.projectDirectory.file("fixture-app/build/outputs/apk/debug/fixture-app-debug.apk")
val daemonBin = rootProject.layout.projectDirectory.file("host/daemon/build/install/tap/bin/tap")

tasks.test {
    enabled = serials.isPresent
    dependsOn(":fixture-app:assembleDebug", ":host:daemon:installDist")
    outputs.upToDateWhen { false }
    systemProperty("tap.serials", serials.getOrElse(""))
    systemProperty("tap.bin", daemonBin.asFile.absolutePath)
    // Starts the JVM dist built here unless a server is already running; stops it again if it started it.
    systemProperty("tap.manageDaemon", providers.gradleProperty("tap.manageDaemon").getOrElse("true"))
    systemProperty("tap.fixtureApk", fixtureApk.asFile.absolutePath)
    systemProperty(
        "tap.artifactsDir",
        layout.buildDirectory
            .dir("tap-artifacts")
            .get()
            .asFile.absolutePath,
    )
    // Optional overrides: -Ptap.acquireTimeoutSeconds=…, -Ptap.server=host:port (+ -Ptap.token=…)
    listOf("tap.acquireTimeoutSeconds", "tap.server", "tap.token").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
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
