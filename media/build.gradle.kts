// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

plugins {
    id("com.android.library")
}

android {
    namespace = "com.librestatic.opencinecam.media"
    compileSdk = 37
    // libocc_rawcodec.so (RAW10 codecs, CRC32C) from native-pipeline/, see src/main/cpp.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 29
        ndk {
            // :app packages arm64-v8a, plus x86_64 for debug and benchmark emulator builds.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
