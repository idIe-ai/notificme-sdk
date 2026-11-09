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
// A phone app lends its session to its watch app over the Wear OS Data Layer.
include(":client-wear")
// Not published yet: Compose renderer for cards and questions, shared by the
// sample and the official app.
include(":cards")
// Compose for Wear OS renderer: notifications and simple decisions on a
// small round screen (the official watch app uses it).
include(":cards-wear")
// Reference app (never published to an app store).
include(":sample")
