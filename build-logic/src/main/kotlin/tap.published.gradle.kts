import io.github.noamcohen48.tap.build.TapPublicationExtension

/*
 * A published JVM library: sources jar plus one `maven` publication, declared with
 * `tapPublication { maven("tap-…", "description") }`. The GitHub Packages repository is added
 * by the root build for every module that applies maven-publish.
 */
pluginManager.apply("java-library")
pluginManager.apply("maven-publish")

extensions.configure<JavaPluginExtension> { withSourcesJar() }
extensions.create<TapPublicationExtension>("tapPublication")
