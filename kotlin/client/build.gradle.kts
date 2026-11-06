plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

// Plain Kotlin, no Android dependency: runs on the JVM (fast tests, no
// emulator) and on Android. Written with Ktor, kotlinx.serialization and
// coroutines so it can become Kotlin Multiplatform when iOS needs it.
kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

// The protocol examples are the shared fixtures of every SDK.
tasks.test {
    systemProperty("notificme.protocolExamples", rootDir.resolve("../protocol/examples").absolutePath)
    inputs.dir(rootDir.resolve("../protocol/examples"))
}
