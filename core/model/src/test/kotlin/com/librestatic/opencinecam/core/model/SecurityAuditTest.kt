/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAuditTest {
    private val safeInput = SecurityAuditInput(
        permissions = setOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_CAMERA",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        ),
        exportedComponents = mapOf("MainActivity" to true, "CaptureService" to false),
        launcherComponent = "MainActivity",
        allowBackup = false,
        sharingRequiresExplicitConsent = true,
        diagnosticsRedactedByDefault = true,
        fullExportRequiresExplicitConsent = true,
        nativeInputsValidated = true,
        analyticsOrRemoteCrashEnabled = false,
        touchTargetDp = listOf(48, 56),
        semanticLabels = listOf("Record", "Stop"),
    )

    @Test
    fun safeManifestAndUiPassEveryFinding() {
        val report = SecurityPrivacyAccessibilityAuditor.audit(safeInput)
        assertTrue(report.passed)
        assertTrue(report.findings.all { it.passed })
    }

    @Test
    fun unsafePermissionExportAndAccessibilityAreVisibleFailures() {
        val report = SecurityPrivacyAccessibilityAuditor.audit(
            safeInput.copy(
                permissions = safeInput.permissions + "android.permission.INTERNET",
                exportedComponents = mapOf("MainActivity" to true, "CaptureService" to true),
                allowBackup = true,
                sharingRequiresExplicitConsent = false,
                diagnosticsRedactedByDefault = false,
                fullExportRequiresExplicitConsent = false,
                nativeInputsValidated = false,
                analyticsOrRemoteCrashEnabled = true,
                touchTargetDp = listOf(44),
                semanticLabels = listOf(""),
            ),
        )
        assertFalse(report.passed)
        assertFalse(report.findings.first { it.findingId == "SEC-NETWORK" }.passed)
        assertFalse(report.findings.first { it.findingId == "SEC-COMPONENTS" }.passed)
        assertFalse(report.findings.first { it.findingId == "A11Y-TOUCH" }.passed)
    }

    @Test
    fun networkAndLocationPermissionsPassOnlyWithTheirDeclaredOptInFeature() {
        val optInPermissions = safeInput.permissions + setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
        )
        val allowed = SecurityPrivacyAccessibilityAuditor.audit(
            safeInput.copy(
                permissions = optInPermissions,
                declaredOptInFeatures = setOf(OptInFeature.WEBDAV_TRANSFER, OptInFeature.GEOTAGGING),
            ),
        )
        assertTrue(allowed.passed)

        val noWebDav = SecurityPrivacyAccessibilityAuditor.audit(
            safeInput.copy(permissions = optInPermissions, declaredOptInFeatures = setOf(OptInFeature.GEOTAGGING)),
        )
        assertEquals(
            listOf("SEC-NETWORK", "SEC-PERMISSIONS"),
            noWebDav.findings.filterNot { it.passed }.map { it.findingId },
        )

        val noGeotag = SecurityPrivacyAccessibilityAuditor.audit(
            safeInput.copy(permissions = optInPermissions, declaredOptInFeatures = setOf(OptInFeature.WEBDAV_TRANSFER)),
        )
        assertEquals(listOf("SEC-PERMISSIONS"), noGeotag.findings.filterNot { it.passed }.map { it.findingId })
    }

    @Test
    fun optInFeaturesDoNotJustifyOtherNetworkLocationOrSensitivePermissions() {
        val allFeatures = setOf(OptInFeature.WEBDAV_TRANSFER, OptInFeature.GEOTAGGING)
        listOf(
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.READ_CONTACTS",
        ).forEach { permission ->
            val report = SecurityPrivacyAccessibilityAuditor.audit(
                safeInput.copy(permissions = safeInput.permissions + permission, declaredOptInFeatures = allFeatures),
            )
            assertFalse(permission, report.passed)
            assertFalse(permission, report.findings.first { it.findingId == "SEC-PERMISSIONS" }.passed)
        }
    }
}
