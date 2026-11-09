plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// Compose for Wear OS renderer: the inbox row, the blocks of a card and the
// questions a watch can answer (yes/no and single choice), sized for a small
// round screen. Not published yet: the official watch app uses it from this
// build. It does not depend on `cards` (phone Material 3), so a watch app
// carries only Wear components.
android {
    namespace = "me.notific.cards.wear"
    compileSdk = 37

    defaultConfig {
        // Wear OS 3 and later.
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":client"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.wear.compose.material3)
    api(libs.wear.compose.foundation)
    // Check and X of the yes/no answers.
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.wear.compose.ui.tooling)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}
