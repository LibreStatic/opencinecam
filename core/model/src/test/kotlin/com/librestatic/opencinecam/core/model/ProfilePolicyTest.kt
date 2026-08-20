/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePolicyTest {
    private val scope = DeviceProfileScope(
        buildFingerprint = "fp",
        cameraId = "0",
        physicalCameraId = null,
        codecName = "hevc",
        protocolVersion = "p1",
        characteristicsDigest = "digest",
    )

    private fun profile(
        id: String,
        trust: ProfileTrust,
        quirks: List<DeviceProfileQuirk>,
    ): DeviceProfile {
        val unsigned = DeviceProfile("1.0.0", id, "fixture", trust, scope, null, quirks)
        return if (trust == ProfileTrust.BUNDLED_TRUSTED) {
            unsigned.copy(
                signature = ProfileSignature(
                    "SHA-256",
                    "release-key",
                    DeviceProfileVerifier.canonicalDigest(unsigned),
                ),
            )
        } else {
            unsigned
        }
    }

    @Test
    fun bundledWinsOverImportedButUserKillSwitchNarrowsOnly() {
        val store = DeviceProfileStore()
        val engine = ProfilePolicyEngine(store)
        assertEquals(
            ProfileStoreStatus.STORED,
            engine.importUntrusted(
                profile(
                    "imported",
                    ProfileTrust.IMPORTED_UNTRUSTED,
                    listOf(DeviceProfileQuirk("surface-reset", "recreate", true)),
                ),
            ),
        )
        assertEquals(
            ProfileStoreStatus.STORED,
            engine.loadBundled(
                profile(
                    "bundled",
                    ProfileTrust.BUNDLED_TRUSTED,
                    listOf(DeviceProfileQuirk("surface-reset", "keep", true)),
                ),
            ),
        )
        val before = engine.resolve(scope)
        assertEquals(ProfileStatus.ACTIVE, before.status)
        assertEquals("keep", before.quirks.single().effect)
        assertEquals(
            OverrideStatus.REJECTED_BROADENING,
            engine.setUserKillSwitch(scope, "surface-reset", "keep", enabled = true),
        )
        assertEquals(
            OverrideStatus.APPLIED,
            engine.setUserKillSwitch(scope, "surface-reset", "disable", enabled = false),
        )
        assertEquals(
            OverrideStatus.DUPLICATE_OVERRIDE,
            engine.setUserKillSwitch(scope, "surface-reset", "disable", enabled = false),
        )
        val narrowed = engine.resolve(scope)
        assertEquals(false, narrowed.quirks.single().enabled)
        assertEquals(ProfileTrust.USER_OVERRIDE, narrowed.quirks.single().source)
    }

    @Test
    fun mismatchedScopeIsUnknownAndCleanupIsBounded() {
        val engine = ProfilePolicyEngine(DeviceProfileStore(), maxOverrides = 1)
        assertEquals(
            OverrideStatus.APPLIED,
            engine.setUserKillSwitch(scope, "a", "disable-a", enabled = false),
        )
        assertEquals(
            OverrideStatus.CAPACITY_EXCEEDED,
            engine.setUserKillSwitch(scope, "b", "disable-b", enabled = false),
        )
        assertEquals(ProfileStatus.UNKNOWN, engine.resolve(scope.copy(codecName = "avc")).status)
        engine.clearOverrides()
        assertEquals(0, engine.overrideCount())
        assertTrue(engine.resolve(scope).quirks.isEmpty())
    }
}
