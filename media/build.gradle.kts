// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

plugins {
    id("com.android.library")
}

android {
    namespace = "com.librestatic.opencinecam.media"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(project(":core:model"))
    testImplementation("junit:junit:4.13.2")
}
