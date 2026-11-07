/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawNoiseProfilesTest {
    private val bundled: List<RawNoiseProfile> by lazy {
        val messages = mutableListOf<String>()
        RawNoiseProfiles.parse(File("src/main/profiles/${RawNoiseProfiles.ASSET}").readText()) { messages += it }
            .also { assertTrue(messages.toString(), messages.isEmpty()) }
    }

    private val razr: RawNoiseProfile get() = requireNotNull(RawNoiseProfiles.find(bundled, "blanc", "0", 4000, 3000))

    @Test
    fun theBundledRazrProfileIsTheMeasuredOne() {
        val profile = razr
        assertEquals("blanc/0", profile.id)
        assertEquals(64, profile.blackLevel)
        assertEquals(listOf(143, 200, 400, 800, 1600, 3200, 6400), profile.points.map { it.iso })
        // Negative fits were clamped to 0 when the profile was written.
        assertTrue(profile.points.all { it.a >= 0.0 })
        assertEquals(0.0, profile.points.single { it.iso == 1600 }.a, 0.0)
    }

    @Test
    fun aProfileAppliesOnlyToItsDeviceCameraAndRawSize() {
        assertNotNull(RawNoiseProfiles.find(bundled, "blanc", "0", 4000, 3000))
        assertNull(RawNoiseProfiles.find(bundled, "blanc", "1", 4000, 3000))
        assertNull(RawNoiseProfiles.find(bundled, "e3q", "0", 4000, 3000))
        // Another RAW size of the same camera is not what was measured.
        assertNull(RawNoiseProfiles.find(bundled, "blanc", "0", 4080, 3060))
        assertNull(RawNoiseProfiles.find(bundled, "blanc", "0", 3000, 4000))
    }

    @Test
    fun theModelIsLinearInIsoBetweenPoints() {
        val at143 = razr.modelAt(143)
        assertEquals(0.3747, at143.a, 1e-12)
        assertEquals(0.04022, at143.b, 1e-12)
        val at1600 = razr.modelAt(1600)
        assertEquals(0.0, at1600.a, 1e-12)
        assertEquals(0.43025, at1600.b, 1e-12)
        // Halfway between 200 and 400.
        val at300 = razr.modelAt(300)
        assertEquals((0.4825 + 0.5855) / 2, at300.a, 1e-12)
        assertEquals((0.05473 + 0.10608) / 2, at300.b, 1e-12)
        // A quarter of the way from 3200 to 6400.
        val at4000 = razr.modelAt(4000)
        assertEquals(0.5951 * 0.25, at4000.a, 1e-12)
        assertEquals(0.86029 + (1.74443 - 0.86029) * 0.25, at4000.b, 1e-12)
    }

    @Test
    fun outsideTheMeasuredRangeTheEndsHoldAndShotNoiseScalesWithIso() {
        val low = razr.modelAt(50)
        assertEquals(0.3747, low.a, 1e-12)
        assertEquals(0.04022, low.b, 1e-12)
        val high = razr.modelAt(12_800)
        assertEquals(0.5951, high.a, 1e-12)
        assertEquals(1.74443 * 2, high.b, 1e-12)
        val highest = razr.modelAt(6400)
        assertEquals(1.74443, highest.b, 1e-12)
    }

    @Test
    fun anInvalidEntryIsSkippedAndAnotherSchemaYieldsNone() {
        val messages = mutableListOf<String>()
        val parsed = RawNoiseProfiles.parse(
            """
            {"schemaVersion": 1, "profiles": [
              {"device": "a", "cameraId": "0", "rawWidth": 4000, "rawHeight": 3000, "blackLevel": 64,
               "points": [{"iso": 400, "a": 0.5, "b": 0.1}, {"iso": 200, "a": 0.5, "b": 0.05}]},
              {"device": "b", "cameraId": "0", "rawWidth": 4000, "rawHeight": 3000, "blackLevel": 64,
               "points": [{"iso": 100, "a": -0.1, "b": 0.05}]},
              {"device": "c", "cameraId": "2", "rawWidth": 4000, "rawHeight": 3000, "blackLevel": 64,
               "points": [{"iso": 100, "a": 0.2, "b": 0.05}]}
            ]}
            """.trimIndent(),
        ) { messages += it }
        assertEquals(listOf("c/2"), parsed.map { it.id })
        assertEquals(2, messages.size)
        // A single point is enough: below it the point, above it b scales.
        assertEquals(0.1, parsed.single().modelAt(200).b, 1e-12)
        assertEquals(emptyList<RawNoiseProfile>(), RawNoiseProfiles.parse("""{"schemaVersion": 2, "profiles": []}"""))
        assertEquals(emptyList<RawNoiseProfile>(), RawNoiseProfiles.parse("not json"))
        assertThrows(IllegalArgumentException::class.java) { RawNoisePoint(100, 0.1, 0.0) }
    }
}
