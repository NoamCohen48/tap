import com.google.protobuf.gradle.id

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.google.protobuf") version "0.9.5"
    application
}

kotlin {
    jvmToolchain(17)
}

val grpcVersion = "1.75.0"
val protobufVersion = "4.32.1"

dependencies {
    implementation(project(":host:sdk"))
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
}

// The contract lives at the repository root so every binding generates from the same file.
sourceSets.main {
    proto.srcDir(rootProject.layout.projectDirectory.dir("api"))
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins { id("grpc") }
        }
    }
}

application {
    applicationName = "tap"
    mainClass.set("com.company.tap.service.ServiceMainKt")
}

/*
 * The driver APKs ride inside the service so `tap serve` can install the matching driver on
 * any device without a checkout. They are copied into resources from the driver module's
 * debug outputs.
 */
val driverApk = rootProject.layout.projectDirectory.file("driver/build/outputs/apk/debug/driver-debug.apk")
val driverTestApk = rootProject.layout.projectDirectory.file("driver/build/outputs/apk/androidTest/debug/driver-debug-androidTest.apk")

val bundleDriver by tasks.registering(Copy::class) {
    dependsOn(":driver:assembleDebug", ":driver:assembleDebugAndroidTest")
    from(driverApk) { rename { "driver.apk" } }
    from(driverTestApk) { rename { "driver-test.apk" } }
    into(layout.buildDirectory.dir("bundled-driver/com/company/tap/service/driver"))
}

sourceSets.main {
    resources.srcDir(bundleDriver.map { layout.buildDirectory.dir("bundled-driver").get() })
}

tasks.test {
    useJUnitPlatform()
    systemProperty(
        "tap.goldenDir",
        rootProject.layout.projectDirectory.dir("protocol/src/test/resources/golden").asFile.absolutePath,
    )
}
