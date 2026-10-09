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
        // -Popencinecam.versionCode wins; CI derives it from the run number plus an offset
        // (-Popencinecam.versionCodeOffset, default 100) that keeps it above the last manual
        // upload; local builds fall back to the constant below (tools/*.sh read that line).
        versionCode = providers.gradleProperty("opencinecam.versionCode").orNull?.toInt()
            ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.let {
                it + (providers.gradleProperty("opencinecam.versionCodeOffset").orNull?.toInt() ?: 100)
            }
            ?: 114
        versionName = "0.3.4-beta"

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

    // Robolectric host tests (the Compose Driver server) resolve strings, fonts and drawables.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Compose Driver: tools/compose-driver.sh passes these as -P properties and the host test
// ComposeDriverServer reads them as system properties. Without them that test is skipped.
val composeDriverProperties = listOf(
    "compose.driver.composable",
    "composeDriver.port",
    "composeDriver.qualifiers",
    "composeDriver.theme",
)
tasks.withType<Test>().configureEach {
    composeDriverProperties.forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
    if (providers.gradleProperty("compose.driver.composable").isPresent) {
        maxHeapSize = "2g"
    } else {
        // Its SDK 36 sandbox needs Java 21 before the test can skip itself, so ordinary runs leave it out.
        filter.excludeTestsMatching("com.librestatic.opencinecam.driver.ComposeDriverServer")
    }
    // Compose Driver is built for Java 21; the build itself stays on the JDK 17 that CI uses.
    providers.gradleProperty("composeDriver.javaHome").orNull?.let {
        executable = "$it/bin/java"
        // Robolectric reaches into FileDescriptor internals and loads its native graphics runtime.
        jvmArgs(
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--enable-native-access=ALL-UNNAMED",
        )
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
    implementation(libs.androidx.core.splashscreen)
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
    // Agent visual feedback (AGENTS.md): host-test classpath only, never packaged.
    testImplementation(libs.compose.driver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(project(":camera"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
