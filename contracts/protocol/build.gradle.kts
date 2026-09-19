plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    // RE2 regex for user selectors: linear time, no backreferences/lookaround (plan §10).
    api("com.google.re2j:re2j:1.8")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // `./gradlew :contracts:protocol:test -Dtap.golden.update=true` rewrites the golden wire fixtures.
    systemProperty("tap.golden.update", System.getProperty("tap.golden.update") ?: "false")
}
