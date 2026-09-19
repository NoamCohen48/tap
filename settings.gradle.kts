pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tap"

// Contracts shared across components.
include(":contracts:protocol")   // device wire protocol (device <-> host)
include(":contracts:api")        // host service API, tap.v1 (host service <-> clients)

// On-device component.
include(":device:driver")
include(":device:driver:command-engine")
include(":device:sync-sdk")

// Host service.
include(":host:core")
include(":host:service")
include(":host:validation")

// Clients (Python lives in clients/python, outside Gradle).
include(":clients:kotlin:sdk")
include(":clients:kotlin:junit5")

// Test AUT and samples.
include(":fixture-app")
include(":samples:fixture-tests")
