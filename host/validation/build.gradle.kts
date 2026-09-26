plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
    // Validation and fault injection run arbitrary ADB on purpose; product code may not.
    compilerOptions.optIn.add("io.github.noamcohen48.tap.host.RawAdb")
}

application {
    // Keeps the documented `host` executable name: host/validation/build/install/host/bin/host.
    applicationName = "host"
    mainClass = "io.github.noamcohen48.tap.host.validation.PhaseZeroMainKt"
}

dependencies {
    implementation(project(":host:core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
