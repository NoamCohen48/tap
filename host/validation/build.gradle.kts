plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    // Keeps the documented `host` executable name: host/validation/build/install/host/bin/host.
    applicationName = "host"
    mainClass = "com.company.tap.host.validation.PhaseZeroMainKt"
}

dependencies {
    implementation(project(":host"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
