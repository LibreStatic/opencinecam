// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.librestatic.opencinecam"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.librestatic.opencinecam"
        minSdk = 29
        targetSdk = 37
        versionCode = 8
        versionName = "0.3.3"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            // Native AndroidX graphics on x86_64 emulators; release remains ARM64-only.
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencyLocking {
    lockAllConfigurations()
}

val verifyThirdPartyLicenses by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the bundled license catalog against releaseRuntimeClasspath."
    workingDir(rootProject.projectDir)
    commandLine(
        "python3",
        "tools/generate_third_party_licenses.py",
        "--lockfile",
        "app/gradle.lockfile",
        "--output",
        "app/src/main/assets/third_party_licenses.json",
        "--check",
    )
    inputs.file("gradle.lockfile")
    inputs.file(rootProject.file("tools/generate_third_party_licenses.py"))
    inputs.file("src/main/assets/third_party_licenses.json")
}

tasks.named("preBuild").configure {
    dependsOn(verifyThirdPartyLicenses)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":camera"))
    implementation(project(":media"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.window)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(project(":camera"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
