/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.core.model.DeviceProfile
import com.librestatic.opencinecam.core.model.DeviceProfileCodec
import com.librestatic.opencinecam.core.model.DeviceProfileDecoding
import com.librestatic.opencinecam.core.model.DeviceProfileVerifier
import com.librestatic.opencinecam.core.model.ProfileBuildCheck
import com.librestatic.opencinecam.core.model.ProfileStatus
import com.librestatic.opencinecam.core.model.ProfileTrust
import com.librestatic.opencinecam.core.model.QuirkEffect
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledDeviceQuirksTest {
    /** Gradle runs app host tests with the module directory as the working directory. */
    private val profileDirectory = File("src/main/profiles/$BUNDLED_PROFILE_DIRECTORY")
    private val documents: Map<String, String> = profileDirectory.listFiles { file -> file.extension == "json" }
        .orEmpty().sortedBy { it.name }.associate { it.name to it.readText() }

    private fun decoded(): List<DeviceProfile> = documents.map { (name, text) ->
        (DeviceProfileCodec.decode(text) as? DeviceProfileDecoding.Decoded ?: error("$name does not decode")).profile
    }

    private fun razr(device: String = "blanc", fingerprint: String? = null, log: (String) -> Unit = {}): BundledDeviceQuirks {
        val profile = decoded().single { it.scope.device == "blanc" && it.scope.cameraId == "0" }
        return BundledDeviceQuirks(decoded(), device, fingerprint ?: profile.scope.buildFingerprint, log)
    }

    @Test
    fun everyBundledProfileDecodesIsTrustedAndVerifiesItsDigest() {
        assertTrue("no bundled profile found in ${profileDirectory.absolutePath}", documents.isNotEmpty())
        decoded().forEach { profile ->
            assertEquals(ProfileTrust.BUNDLED_TRUSTED, profile.trust)
            val verification = DeviceProfileVerifier.verify(profile)
            assertEquals(
                "${profile.profileId}: update the signature digest to ${verification.expectedDigestHex} after editing a bundled profile",
                ProfileStatus.ACTIVE,
                verification.status,
            )
        }
    }

    @Test
    fun everyBundledProfileValidatesAgainstTheDeviceProfileSchema() {
        val schema = Json.parseToJsonElement(File("../docs/schemas/device-profile.schema.json").readText()) as JsonObject
        documents.forEach { (name, text) ->
            assertEquals(name, emptyList<String>(), JsonSchemaSubset.violations(schema, Json.parseToJsonElement(text)))
        }
    }

    @Test
    fun noBundledProfileIsManufacturerWideOrAnUnknownEffect() {
        decoded().forEach { profile ->
            assertTrue(profile.scope.device.isNotBlank())
            assertFalse(profile.scope.device.equals("motorola", ignoreCase = true))
            profile.quirks.forEach { quirk ->
                assertTrue("${quirk.quirkId}: ${quirk.effect}", quirk.knownEffect != null)
            }
        }
    }

    @Test
    fun theRazrRearCameraGetsBothHighSpeedQuirks() {
        val resolution = razr().resolve("0")
        assertEquals(ProfileStatus.ACTIVE, resolution.status)
        assertEquals(ProfileBuildCheck.VERIFIED, resolution.buildCheck)
        assertTrue(resolution.applies(QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA))
        assertTrue(resolution.applies(QuirkEffect.MOTO_HIGH_SPEED_SESSION_KEYS))
        assertEquals(emptyList<String>(), resolution.warnings)
    }

    @Test
    fun aRazrOnAnotherBuildKeepsTheQuirksAndSaysItIsNotVerified() {
        val resolution = razr(fingerprint = "motorola/blanc_g/blanc:16/NEXT.1/1:user/release-keys").resolve("0")
        assertEquals(ProfileStatus.ACTIVE, resolution.status)
        assertEquals(ProfileBuildCheck.NOT_VERIFIED_ON_THIS_BUILD, resolution.buildCheck)
        assertTrue(resolution.applies(QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA))
        assertTrue(resolution.applies(QuirkEffect.MOTO_HIGH_SPEED_SESSION_KEYS))
        assertTrue(resolution.warnings.single().contains("not verified on this build"))
    }

    @Test
    fun otherMotorolaModelsAndOtherRazrCamerasGetNoQuirks() {
        for (resolution in listOf(razr(device = "tundra").resolve("0"), razr().resolve("1"), razr().resolve("5"))) {
            assertEquals(ProfileStatus.UNKNOWN, resolution.status)
            assertFalse(resolution.applies(QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA))
            assertFalse(resolution.applies(QuirkEffect.MOTO_HIGH_SPEED_SESSION_KEYS))
        }
    }

    @Test
    fun anInvalidDocumentIsLoggedAndSkippedWhileTheOthersLoad() {
        val logged = mutableListOf<String>()
        val quirks = BundledDeviceQuirks.fromDocuments(
            documents + ("broken.json" to "{"),
            "blanc",
            decoded().single().scope.buildFingerprint,
            logged::add,
        )
        assertTrue(quirks.resolve("0").applies(QuirkEffect.MOTO_HIGH_SPEED_SESSION_KEYS))
        assertTrue(logged.single().contains("broken.json"))
    }

    @Test
    fun aTamperedProfileIsRejectedAndAppliesNothing() {
        val tampered = documents.mapValues { (_, text) -> text.replace("\"enabled\": true", "\"enabled\": false") }
        val quirks = BundledDeviceQuirks.fromDocuments(tampered, "blanc", decoded().single().scope.buildFingerprint)
        val resolution = quirks.resolve("0")
        assertEquals(ProfileStatus.REJECTED, resolution.status)
        assertFalse(resolution.applies(QuirkEffect.MOTO_HIGH_SPEED_SESSION_KEYS))
    }
}
