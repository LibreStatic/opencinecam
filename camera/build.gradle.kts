// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

plugins {
    id("com.android.library")
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
    testImplementation("junit:junit:4.13.2")
}
