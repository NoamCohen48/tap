import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Every JVM module: Kotlin on the JDK 17 toolchain, tests on the JUnit Platform.
pluginManager.apply("org.jetbrains.kotlin.jvm")

extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(17) }

tasks.withType<Test>().configureEach { useJUnitPlatform() }
