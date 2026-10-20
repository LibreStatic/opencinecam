/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfilesTest {
    private val scope = DeviceProfileScope(
        device = "blanc",
        buildFingerprint = "fingerprint/a",
        cameraId = "0",
        physicalCameraId = null,
        codecName = null,
        protocolVersion = "probe-1",
        characteristicsDigest = "sha256-camera",
    )
    private val query = DeviceProfileQuery("blanc", "fingerprint/a", "0")

    private fun bundled(profileScope: DeviceProfileScope = scope, id: String = "bundled-1"): DeviceProfile {
        val unsigned = DeviceProfile(
            schemaVersion = "1.0.0",
            profileId = id,
            source = "release",
            trust = ProfileTrust.BUNDLED_TRUSTED,
            scope = profileScope,
            signature = null,
            quirks = listOf(DeviceProfileQuirk("surface-reset", "high-speed-prefer-physical-camera", true)),
        )
        return unsigned.copy(
            signature = ProfileSignature("SHA-256", "release-key", DeviceProfileVerifier.canonicalDigest(unsigned)),
        )
    }

    @Test
    fun bundledProfilesRequireMatchingDigestAndDeviceAndCameraLookup() {
        val bundled = bundled()
        assertEquals(ProfileStatus.ACTIVE, DeviceProfileVerifier.verify(bundled).status)
        val store = DeviceProfileStore()
        assertEquals(ProfileStoreStatus.STORED, store.put(bundled))
        assertEquals(ProfileStoreStatus.DUPLICATE_PROFILE, store.put(bundled))
        val found = store.lookup(query)
        assertEquals(ProfileStatus.ACTIVE, found.status)
        assertEquals(ProfileBuildCheck.VERIFIED, found.buildCheck)
        assertEquals(ProfileStatus.UNKNOWN, store.lookup(query.copy(cameraId = "1")).status)
        assertEquals(ProfileStatus.UNKNOWN, store.lookup(query.copy(device = "other")).status)
    }

    @Test
    fun aBundledProfileWithoutItsDigestIsRejected() {
        assertEquals(ProfileStatus.REJECTED, DeviceProfileVerifier.verify(bundled().copy(signature = null)).status)
        val tampered = bundled().copy(source = "edited after signing")
        assertEquals(ProfileStatus.REJECTED, DeviceProfileVerifier.verify(tampered).status)
    }

    @Test
    fun aDifferentBuildFingerprintStaysActiveButIsNotVerified() {
        val store = DeviceProfileStore()
        store.put(bundled())
        val ota = store.lookup(query.copy(buildFingerprint = "fingerprint/b"))
        assertEquals(ProfileStatus.ACTIVE, ota.status)
        assertEquals(ProfileBuildCheck.NOT_VERIFIED_ON_THIS_BUILD, ota.buildCheck)
        assertEquals("bundled-1", ota.profile?.profileId)
        assertTrue(ota.reason.contains("another build"))
    }

    @Test
    fun theMatchingFingerprintBuildBeatsAnotherBuildOfTheSameTrust() {
        val store = DeviceProfileStore()
        store.put(bundled(scope.copy(buildFingerprint = "fingerprint/old"), id = "old"))
        store.put(bundled(id = "current"))
        assertEquals("current", store.lookup(query).profile?.profileId)
    }

    @Test
    fun physicalCameraAndCodecNarrowTheMatchOnlyWhenTheProfileSetsThem() {
        val narrow = scope.copy(physicalCameraId = "5", codecName = "c2.vendor.hevc")
        assertFalse(narrow.matches(query))
        assertTrue(narrow.matches(query.copy(physicalCameraId = "5", codecName = "c2.vendor.hevc")))
        assertTrue(scope.matches(query.copy(physicalCameraId = "5", codecName = "c2.vendor.hevc")))
    }

    @Test
    fun aBlankOrWildcardDeviceCodenameIsNotAScope() {
        for (device in listOf("", "  ", "*")) {
            try {
                scope.copy(device = device)
                throw AssertionError("'$device' must be rejected")
            } catch (_: IllegalArgumentException) {
                assertTrue(true)
            }
        }
    }

    @Test
    fun importedAndOverrideTrustLabelsRemainExplicitAndNonMatchingScopesAreRetainedAsStale() {
        val imported = DeviceProfile(
            schemaVersion = "1.0.0",
            profileId = "imported-1",
            source = "file://profile.json",
            trust = ProfileTrust.IMPORTED_UNTRUSTED,
            scope = scope,
            signature = null,
            quirks = emptyList(),
        )
        val override = DeviceProfile(
            schemaVersion = "1.0.0",
            profileId = "override-1",
            source = "user",
            trust = ProfileTrust.USER_OVERRIDE,
            scope = scope,
            signature = null,
            quirks = listOf(DeviceProfileQuirk("disable-hlg", "hide-mode", true)),
        )
        val store = DeviceProfileStore()
        assertEquals(ProfileStoreStatus.STORED, store.put(imported))
        assertEquals(ProfileStoreStatus.STORED, store.put(override))
        assertEquals("override-1", store.lookup(query).profile?.profileId)
        assertEquals(ProfileStatus.ACTIVE, store.lookup(query).status)
        val stillThere = store.invalidate(query.copy(buildFingerprint = "fingerprint/b"))
        assertTrue(stillThere.all { it.status == ProfileStatus.ACTIVE })
        val otherCamera = store.invalidate(query.copy(cameraId = "1"))
        assertTrue(otherCamera.all { it.status == ProfileStatus.STALE })
        assertEquals(2, store.size())
        assertTrue(store.remove("override-1"))
        store.clear()
        assertEquals(0, store.size())
    }

    @Test
    fun mismatchedSignatureAndUnsupportedSchemaAreRejected() {
        val imported = DeviceProfile(
            schemaVersion = "1.0.0",
            profileId = "bad-signature",
            source = "fixture",
            trust = ProfileTrust.IMPORTED_UNTRUSTED,
            scope = scope,
            signature = ProfileSignature("SHA-256", "fixture-key", "0".repeat(64)),
            quirks = emptyList(),
        )
        assertEquals(ProfileStatus.REJECTED, DeviceProfileVerifier.verify(imported).status)
        val newerMinor = imported.copy(profileId = "newer", schemaVersion = "1.1.0", signature = null)
        val store = DeviceProfileStore()
        assertEquals(ProfileStoreStatus.STORED, store.put(newerMinor))
        assertFalse(store.lookup(query).status == ProfileStatus.REJECTED)
        try {
            imported.copy(profileId = "unsupported", schemaVersion = "2.0.0")
            throw AssertionError("unsupported schema should fail")
        } catch (_: UnsupportedSchemaMajor) {
            assertTrue(true)
        }
    }

    @Test
    fun effectIdsAreStableAndUnknownIdsAreNotMapped() {
        assertEquals("high-speed-prefer-physical-camera", QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA.id)
        assertEquals("moto-high-speed-session-keys", QuirkEffect.MOTO_HIGH_SPEED_SESSION_KEYS.id)
        assertEquals(QuirkEffect.MOTO_HIGH_SPEED_SESSION_KEYS, QuirkEffect.fromId("moto-high-speed-session-keys"))
        assertNull(QuirkEffect.fromId("do-something-new"))
        assertNull(DeviceProfileQuirk("q", "recreate-session", true).knownEffect)
    }
}
