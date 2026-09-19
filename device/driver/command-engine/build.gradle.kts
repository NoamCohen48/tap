plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":contracts:protocol"))

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
