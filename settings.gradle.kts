pluginManagement {
    includeBuild("build-logic")
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
include(":contracts:schema") // contracts/proto compiled to protobuf-lite (tap.v1 + tap.wire.v1)
include(":contracts:protocol") // TAP1 framing, handshake, validation and dispatch over the schema
include(":contracts:api") // host server API, tap.v1 (host daemon <-> clients)

// On-device component.
include(":device:driver")
include(":device:driver:command-engine")
include(":device:driver:core") // driver product code (Android library); :device:driver is the instrumentation shell
include(":device:sync-sdk")

// Host daemon.
include(":host:core")
include(":host:daemon")
include(":host:validation")

// Clients (Python lives in clients/python, outside Gradle).
include(":clients:kotlin:sdk")
include(":clients:kotlin:junit5")

// Test AUT and samples.
include(":fixture-app")
include(":samples:fixture-tests")
