plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.dokka") version "2.1.0"
}

/*
 * Generated Kotlin API reference (docs/reference/kotlin): Dokka over the two client modules
 * only. `./gradlew :dokkaGenerate` aggregates them into build/dokka/html.
 */
dependencies {
    dokka(project(":clients:kotlin:sdk"))
    dokka(project(":clients:kotlin:junit5"))
}

dokka {
    moduleName.set("Tap Kotlin client")
}

/*
 * Versioning. gradle.properties holds one version per artifact family; every module gets the
 * group and the version of the family it belongs to, so `maven-publish` coordinates and the
 * version strings compiled into the daemon/driver all come from the same place.
 */
val engineVersion = providers.gradleProperty("tap.version.engine").get()
val kotlinClientVersion = providers.gradleProperty("tap.version.client.kotlin").get()
val syncSdkVersion = providers.gradleProperty("tap.version.sync-sdk").get()

val clientProjects = setOf(":clients:kotlin:sdk", ":clients:kotlin:junit5", ":samples:fixture-tests")
val syncSdkProjects = setOf(":device:sync-sdk")

allprojects {
    group = "io.github.noamcohen48.tap"
    version =
        when (path) {
            in clientProjects -> kotlinClientVersion
            in syncSdkProjects -> syncSdkVersion
            else -> engineVersion
        }
}

/*
 * Publishing. Modules that apply `maven-publish` (contracts:api, clients:kotlin:*,
 * device:sync-sdk) publish to GitHub Packages of this repository; credentials come from the
 * standard GITHUB_ACTOR / GITHUB_TOKEN variables in CI (or -PgithubUser/-PgithubToken locally).
 * `publishToMavenLocal` needs no credentials and is what CI uses to prove the POMs resolve.
 */
subprojects {
    plugins.withId("maven-publish") {
        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri(providers.gradleProperty("tap.mavenRepo").orElse("https://maven.pkg.github.com/NoamCohen48/tap"))
                    credentials {
                        username = providers.gradleProperty("githubUser").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                        password = providers.gradleProperty("githubToken").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                    }
                }
            }
        }
    }
}
