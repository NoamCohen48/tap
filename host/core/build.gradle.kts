plugins {
    id("tap.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-test-fixtures`
}

dependencies {
    api(project(":contracts:protocol"))
    // The session journal's JSON (SessionJournal.kt); the wire protocol is protobuf.
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)

    testFixturesApi(project(":contracts:protocol"))
    testFixturesImplementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
