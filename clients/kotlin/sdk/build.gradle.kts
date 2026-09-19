/*
 * Kotlin client for the Tap host service. A thin layer over the generated tap.v1 stubs; it
 * contains no ADB, session or driver logic (that lives in host/) and never links host modules.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":contracts:api"))
    implementation("io.grpc:grpc-netty-shaded:1.75.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
