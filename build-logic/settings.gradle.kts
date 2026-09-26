/*
 * Convention plugins shared by the JVM modules (B-2): `tap.kotlin-jvm`, `tap.published` and
 * `tap.dokka`. Versions come from the root catalog, gradle/libs.versions.toml.
 */
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
