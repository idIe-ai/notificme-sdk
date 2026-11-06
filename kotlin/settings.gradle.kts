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

rootProject.name = "notificme-kotlin"

// Published libraries (me.notific:<name>).
include(":client")
include(":client-android")
// Not published yet: Compose renderer for cards and questions, shared by the
// sample and the official app.
include(":cards")
// Reference app (never published to an app store).
include(":sample")
