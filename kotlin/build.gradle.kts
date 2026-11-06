plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Coordinates used by the published libraries and by builds that include
// this one (`includeBuild("…/sdk/kotlin")` substitutes me.notific:* by these
// projects, so the official app needs no published version).
allprojects {
    group = "me.notific"
    version = providers.gradleProperty("notificme.version").get()
}
