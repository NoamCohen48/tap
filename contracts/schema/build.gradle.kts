/*
 * The one message schema: every file under contracts/proto (tap.v1, the server API, and
 * tap.wire.v1, the TAP1 frame payloads) compiled to protobuf-javalite plus the Kotlin DSL.
 * Lite on every JVM consumer (daemon, host core, driver, Kotlin client) so the same generated
 * classes run on the host and on Android, and the driver APK carries no gRPC.
 * clients/python/scripts/gen_stubs.py generates the committed Python stubs for tap.v1.
 */
plugins {
    `java-library`
    `maven-publish`
    id("org.jetbrains.kotlin.jvm")
    id("com.google.protobuf") version "0.9.5"
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)
}

// Published as io.github.noamcohen48.tap:tap-schema (engine version); tap-api depends on it.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "tap-schema"
            from(components["java"])
            pom {
                name.set("tap-schema")
                description.set("tap.v1 and tap.wire.v1 protobuf-lite messages")
            }
        }
    }
}

val protobufVersion = "4.32.1"

dependencies {
    api("com.google.protobuf:protobuf-javalite:$protobufVersion")
    api("com.google.protobuf:protobuf-kotlin-lite:$protobufVersion")
}

sourceSets.main {
    proto.srcDir("../proto")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}
