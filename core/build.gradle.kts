// SPDX-License-Identifier: GPL-3.0-or-later
// Domain core: pure Kotlin, no Android dependencies. Defines the model, the use cases and the
// ports (Source, Separator, LyricsProvider, Audio Engine) that adapters implement.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
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
    api(libs.kotlinx.coroutines.core)

    testFixturesApi(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
