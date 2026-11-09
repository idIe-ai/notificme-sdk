plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// The phone app lends its session to its watch app over the Wear OS Data
// Layer (Bluetooth or Wi-Fi, encrypted by Google Play services, only between
// apps with the same package name and signing key). Used on both sides.
android {
    namespace = "me.notific.client.wear"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":client-android"))
    api(libs.play.services.wearable)
    // The phone opens the watch app's alert screen (Google Play services may start it; the app can't from the background).
    implementation(libs.wear.remote.interactions)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
