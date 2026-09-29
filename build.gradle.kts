plugins {
    // Versions: gradle/libs.versions.toml. Declared once here so every project (and the
    // build-logic convention plugins, which compile against them) share one classloader copy.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.dokka)
}

/*
 * Generated Kotlin API reference (docs/reference/kotlin-api): Dokka over the two client modules
 * only. `./gradlew :dokkaGenerate` aggregates them into build/dokka/html.
 */
dependencies {
    dokka(project(":clients:kotlin:sdk"))
    dokka(project(":clients:kotlin:junit5"))
}

dokka {
    moduleName.set("Tap Kotlin client")
    // Same branding as the modules (build-logic TapDokka.kt): Tap logo, footer back to the site.
    pluginsConfiguration.html {
        footerMessage.set("Tap &middot; Apache License 2.0 &middot; <a href=\"https://noamcohen48.github.io/tap/\">Documentation</a>")
        customAssets.from(layout.projectDirectory.file("docs/assets/dokka/logo-icon.svg"))
    }
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
