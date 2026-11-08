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

rootProject.name = "ChitChat"
include(":app")
// Separate opt-in test APK; never added to :app's ordinary connected suite.
if (providers.gradleProperty("focusDiagnostic").orNull == "true") include(":focus-diagnostic")
// Independent app/test APKs; never changes the ordinary app's instrumentation suite.
if (providers.gradleProperty("imeReproducer").orNull == "true") include(":ime-reproducer")
