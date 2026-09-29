package io.github.noamcohen48.tap.build

import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.get
import javax.inject.Inject

/** `tapPublication { maven(artifactId, description) }`, added by the `tap.published` plugin. */
abstract class TapPublicationExtension
    @Inject
    constructor(
        private val project: Project,
    ) {
        /** The Maven artifact id once [maven] ran; `tap.dokka` names the module after it. */
        var artifactId: String? = null
            private set

        fun maven(
            artifactId: String,
            description: String,
        ) {
            this.artifactId = artifactId
            project.extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("maven") {
                    this.artifactId = artifactId
                    from(project.components["java"])
                    pom {
                        name.set(artifactId)
                        this.description.set(description)
                        url.set("https://github.com/NoamCohen48/tap")
                        licenses {
                            license {
                                name.set("Apache-2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                            }
                        }
                        scm { url.set("https://github.com/NoamCohen48/tap") }
                    }
                }
            }
        }
    }
