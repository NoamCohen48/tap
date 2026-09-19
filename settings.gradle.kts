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

include(":protocol")
include(":driver")
include(":driver:command-engine")
include(":host")
include(":host:validation")
include(":host:sdk")
include(":host:junit5")
include(":host:service")
include(":fixture-app")
include(":sync-sdk")
include(":samples:fixture-tests")
