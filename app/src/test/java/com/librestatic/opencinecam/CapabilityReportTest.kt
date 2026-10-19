/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.CameraInventory
import com.librestatic.opencinecam.camera.CharacteristicEntry
import com.librestatic.opencinecam.core.model.CapabilityReportDocument
import com.librestatic.opencinecam.core.model.JsonReportRedactor
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityReportTest {
    private val facts = ReportDeviceFacts("brand/fp:secret", "Acme", "Phone X", "16", 36)
    private fun inventory(value: String = "FULL") = listOf(
        CameraInventory("0", null, listOf(CharacteristicEntry("android.info.supportedHardwareLevel", value)), lensFacing = 1),
        CameraInventory("2", "0", emptyList(), error = "boom"),
    )

    private fun report(inventory: List<CameraInventory> = inventory()) =
        buildCapabilityReport(facts, inventory, emptyList(), reportId = "123e4567-e89b-42d3-a456-426614174000", createdAt = "2026-10-19T10:00:00Z")

    @Test fun documentCarriesTheRequiredSchemaFields() {
        val document = report()
        assertEquals("1.0.0", document.schemaVersion)
        assertEquals("brand/fp:secret", (document.device["buildFingerprint"] as JsonPrimitive).content)
        assertEquals(36, (document.device["sdkInt"] as JsonPrimitive).content.toInt())
        assertTrue((document.device["characteristicsDigest"] as JsonPrimitive).content.startsWith("sha256:"))
        assertEquals(listOf("0", "2"), document.cameras.map { (it["cameraId"] as JsonPrimitive).content })
        assertEquals("boom", (document.cameras[1]["error"] as JsonPrimitive).content)
        assertEquals("Advertised", document.evidence.single().stage)
        Instant.parse(document.createdAt)
    }

    @Test fun digestFollowsTheCharacteristicsNotTheOrder() {
        assertEquals(characteristicsDigest(inventory()), characteristicsDigest(inventory().reversed()))
        assertNotEquals(characteristicsDigest(inventory()), characteristicsDigest(inventory("LIMITED")))
    }

    @Test fun redactedReportHidesTheIdentifiersButStaysAValidDocument() {
        val full = capabilityReportJson(report())
        val redacted = JsonReportRedactor.redact(full)
        val text = redacted.toString()
        listOf("secret", "Acme", "Phone X").forEach { assertFalse(it, text.contains(it)) }
        val document = Json.decodeFromJsonElement(CapabilityReportDocument.serializer(), redacted)
        assertEquals(JsonReportRedactor.REDACTED, (document.device["buildFingerprint"] as JsonPrimitive).content)
        assertEquals(36, (document.device.jsonObject["sdkInt"] as JsonPrimitive).content.toInt())
        assertTrue(full.toString().contains("secret"))
    }

    @Test fun fullExportIsGatedOnConsentAndRedactedIsTheDefault() {
        assertFalse(canExport(ReportExportMode.FULL, fullConsent = false))
        assertTrue(canExport(ReportExportMode.REDACTED, fullConsent = false))
        // Going back to the redacted choice drops a consent given for the full one.
        assertFalse(consentAfterModeChange(ReportExportMode.REDACTED, consent = true))
        assertTrue(consentAfterModeChange(ReportExportMode.FULL, consent = true))
    }
}
