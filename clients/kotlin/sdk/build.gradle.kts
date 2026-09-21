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
 * `./gradlew -Ptap.dokkaFormat=gfm :clients:kotlin:sdk:dokkaGeneratePublicationHtml`.
 * Without the property this module feeds the root HTML aggregation (`:dokkaGenerate`).
 */
if (providers.gradleProperty("tap.dokkaFormat").orNull == "gfm") {
    dependencies { dokkaPlugin("org.jetbrains.dokka:gfm-plugin:2.1.0") }
    dokka {
        moduleName.set("tap-client")
        dokkaPublications.named("html") { outputDirectory.set(layout.buildDirectory.dir("dokka/gfm")) }
    }
}

