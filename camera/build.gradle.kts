// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlinx.kover")
}

android {
    namespace = "com.librestatic.opencinecam.camera"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":media"))
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.serialization.json)
    testImplementation("junit:junit:4.13.2")
}

// Coverage of the debug unit tests, merged by the root project's koverHtmlReportUnit.
kover {
    currentProject {
        createVariant("unit") {
            add("debug")
        }
    }
}
