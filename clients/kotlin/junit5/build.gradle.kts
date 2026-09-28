plugins {
    id("tap.kotlin-jvm")
    id("tap.published")
    id("tap.dokka")
}

// Published as io.github.noamcohen48.tap:tap-junit5 (Kotlin client version); depends on tap-client.
tapPublication { maven("tap-junit5", "JUnit 5 extension for the Tap Kotlin client") }

dependencies {
    api(project(":clients:kotlin:sdk"))
    api(libs.junit.jupiter.api)
    api(libs.coroutines.core)
    // TapLauncherSessionListener (stops a server this JVM started). The launcher is on every
    // JUnit Platform test runtime already; it is not forced onto consumers' compile classpath.
    compileOnly(libs.junit.platform.launcher)

    testImplementation(kotlin("test"))
    // Fake daemons in the tests implement the generated services.
    testImplementation(project(":contracts:api"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
