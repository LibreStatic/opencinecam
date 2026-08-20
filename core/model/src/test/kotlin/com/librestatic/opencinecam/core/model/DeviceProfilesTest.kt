/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfilesTest {
    private val scope = DeviceProfileScope(
        buildFingerprint = "fingerprint/a",
        cameraId = "0",
        physicalCameraId = "0-wide",
        codecName = "c2.vendor.hevc",
        protocolVersion = "probe-1",
        characteristicsDigest = "sha256-camera",
    )

    @Test
    fun bundledProfilesRequireMatchingDigestAndExactLookup() {
        val unsigned = DeviceProfile(
            schemaVersion = "1.0.0",
            profileId = "bundled-1",
            source = "release",
            trust = ProfileTrust.IMPORTED_UNTRUSTED,
            scope = scope,
            signature = null,
            quirks = listOf(DeviceProfileQuirk("surface-reset", "recreate-session", true)),
        )
        val digest = DeviceProfileVerifier.canonicalDigest(unsigned.copy(trust = ProfileTrust.BUNDLED_TRUSTED))
        val bundled = unsigned.copy(
            trust = ProfileTrust.BUNDLED_TRUSTED,
            signature = ProfileSignature("SHA-256", "release-key", digest),
        )
        assertEquals(ProfileStatus.ACTIVE, DeviceProfileVerifier.verify(bundled).status)
        val store = DeviceProfileStore()
        assertEquals(ProfileStoreStatus.STORED, store.put(bundled))
        assertEquals(ProfileStoreStatus.DUPLICATE_PROFILE, store.put(bundled))
        assertEquals(ProfileStatus.ACTIVE, store.lookup(scope).status)
        assertEquals(ProfileStatus.UNKNOWN, store.lookup(scope.copy(cameraId = "1")).status)
    }

    @Test
    fun importedAndOverrideTrustLabelsRemainExplicitAndStaleScopesAreRetained() {
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
        assertEquals("override-1", store.lookup(scope).profile?.profileId)
        assertEquals(ProfileStatus.ACTIVE, store.lookup(scope).status)
        val changedScope = scope.copy(characteristicsDigest = "sha256-new")
        val invalidated = store.invalidate(changedScope)
        assertTrue(invalidated.all { it.status == ProfileStatus.STALE })
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
        assertFalse(store.lookup(scope).status == ProfileStatus.REJECTED)
        try {
            imported.copy(profileId = "unsupported", schemaVersion = "2.0.0")
            throw AssertionError("unsupported schema should fail")
        } catch (_: UnsupportedSchemaMajor) {
            assertTrue(true)
        }
    }
}
