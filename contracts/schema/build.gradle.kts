/*
 * The one message schema: every file under contracts/proto (tap.v1, the server API, and
 * tap.wire.v1, the TAP1 frame payloads) compiled to protobuf-javalite plus the Kotlin DSL.
 * Lite on every JVM consumer (daemon, host core, driver, Kotlin client) so the same generated
 * classes run on the host and on Android, and the driver APK carries no gRPC.
 * clients/python/scripts/gen_stubs.py generates the committed Python stubs for tap.v1.
 */
plugins {
    id("tap.kotlin-jvm")
    id("tap.published")
    alias(libs.plugins.protobuf)
}

// Published as io.github.noamcohen48.tap:tap-schema (engine version); tap-api depends on it.
tapPublication { maven("tap-schema", "tap.v1 and tap.wire.v1 protobuf-lite messages") }

dependencies {
    api(libs.protobuf.javalite)
    api(libs.protobuf.kotlin.lite)
}

sourceSets.main {
    proto.srcDir("../proto")
}

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}
