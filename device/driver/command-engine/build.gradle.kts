plugins {
    id("tap.kotlin-jvm")
}

dependencies {
    api(project(":contracts:protocol"))

    testImplementation(kotlin("test"))
}
