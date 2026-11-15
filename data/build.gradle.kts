// SPDX-License-Identifier: GPL-3.0-or-later
// Data layer: Room persistence and the app-private file store, implementing the domain core's
// repository and storage interfaces (ADR 8, ADR 10).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "app.croonvox.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room {
    // Exported schemas are committed so later migrations can be tested (spec #1, testing decisions).
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
