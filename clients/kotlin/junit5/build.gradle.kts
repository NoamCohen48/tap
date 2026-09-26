plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
    id("org.jetbrains.dokka")
}

kotlin {
    jvmToolchain(17)
}

java { withSourcesJar() }

// Published as com.company.tap:tap-junit5 (Kotlin client version); depends on tap-client.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "tap-junit5"
            from(components["java"])
            pom {
                name.set("tap-junit5")
                description.set("JUnit 5 extension for the Tap Kotlin client")
            }
        }
    }
}

dependencies {
    api(project(":clients:kotlin:sdk"))
    api("org.junit.jupiter:junit-jupiter-api:5.13.4")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // TapLauncherSessionListener (stops a server this JVM started). The launcher is on every
    // JUnit Platform test runtime already; it is not forced onto consumers' compile classpath.
    compileOnly("org.junit.platform:junit-platform-launcher:1.13.4")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.grpc:grpc-inprocess:1.75.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

/*
 * Markdown edition of the API reference (the docs-md bundle, scripts/build-docs.sh). Dokka's GFM
 * renderer replaces the HTML one inside the same publication, so it is opted into per invocation
 * and written to build/dokka/gfm:
 * `./gradlew -Ptap.dokkaFormat=gfm :clients:kotlin:junit5:dokkaGeneratePublicationHtml`.
 * Without the property this module feeds the root HTML aggregation (`:dokkaGenerate`).
 */
if (providers.gradleProperty("tap.dokkaFormat").orNull == "gfm") {
    dependencies { dokkaPlugin("org.jetbrains.dokka:gfm-plugin:2.1.0") }
    dokka {
        moduleName.set("tap-junit5")
        dokkaPublications.named("html") { outputDirectory.set(layout.buildDirectory.dir("dokka/gfm")) }
    }
}
