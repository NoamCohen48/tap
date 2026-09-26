/*
 * Kotlin client for the Tap host daemon. A thin layer over the generated tap.v1 stubs; it
 * contains no ADB, session or driver logic (that lives in host/) and never links host modules.
 */
plugins {
    id("tap.kotlin-jvm")
    id("tap.published")
    id("tap.dokka")
}

// Published as io.github.noamcohen48.tap:tap-client (Kotlin client version); depends on tap-api.
tapPublication { maven("tap-client", "Kotlin client for the Tap host daemon") }

dependencies {
    api(project(":contracts:api"))
    implementation(libs.grpc.netty.shaded)
    // daemon.json parsing (port + bearer token); no serialization plugin needed.
    implementation(libs.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    // The shared client conformance table (also loaded by the Python unit suite).
    val conformance = rootProject.file("contracts/conformance/client-conformance.json")
    inputs.file(conformance)
    systemProperty("tap.conformance", conformance.path)
}
