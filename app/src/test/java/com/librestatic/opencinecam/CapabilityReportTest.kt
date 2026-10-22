/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.CameraInventory
import com.librestatic.opencinecam.camera.AudioMetadata
import com.librestatic.opencinecam.camera.CharacteristicEntry
import com.librestatic.opencinecam.camera.CodecAudioMetadata
import com.librestatic.opencinecam.camera.CodecAudioReportJson
import com.librestatic.opencinecam.camera.CodecMetadata
import com.librestatic.opencinecam.core.model.CapabilityReportDocument
import com.librestatic.opencinecam.core.model.JsonReportRedactor
import com.librestatic.opencinecam.core.model.ProfileBuildCheck
import com.librestatic.opencinecam.core.model.ProfileStatus
import com.librestatic.opencinecam.core.model.ProfileTrust
import com.librestatic.opencinecam.core.model.QuirkEffect
import com.librestatic.opencinecam.core.model.QuirkResolution
import com.librestatic.opencinecam.core.model.EffectiveQuirk
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    @Test fun codecAndAudioProbeFillTheirSections() {
        val probe = CodecAudioMetadata(
            listOf(CodecMetadata("c2.hevc.encoder", "video/hevc", true, listOf("1:256"), listOf(21))),
            AudioMetadata(unprocessedSource = true, noiseSuppressorAvailable = null, automaticGainControlAvailable = false, acousticEchoCancelerAvailable = false),
        )
        val document = buildCapabilityReport(facts, inventory(), emptyList(), codecs = CodecAudioReportJson.codecs(probe), audio = CodecAudioReportJson.audio(probe))
        assertEquals("video/hevc", (document.codecs.single()["mime"] as JsonPrimitive).content)
        assertEquals("known", ((document.audio["unprocessed"] as JsonObject)["knowledge"] as JsonPrimitive).content)
        assertEquals("unknown", ((document.audio["noiseSuppressor"] as JsonObject)["knowledge"] as JsonPrimitive).content)
        assertEquals(probe, CodecAudioReportJson.decode(document.codecs, document.audio))
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

    private val driftedProfile = QuirkResolution(
        ProfileStatus.ACTIVE,
        listOf(EffectiveQuirk("OCC-QUIRK-0001", QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA, true, ProfileTrust.BUNDLED_TRUSTED)),
        listOf("not verified on this build: the profile was recorded for another build fingerprint"),
        "3f6c2a9e-7d41-4b58-9c0e-5a1d8e2b7f04",
        ProfileBuildCheck.NOT_VERIFIED_ON_THIS_BUILD,
    )

    @Test fun deviceProfileSectionNamesTheStatusAndQuirksButNoFingerprint() {
        val document = buildCapabilityReport(facts, inventory(), emptyList(), deviceProfiles = mapOf("0" to driftedProfile),
            reportId = "123e4567-e89b-42d3-a456-426614174000", createdAt = "2026-10-19T10:00:00Z")
        val section = document.cameras[0]["deviceProfile"]!!.jsonObject
        assertEquals("ACTIVE_NOT_VERIFIED_ON_THIS_BUILD", (section["state"] as JsonPrimitive).content)
        assertEquals("3f6c2a9e-7d41-4b58-9c0e-5a1d8e2b7f04", (section["profileId"] as JsonPrimitive).content)
        assertTrue(section.toString().contains("high-speed-prefer-physical-camera"))
        assertFalse(section.toString().contains("fp:secret"))
        assertEquals(null, document.cameras[1]["deviceProfile"])
        // The redactor leaves the section intact: nothing in it identifies the phone.
        val redacted = JsonReportRedactor.redact(capabilityReportJson(document)).toString()
        assertTrue(redacted.contains("ACTIVE_NOT_VERIFIED_ON_THIS_BUILD"))
        assertFalse(redacted.contains("secret"))
    }

    @Test fun noProfileIsReportedAsNone() {
        val section = deviceProfileJson(QuirkResolution.NONE)
        assertEquals("NONE", (section["state"] as JsonPrimitive).content)
        assertEquals(null, section["profileId"])
    }
}
