import io.github.noamcohen48.tap.build.TapPublicationExtension
import org.jetbrains.dokka.gradle.DokkaExtension
import io.github.noamcohen48.tap.build.tapDokkaHtml
import io.github.noamcohen48.tap.build.tapDokkaSources

/*
 * API reference for a published client module. The root `:dokkaGenerate` aggregates the HTML.
 * The Markdown edition (docs-md bundle, scripts/build-docs.sh) swaps Dokka's GFM renderer into
 * the same publication, per invocation, and writes build/dokka/gfm:
 * `./gradlew -Ptap.dokkaFormat=gfm :clients:kotlin:sdk:dokkaGeneratePublicationHtml`.
 *
 * The module is named after its artifact (tap-client, tap-junit5) and described by its
 * Module.md; declarations link to their source on GitHub (TapDokka.kt).
 */
pluginManager.apply("org.jetbrains.dokka")

tapDokkaSources()
tapDokkaHtml()

// tapPublication { maven(...) } runs after this plugin is applied, so the name is read once the
// build script has been evaluated.
afterEvaluate {
    extensions.findByType<TapPublicationExtension>()?.artifactId?.let { artifactId ->
        extensions.configure<DokkaExtension> { moduleName.set(artifactId) }
    }
}

if (providers.gradleProperty("tap.dokkaFormat").orNull == "gfm") {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies.add("dokkaPlugin", libs.findLibrary("dokka-gfm-plugin").get())
    extensions.configure<DokkaExtension> {
        dokkaPublications.named("html") { outputDirectory.set(layout.buildDirectory.dir("dokka/gfm")) }
    }
}
