/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EqProfileCodecTest {
    private val curve = EqCurve(
        points = listOf(
            EqPoint.coerced(EqFilterType.BELL, 8_800.0, -13.0, 1.25),
            EqPoint.coerced(EqFilterType.HIGH_SHELF, 13_500.0, -5.0, 0.707, enabled = false),
        ),
        trimDb = -1.0,
    )
    private val bundled = EqProfile("bundled-motorola-blanc", "Razr Fold microphones", curve, bundled = true, device = "blanc")
    private val user = EqProfile("user-1", "Concert", curve.copy(enabled = false))

    @Test
    fun roundTripsEveryField() {
        assertEquals(bundled, decodeEqProfile(bundled.toJson()))
        assertEquals(user, decodeEqProfile(user.toJson()))
        assertEquals(EqProfile("flat", "Flat", EqCurve()), decodeEqProfile(EqProfile("flat", "Flat", EqCurve()).toJson()))
    }

    @Test
    fun writesTheSchemaAndLeavesOutAnAbsentDevice() {
        val text = user.toJson()
        assertTrue(text.contains("\"schema\":\"$EQ_PROFILE_SCHEMA\""))
        assertFalse(text.contains("device"))
        assertTrue(bundled.toJson().contains("\"device\":\"blanc\""))
    }

    @Test
    fun aDamagedDocumentDecodesToNull() {
        val good = bundled.toJson()
        for (bad in listOf(
            "",
            "{",
            "[]",
            good.replace(EQ_PROFILE_SCHEMA, "opencinecam-eq-profile-v2"),
            good.replace("\"schema\":\"$EQ_PROFILE_SCHEMA\",", ""),
            good.replace("\"id\":\"bundled-motorola-blanc\"", "\"id\":\"has space\""),
            good.replace("\"id\":\"bundled-motorola-blanc\"", "\"id\":7"),
            good.replace("\"name\":\"Razr Fold microphones\"", "\"name\":\"  \""),
            good.replace("\"device\":\"blanc\"", "\"device\":3"),
            """{"schema":"$EQ_PROFILE_SCHEMA","id":"a","name":"A"}""",
            """{"schema":"$EQ_PROFILE_SCHEMA","id":"a","name":"A","curve":"flat"}""",
        )) {
            assertNull(bad, decodeEqProfile(bad))
        }
    }

    @Test
    fun aMissingBundledFlagOrNullDeviceReadsAsAUserProfile() {
        val decoded = decodeEqProfile("""{"schema":"$EQ_PROFILE_SCHEMA","id":"a","name":"A","device":null,"curve":{"points":[]}}""")
        assertEquals(EqProfile("a", "A", EqCurve()), decoded)
    }

    @Test
    fun theCurveIsReadAsTheSettingsStoreReadsIt() {
        val decoded = requireNotNull(decodeEqProfile(
            """{"schema":"$EQ_PROFILE_SCHEMA","id":"a","name":"A","curve":{"trimDb":40,"points":[{"type":"BELL","frequencyHz":5,"gainDb":99,"q":50},{"type":"WARP","frequencyHz":1000}]}}""",
        ))
        assertEquals(EqCurve.MAX_TRIM_DB, decoded.curve.trimDb, 0.0)
        assertEquals(1, decoded.curve.points.size)
        assertEquals(EqPoint.MAX_GAIN_DB, decoded.curve.points.single().gainDb, 0.0)
    }

    @Test
    fun theConstructorRefusesAnInvalidIdOrName() {
        assertThrows(IllegalArgumentException::class.java) { EqProfile("", "A", EqCurve()) }
        assertThrows(IllegalArgumentException::class.java) { EqProfile("a/b", "A", EqCurve()) }
        assertThrows(IllegalArgumentException::class.java) { EqProfile("a", "", EqCurve()) }
        assertThrows(IllegalArgumentException::class.java) { EqProfile("a", "x".repeat(65), EqCurve()) }
        assertTrue(isValidEqProfileId("bundled-motorola-blanc"))
        assertFalse(isValidEqProfileId("x".repeat(65)))
    }
}
