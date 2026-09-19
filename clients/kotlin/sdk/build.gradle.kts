/*
 * Kotlin client for the Tap host service. A thin layer over the generated tap.v1 stubs; it
 * contains no ADB, session or driver logic (that lives in host/) and never links host modules.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
    id("org.jetbrains.dokka")
}

kotlin {
    jvmToolchain(17)
}

java { withSourcesJar() }

// Published as com.company.tap:tap-client (Kotlin client version); depends on tap-api.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "tap-client"
            from(components["java"])
            pom { name.set("tap-client"); description.set("Kotlin client for the Tap host service") }
        }
    }
}

dependencies {
    api(project(":contracts:api"))
    implementation("io.grpc:grpc-netty-shaded:1.75.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
