import com.google.protobuf.gradle.id

/*
 * Host server API (tap.v1) gRPC stubs: grpc-java (lite) and grpc-kotlin coroutine stubs for the
 * services in contracts/proto. The messages come from :contracts:schema; this module generates
 * no message classes of its own.
 */
plugins {
    id("tap.kotlin-jvm")
    id("tap.published")
    alias(libs.plugins.protobuf)
}

// Published as io.github.noamcohen48.tap:tap-api (engine version); the Kotlin client depends on it.
tapPublication { maven("tap-api", "tap.v1 host server API: generated gRPC stubs over tap-schema") }

dependencies {
    api(project(":contracts:schema"))
    api(libs.grpc.protobuf.lite)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    api(libs.coroutines.core)
    compileOnly(libs.javax.annotation.api)
}

sourceSets.main {
    // Only the service files; wire/ has no services and is not part of the API.
    proto.srcDir("../proto")
    proto.exclude("wire/**")
}

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    plugins {
        id("grpc") { artifact = libs.grpc.protoc.gen.java.get().toString() }
        id("grpckt") { artifact = "${libs.grpc.protoc.gen.kotlin.get()}:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            // The message classes are :contracts:schema's.
            task.builtins { removeIf { it.name == "java" } }
            task.plugins {
                id("grpc") { option("lite") }
                id("grpckt") { option("lite") }
            }
        }
    }
}
