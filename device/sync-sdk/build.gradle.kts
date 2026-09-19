plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "com.company.tap.sync"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

// Published as com.company.tap:tap-sync-sdk (its own version): apps under test add it to their
// E2E builds, so it is released independently of the engine.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                artifactId = "tap-sync-sdk"
                from(components["release"])
                pom { name.set("tap-sync-sdk"); description.set("Android library exposing app idle state to the Tap driver") }
            }
        }
    }
}
