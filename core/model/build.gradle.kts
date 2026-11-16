// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(libs.kotlinx.serialization.json)
}

tasks.test {
    useJUnit()
    // SecurityAuditManifestTest audits the real app manifest; a manifest-only change must re-run it.
    inputs.file(rootProject.file("app/src/main/AndroidManifest.xml"))
        .withPropertyName("appManifest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Coverage of the unit tests, merged by the root project's koverHtmlReportUnit.
kover {
    currentProject {
        createVariant("unit") {
            add("jvm")
        }
    }
}
