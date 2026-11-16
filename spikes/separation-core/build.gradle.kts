// SPDX-License-Identifier: GPL-3.0-or-later
// Throwaway spike for ticket #3 (ADR 3). Not part of the production app.
// Pure Kotlin: DSP, chunked Separation and the model adapters, so they run as JVM tests.
// ONNX Runtime is compileOnly: the Android app supplies onnxruntime-android (same `ai.onnxruntime`
// API), the tests the desktop build.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.onnxruntime)
    implementation(libs.commons.compress)

    testImplementation(libs.onnxruntime)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Opt-in tests with real model files: -PseparationModels=/path/to/models
    providers.gradleProperty("separationModels").orNull?.let { systemProperty("separationModels", it) }
    providers.gradleProperty("cpuArena").orNull?.let { systemProperty("cpuArena", it) }
    maxHeapSize = "2g"
}
