import com.google.protobuf.gradle.id

/*
 * Host server API (tap.v1) gRPC stubs: grpc-java (lite) and grpc-kotlin coroutine stubs for the
 * services in contracts/proto. The messages come from :contracts:schema; this module generates
 * no message classes of its own.
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

// Published as io.github.noamcohen48.tap:tap-api (engine version); the Kotlin client depends on it.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "tap-api"
            from(components["java"])
            pom {
                name.set("tap-api")
                description.set("tap.v1 host server API: generated gRPC stubs over tap-schema")
            }
        }
    }
}

val grpcVersion = "1.75.0"
val protobufVersion = "4.32.1"
// grpc-kotlin lags grpc-java: 1.5.0 is built against grpc-stub 1.62.2 and used here with
// grpc-java 1.75.0, which resolves because its metadata requires that version softly.
val grpcKotlinVersion = "1.5.0"
val coroutinesVersion = "1.10.2"

dependencies {
    api(project(":contracts:schema"))
    api("io.grpc:grpc-protobuf-lite:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    api("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
}

sourceSets.main {
    // Only the service files; wire/ has no services and is not part of the API.
    proto.srcDir("../proto")
    proto.exclude("wire/**")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" }
        id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar" }
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
