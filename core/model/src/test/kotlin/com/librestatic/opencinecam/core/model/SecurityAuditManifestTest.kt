/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Audits the real app manifest, so a new permission or export cannot drift past OCC-PRIV-001. */
class SecurityAuditManifestTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** The opt-in features the app declares: WebDAV (OCC-PLAN-067) and geotagging (OCC-PLAN-066). */
    private val appOptInFeatures = setOf(OptInFeature.WEBDAV_TRANSFER, OptInFeature.GEOTAGGING)

    private fun manifestFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return File(requireNotNull(dir) { "project root not found" }, "app/src/main/AndroidManifest.xml")
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun manifestInput(): SecurityAuditInput {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = factory.newDocumentBuilder().parse(manifestFile()).documentElement
        val application = root.children("application").single()
        val components = listOf("activity", "activity-alias", "service", "receiver", "provider")
            .flatMap { application.children(it) }
        val launcher = components.firstOrNull { component ->
            component.children("intent-filter").any { filter ->
                filter.children("action").any { it.getAttributeNS(androidNs, "name") == "android.intent.action.MAIN" } &&
                    filter.children("category").any {
                        it.getAttributeNS(androidNs, "name") == "android.intent.category.LAUNCHER"
                    }
            }
        }?.getAttributeNS(androidNs, "name")
        return SecurityAuditInput(
            permissions = root.children("uses-permission").map { it.getAttributeNS(androidNs, "name") }.toSet(),
            exportedComponents = components.associate {
                it.getAttributeNS(androidNs, "name") to (it.getAttributeNS(androidNs, "exported") == "true")
            },
            launcherComponent = launcher,
            allowBackup = application.getAttributeNS(androidNs, "allowBackup") != "false",
            // Not expressed in the manifest; covered by their own host tests.
            sharingRequiresExplicitConsent = true,
            diagnosticsRedactedByDefault = true,
            fullExportRequiresExplicitConsent = true,
            nativeInputsValidated = true,
            analyticsOrRemoteCrashEnabled = false,
            touchTargetDp = listOf(48),
            semanticLabels = listOf("Record"),
            declaredOptInFeatures = appOptInFeatures,
        )
    }

    @Test
    fun realManifestPassesWithTheAppsDeclaredOptInFeatures() {
        val input = manifestInput()
        assertTrue(input.permissions.contains("android.permission.CAMERA"))
        assertTrue(input.exportedComponents.isNotEmpty())
        val report = SecurityPrivacyAccessibilityAuditor.audit(input)
        assertTrue(report.findings.filterNot { it.passed }.toString(), report.passed)
    }

    @Test
    fun realManifestFailsIfInternetIsDeclaredWithoutTheWebDavOptIn() {
        val input = manifestInput()
        assertTrue(input.permissions.contains("android.permission.INTERNET"))
        val report = SecurityPrivacyAccessibilityAuditor.audit(
            input.copy(declaredOptInFeatures = appOptInFeatures - OptInFeature.WEBDAV_TRANSFER),
        )
        assertFalse(report.passed)
        assertFalse(report.findings.first { it.findingId == "SEC-NETWORK" }.passed)
    }
}
