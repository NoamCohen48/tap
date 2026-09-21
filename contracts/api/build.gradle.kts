import com.google.protobuf.gradle.id

/*
 * Host service API (tap.v1). The files under `proto/` are the single source of truth:
 * this module generates the Java/gRPC classes for the host service and the Kotlin client;
 * clients/python/scripts/gen_stubs.py generates (and CI verifies) the committed Python stubs.
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

// Published as com.company.tap:tap-api (engine version); the Kotlin client depends on it.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "tap-api"
            from(components["java"])
            pom { name.set("tap-api"); description.set("tap.v1 host service API: generated gRPC/protobuf stubs") }
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
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    api("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
}

sourceSets.main {
    proto.srcDir("proto")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" }
        id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
        }
    }
}
