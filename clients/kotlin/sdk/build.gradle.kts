/*
 * Kotlin client for the Tap host daemon. A layer over the generated tap.v1 stubs that exposes
 * only its own model types; it contains no ADB, session or driver logic (that lives in host/) and never links host modules.
 */
plugins {
    id("tap.kotlin-jvm")
    id("tap.published")
    id("tap.dokka")
}

// Published as io.github.noamcohen48.tap:tap-client (Kotlin client version); depends on tap-api.
tapPublication { maven("tap-client", "Kotlin client for the Tap host daemon") }

dependencies {
    // The generated tap.v1 types are an implementation detail (K-4): the public API is the SDK's
    // own models. gRPC's channel type (the TapClient transport seam) and coroutines stay public.
    implementation(project(":contracts:api"))
    api(libs.grpc.stub)
    api(libs.coroutines.core)
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
