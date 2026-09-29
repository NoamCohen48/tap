package io.github.noamcohen48.tap.build

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.plugins.DokkaHtmlPluginParameters

private const val FOOTER =
    "Tap &middot; Apache License 2.0 &middot; <a href=\"https://noamcohen48.github.io/tap/\">Documentation</a>"

/**
 * Tap branding for a Dokka HTML publication: the Tap logo in place of Kotlin's and a footer
 * linking back to the documentation site. Used by `tap.dokka` and the root aggregate. Kept out of
 * the script plugins so the lambdas capture only these values (configuration cache).
 */
fun Project.tapDokkaHtml() {
    val logo = rootProject.layout.projectDirectory.file("docs/assets/dokka/logo-icon.svg")
    extensions.configure<DokkaExtension> {
        pluginsConfiguration.withType<DokkaHtmlPluginParameters>().configureEach {
            footerMessage.set(FOOTER)
            customAssets.from(logo)
        }
    }
}

/** Module docs from `Module.md` and source links to the module's `src/main/kotlin` on GitHub. */
fun Project.tapDokkaSources() {
    val moduleDoc = layout.projectDirectory.file("Module.md")
    val sourceDir = layout.projectDirectory.dir("src/main/kotlin")
    val sourceUrl =
        "https://github.com/NoamCohen48/tap/tree/main/${projectDir.relativeTo(rootDir).invariantSeparatorsPath}/src/main/kotlin"
    extensions.configure<DokkaExtension> {
        dokkaSourceSets.configureEach {
            includes.from(moduleDoc)
            sourceLink {
                localDirectory.set(sourceDir)
                remoteUrl(sourceUrl)
                remoteLineSuffix.set("#L")
            }
        }
    }
}
