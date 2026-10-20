/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePolicyTest {
    private val scope = DeviceProfileScope(
        device = "blanc",
        buildFingerprint = "fp",
        cameraId = "0",
        physicalCameraId = null,
        codecName = "hevc",
        protocolVersion = "p1",
        characteristicsDigest = "digest",
    )
    private val query = DeviceProfileQuery("blanc", "fp", "0", codecName = "hevc")
    private val physical = DeviceProfileQuirk("physical", QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA.id, true)
    private val keys = DeviceProfileQuirk("keys", QuirkEffect.MOTO_HIGH_SPEED_SESSION_KEYS.id, true)

    private fun profile(
        id: String,
        trust: ProfileTrust,
        quirks: List<DeviceProfileQuirk>,
        profileScope: DeviceProfileScope = scope,
    ): DeviceProfile {
        val unsigned = DeviceProfile("1.0.0", id, "fixture", trust, profileScope, null, quirks)
        return if (trust == ProfileTrust.BUNDLED_TRUSTED) {
            unsigned.copy(
                signature = ProfileSignature("SHA-256", "release-key", DeviceProfileVerifier.canonicalDigest(unsigned)),
            )
        } else {
            unsigned
        }
    }

    @Test
    fun bundledWinsOverImportedButUserKillSwitchNarrowsOnly() {
        val engine = ProfilePolicyEngine(DeviceProfileStore())
        assertEquals(
            ProfileStoreStatus.STORED,
            engine.importUntrusted(
                profile("imported", ProfileTrust.IMPORTED_UNTRUSTED, listOf(DeviceProfileQuirk("physical", QuirkEffect.MOTO_HIGH_SPEED_SESSION_KEYS.id, true))),
            ),
        )
        assertEquals(
            ProfileStoreStatus.STORED,
            engine.loadBundled(profile("bundled", ProfileTrust.BUNDLED_TRUSTED, listOf(physical))),
        )
        val before = engine.resolve(query)
        assertEquals(ProfileStatus.ACTIVE, before.status)
        assertEquals(QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA, before.quirks.single().effect)
        assertEquals("bundled", before.profileId)
        assertTrue(before.applies(QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA))
        assertFalse(before.applies(QuirkEffect.MOTO_HIGH_SPEED_SESSION_KEYS))
        val effect = QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA
        assertEquals(OverrideStatus.REJECTED_BROADENING, engine.setUserKillSwitch(query, "physical", effect, enabled = true))
        assertEquals(OverrideStatus.APPLIED, engine.setUserKillSwitch(query, "physical", effect, enabled = false))
        assertEquals(OverrideStatus.DUPLICATE_OVERRIDE, engine.setUserKillSwitch(query, "physical", effect, enabled = false))
        val narrowed = engine.resolve(query)
        assertEquals(false, narrowed.quirks.single().enabled)
        assertEquals(ProfileTrust.USER_OVERRIDE, narrowed.quirks.single().source)
        assertFalse(narrowed.applies(effect))
    }

    @Test
    fun otherDeviceOrCameraIsUnknownAndCleanupIsBounded() {
        val engine = ProfilePolicyEngine(DeviceProfileStore(), maxOverrides = 1)
        val effect = QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA
        assertEquals(OverrideStatus.APPLIED, engine.setUserKillSwitch(query, "a", effect, enabled = false))
        assertEquals(OverrideStatus.CAPACITY_EXCEEDED, engine.setUserKillSwitch(query, "b", effect, enabled = false))
        engine.loadBundled(profile("bundled", ProfileTrust.BUNDLED_TRUSTED, listOf(physical)))
        assertEquals(ProfileStatus.UNKNOWN, engine.resolve(query.copy(device = "other")).status)
        assertEquals(ProfileStatus.UNKNOWN, engine.resolve(query.copy(cameraId = "1")).status)
        assertFalse(engine.resolve(query.copy(device = "other")).applies(effect))
        engine.clearOverrides()
        assertEquals(0, engine.overrideCount())
        assertTrue(engine.resolve(query.copy(cameraId = "1")).quirks.isEmpty())
    }

    @Test
    fun aDriftedBuildKeepsTheQuirksActiveWithAWarning() {
        val engine = ProfilePolicyEngine(DeviceProfileStore())
        engine.loadBundled(profile("bundled", ProfileTrust.BUNDLED_TRUSTED, listOf(physical, keys)))
        val same = engine.resolve(query)
        assertEquals(ProfileBuildCheck.VERIFIED, same.buildCheck)
        assertTrue(same.warnings.isEmpty())
        val ota = engine.resolve(query.copy(buildFingerprint = "fp-after-ota"))
        assertEquals(ProfileStatus.ACTIVE, ota.status)
        assertEquals(ProfileBuildCheck.NOT_VERIFIED_ON_THIS_BUILD, ota.buildCheck)
        assertTrue(ota.warnings.single().contains("not verified on this build"))
        assertTrue(ota.applies(QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA))
        assertTrue(ota.applies(QuirkEffect.MOTO_HIGH_SPEED_SESSION_KEYS))
    }

    @Test
    fun aKillSwitchSurvivesABuildChange() {
        val engine = ProfilePolicyEngine(DeviceProfileStore())
        engine.loadBundled(profile("bundled", ProfileTrust.BUNDLED_TRUSTED, listOf(physical)))
        engine.setUserKillSwitch(query, "physical", QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA, enabled = false)
        assertFalse(engine.resolve(query.copy(buildFingerprint = "fp-after-ota")).applies(QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA))
    }

    @Test
    fun anUnknownEffectIsIgnoredWithAWarningAndNeverApplied() {
        val engine = ProfilePolicyEngine(DeviceProfileStore())
        engine.loadBundled(
            profile(
                "bundled",
                ProfileTrust.BUNDLED_TRUSTED,
                listOf(physical, DeviceProfileQuirk("future", "rewrite-the-hal", true)),
            ),
        )
        val resolution = engine.resolve(query)
        assertEquals(listOf("physical"), resolution.quirks.map { it.quirkId })
        assertTrue(resolution.warnings.single().contains("unknown effect 'rewrite-the-hal'"))
    }

    @Test
    fun aProfileWhoseDigestDoesNotVerifyIsRejectedAndApplyNothing() {
        val engine = ProfilePolicyEngine(DeviceProfileStore())
        val good = profile("bundled", ProfileTrust.BUNDLED_TRUSTED, listOf(physical))
        engine.loadBundled(good.copy(quirks = listOf(physical, keys)))
        val resolution = engine.resolve(query)
        assertEquals(ProfileStatus.REJECTED, resolution.status)
        assertNull(resolution.profileId)
        assertFalse(resolution.applies(QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA))
        assertTrue(resolution.warnings.single().contains("digest mismatch"))
    }

    @Test
    fun noProfileResolvesToNone() {
        val engine = ProfilePolicyEngine(DeviceProfileStore())
        assertEquals(QuirkResolution.NONE, engine.resolve(query))
        assertEquals(ProfileBuildCheck.NO_PROFILE, QuirkResolution.NONE.buildCheck)
    }
}
