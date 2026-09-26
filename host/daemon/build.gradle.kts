plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.graalvm.buildtools.native") version "0.11.3"
    application
}

kotlin {
    jvmToolchain(17)
}

val grpcVersion = "1.75.0"

dependencies {
    implementation(project(":host:core"))
    implementation(project(":contracts:api"))
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    testImplementation(kotlin("test"))
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(testFixtures(project(":host:core")))
}

application {
    applicationName = "tap"
    mainClass.set("com.company.tap.daemon.TapDaemonMainKt")
}

/*
 * The driver APKs ride inside the daemon so `tap serve` can install the matching driver on
 * any device without a checkout. They are copied into resources from the driver module's
 * debug outputs.
 */
val driverApk = rootProject.layout.projectDirectory.file("device/driver/build/outputs/apk/debug/driver-debug.apk")
val driverTestApk =
    rootProject.layout.projectDirectory.file(
        "device/driver/build/outputs/apk/androidTest/debug/driver-debug-androidTest.apk",
    )

val bundleDriver by tasks.registering(Copy::class) {
    dependsOn(":device:driver:assembleDebug", ":device:driver:assembleDebugAndroidTest")
    from(driverApk) { rename { "driver.apk" } }
    from(driverTestApk) { rename { "driver-test.apk" } }
    into(layout.buildDirectory.dir("bundled-driver/com/company/tap/daemon/driver"))
}

sourceSets.main {
    resources.srcDir(bundleDriver.map { layout.buildDirectory.dir("bundled-driver").get() })
}

tasks.test {
    useJUnitPlatform()
    systemProperty(
        "tap.goldenDir",
        rootProject.layout.projectDirectory
            .dir("contracts/protocol/src/test/resources/golden")
            .asFile.absolutePath,
    )
}

/*
 * Self-contained binary. Requires a GraalVM JDK (21+) at GRAALVM_HOME; the regular Kotlin
 * compilation still uses the JDK 17 toolchain. Reflection/resource metadata for the shaded
 * Netty transport and the JSON protocol classes is committed under
 * src/main/resources/META-INF/native-image and was recorded with the tracing agent
 * (see README "Native image").
 */
graalvmNative {
    toolchainDetection.set(false)
    metadataRepository { enabled.set(true) }
    binaries {
        named("main") {
            imageName.set("tap")
            mainClass.set("com.company.tap.daemon.TapDaemonMainKt")
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
