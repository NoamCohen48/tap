plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
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
            pom { name.set("tap-junit5"); description.set("JUnit 5 extension for the Tap Kotlin client") }
        }
    }
}

dependencies {
    api(project(":clients:kotlin:sdk"))
    api("org.junit.jupiter:junit-jupiter-api:5.13.4")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
