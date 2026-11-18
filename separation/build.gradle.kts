// SPDX-License-Identifier: GPL-3.0-or-later
// Separation (ADR 3, ADR 15): the Separator port's implementation in pure Kotlin. DSP, chunked and
// checkpointed Separation, the Separation Model catalogue and its download. Decoding and FLAC are
// ports the app wires to the engine. ONNX Runtime is compileOnly: the app supplies
// onnxruntime-android (same `ai.onnxruntime` API), the tests the desktop build.
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
    api(project(":core"))
    compileOnly(libs.onnxruntime)
    implementation(libs.commons.compress)

    testImplementation(libs.onnxruntime)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The contract test downloads the real models once and keeps it across builds (and CI runs).
    systemProperty("modelCache", gradle.gradleUserHomeDir.resolve("caches/croonvox-models").path)
    maxHeapSize = "2g"
    jvmArgs("--enable-native-access=ALL-UNNAMED") // ONNX Runtime loads its native library
}
