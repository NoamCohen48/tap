plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly: the root build applies these plugins (`apply false`), so they are already on
    // every project's classpath; bundling them here would load them a second time.
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.dokka.gradle.plugin)
}
