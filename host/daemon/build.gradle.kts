plugins {
    id("tap.kotlin-jvm")
    alias(libs.plugins.graalvm.native)
    application
}

dependencies {
    implementation(project(":host:core"))
    implementation(project(":contracts:api"))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.coroutines.core)
    // The daemon descriptor file (DaemonDescriptor.kt).
    implementation(libs.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":host:core")))
}

application {
    applicationName = "tap"
    mainClass.set("io.github.noamcohen48.tap.daemon.TapDaemonMainKt")
}

/*
 * The driver APKs ride inside the daemon so `tap serve` can install the matching driver on
 * any device without a checkout. They are copied into resources from the driver module's
 * `product` flavor debug outputs (the `validation` flavor, with fault injection, is never bundled).
 */
val driverApk =
    rootProject.layout.projectDirectory.file("device/driver/build/outputs/apk/product/debug/driver-product-debug.apk")
val driverTestApk =
    rootProject.layout.projectDirectory.file(
        "device/driver/build/outputs/apk/androidTest/product/debug/driver-product-debug-androidTest.apk",
    )

val bundleDriver by tasks.registering(Copy::class) {
    dependsOn(":device:driver:assembleProductDebug", ":device:driver:assembleProductDebugAndroidTest")
    from(driverApk) { rename { "driver.apk" } }
    from(driverTestApk) { rename { "driver-test.apk" } }
    into(layout.buildDirectory.dir("bundled-driver/io/github/noamcohen48/tap/daemon/driver"))
}

sourceSets.main {
    resources.srcDir(bundleDriver.map { layout.buildDirectory.dir("bundled-driver").get() })
}

/*
 * Self-contained binary. Requires a GraalVM JDK (21+) at GRAALVM_HOME; the regular Kotlin
 * compilation still uses the JDK 17 toolchain. Reflection/resource metadata for the shaded
 * Netty transport (and the protobuf-lite messages) is committed under
 * src/main/resources/META-INF/native-image and was recorded with the tracing agent
 * (see README "Native image").
 */
graalvmNative {
    toolchainDetection.set(false)
    metadataRepository { enabled.set(true) }
    binaries {
        named("main") {
            imageName.set("tap")
            mainClass.set("io.github.noamcohen48.tap.daemon.TapDaemonMainKt")
            buildArgs.addAll(
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "-march=compatibility",
                "--initialize-at-build-time=kotlin",
                // `tap start` opens a client channel (ManagedChannelBuilder) to poll Info; that makes
                // Netty's SSL classes reachable, and their static init must not run in the builder.
                "--initialize-at-run-time=io.grpc.netty.shaded.io.netty.handler.ssl,io.grpc.netty.shaded.io.netty.internal.tcnative",
            )
        }
    }
}
