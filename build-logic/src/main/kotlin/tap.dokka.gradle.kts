import io.github.noamcohen48.tap.build.TapPublicationExtension
import org.jetbrains.dokka.gradle.DokkaExtension

/*
 * API reference for a published client module. The root `:dokkaGenerate` aggregates the HTML.
 * The Markdown edition (docs-md bundle, scripts/build-docs.sh) swaps Dokka's GFM renderer into
 * the same publication, per invocation, and writes build/dokka/gfm:
 * `./gradlew -Ptap.dokkaFormat=gfm :clients:kotlin:sdk:dokkaGeneratePublicationHtml`.
 */
pluginManager.apply("org.jetbrains.dokka")

if (providers.gradleProperty("tap.dokkaFormat").orNull == "gfm") {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies.add("dokkaPlugin", libs.findLibrary("dokka-gfm-plugin").get())
    extensions.configure<DokkaExtension> {
        moduleName.set(provider { extensions.findByType<TapPublicationExtension>()?.artifactId ?: project.name })
        dokkaPublications.named("html") { outputDirectory.set(layout.buildDirectory.dir("dokka/gfm")) }
    }
}
