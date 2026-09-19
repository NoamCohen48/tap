plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":clients:kotlin:sdk"))
    api("org.junit.jupiter:junit-jupiter-api:5.13.4")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
