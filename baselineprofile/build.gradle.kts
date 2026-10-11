// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.librestatic.opencinecam.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // AGP 9 does not pass it; both target variants carry the ".benchmark" suffix from :app.
        testInstrumentationRunnerArguments["targetAppId"] = "com.librestatic.opencinecam.benchmark"
    }

    targetProjectPath = ":app"

    // The test APK instruments itself, so the macrobenchmark library can drive the app process.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    useConnectedDevices = true
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
