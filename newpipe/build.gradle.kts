// SPDX-License-Identifier: GPL-3.0-or-later
// NewPipe Sources (ADR 1): YouTube and Bandcamp through NewPipeExtractor (GPL-3.0).
// Only the network flavor of the app depends on this module.
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
    implementation(libs.newpipe.extractor)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Opt-in live smoke test against the real services: ./gradlew :newpipe:test -PliveSources
    providers.gradleProperty("liveSources").orNull?.let { systemProperty("liveSources", "true") }
}
