/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.EqFilterType
import com.librestatic.opencinecam.camera.EqPoint
import com.librestatic.opencinecam.camera.EqProfile
import com.librestatic.opencinecam.camera.toJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The equaliser profiles shipped in `app/src/main/profiles/eq-profiles` (PLAN-074 decision 8). */
class BundledEqProfilesTest {
    private val directory = File("src/main/profiles/$BUNDLED_EQ_PROFILE_DIRECTORY")

    private fun shipped(): Map<String, String> =
        directory.listFiles { file -> file.name.endsWith(".json") }.orEmpty().associate { it.name to it.readText() }

    @Test
    fun everyShippedProfileDecodes() {
        val documents = shipped()
        assertTrue(documents.isNotEmpty())
        val problems = mutableListOf<String>()
        val bundled = BundledEqProfiles.fromDocuments(documents) { problems += it }
        assertEquals(emptyList<String>(), problems)
        assertEquals(documents.size, bundled.profiles.size)
        assertTrue(bundled.profiles.all { it.bundled && it.device != null })
    }

    @Test
    fun theRazrProfileShipsItsMeasuredCurve() {
        val profile = BundledEqProfiles.fromDocuments(shipped()).forDevice("blanc")!!
        assertEquals("bundled-motorola-blanc", profile.id)
        assertEquals("Razr Fold microphones", profile.name)
        assertFalse(profile.curve.isFlat)
        assertEquals(
            EqCurve(points = listOf(
                EqPoint(EqFilterType.BELL, 10_000.0, -9.5, 1.1),
                EqPoint(EqFilterType.BELL, 8_100.0, -9.5, 1.8),
                EqPoint(EqFilterType.HIGH_SHELF, 13_500.0, -3.5, 0.9),
            )),
            profile.curve,
        )
        assertNull(BundledEqProfiles.fromDocuments(shipped()).forDevice("e3q"))
    }

    @Test
    fun aDamagedOrRepeatedDocumentIsSkippedAndEveryProfileIsMarkedBundled() {
        val curve = EqCurve(points = listOf(EqPoint(EqFilterType.BELL, 1_000.0, -3.0, 1.0)))
        fun document(id: String, device: String?) = """{"schema":"opencinecam-eq-profile-v1","id":"$id","name":"Mics","bundled":false,""" +
            (device?.let { "\"device\":\"$it\"," } ?: "") + "\"curve\":${curve.toJson()}}"
        val problems = mutableListOf<String>()
        val bundled = BundledEqProfiles.fromDocuments(mapOf(
            "a.json" to document("one", "blanc"),
            "b.json" to "{broken",
            "c.json" to document("two", null),
            "d.json" to document("one", "other"),
            "e.json" to document("three", "blanc"),
            "f.json" to document("four", "e3q"),
        )) { problems += it }
        assertEquals(4, problems.size)
        assertEquals(listOf(EqProfile("one", "Mics", curve, bundled = true, device = "blanc"),
            EqProfile("four", "Mics", curve, bundled = true, device = "e3q")), bundled.profiles)
    }
}
