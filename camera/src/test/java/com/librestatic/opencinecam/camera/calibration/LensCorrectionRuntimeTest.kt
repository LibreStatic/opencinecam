/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import com.librestatic.opencinecam.core.model.CalibrationSessionKind
import com.librestatic.opencinecam.core.model.CalibrationSet
import com.librestatic.opencinecam.core.model.CalibrationSourcePath
import com.librestatic.opencinecam.core.model.LensCalibrationCodec
import com.librestatic.opencinecam.core.model.LensCorrection
import com.librestatic.opencinecam.core.model.Similarity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LensCorrectionRuntimeTest {
    private val matrix = listOf(0.9f, 0.05f, 0.05f, 0.1f, 0.8f, 0.1f, 0.0f, 0.2f, 0.8f)

    private fun lens(id: String, align: Similarity = Similarity.IDENTITY, ev: Float = 0f, m: List<Float> = LensCorrection.IDENTITY_MATRIX) =
        LensCorrection.identity(id).copy(colorMatrix = m, evOffset = ev, align = align, gains = listOf(1.1f, 1f, 0.9f))

    private fun set(vararg lenses: LensCorrection) = CalibrationSet(
        fingerprint = "fp", logicalId = "0", sessionKind = CalibrationSessionKind.LOGICAL, referencePhysicalId = "2",
        sourcePath = CalibrationSourcePath.SDR, createdAtMs = 1L, illuminantHint = null, lenses = lenses.toList(),
    )

    @Test fun matrixIsUploadedColumnMajor() {
        val table = LensCorrectionTable.build(set(lens("2"), lens("3", m = matrix)), alignmentEnabled = false, aspect = 1.33f)
        val m = table.entryFor("3").colorMatrix
        // GLSL mat3 element [column][row]; the model's row r, column c is matrix[r * 3 + c].
        for (c in 0 until 3) for (r in 0 until 3) assertEquals(matrix[r * 3 + c], m[c * 3 + r], 0f)
        // The first GL column is the first matrix column (0.9, 0.1, 0.0).
        assertArrayEquals(floatArrayOf(0.9f, 0.1f, 0.0f), m.copyOfRange(0, 3), 0f)
    }

    @Test fun entryCarriesGainsEvAndHash() {
        val s = set(lens("2"), lens("3", ev = 0.25f))
        val table = LensCorrectionTable.build(s, false, 1f)
        assertEquals(0.25f, table.entryFor("3").evOffset, 0f)
        assertArrayEquals(floatArrayOf(1.1f, 1f, 0.9f), table.entryFor("3").gains, 0f)
        assertEquals(LensCalibrationCodec.sha256(s), table.setSha256)
    }

    @Test fun unknownAndNullPhysicalIdsUseTheIdentityColourDefault() {
        val table = LensCorrectionTable.build(set(lens("2"), lens("3", m = matrix, ev = 1f)), false, 1f)
        assertSame(table.defaultEntry, table.entryFor("99"))
        assertSame(table.defaultEntry, table.entryFor(null))
        assertArrayEquals(LensCorrectionTable.IDENTITY_MATRIX3, table.defaultEntry.colorMatrix, 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), table.defaultEntry.gains, 0f)
        assertEquals(0f, table.defaultEntry.evOffset, 0f)
    }

    @Test fun alignmentDisabledGivesIdentityGeometryEverywhere() {
        val shifted = Similarity(tx = 0.05f, ty = -0.02f, scale = 1.1f, rotationRad = 0.03f)
        val table = LensCorrectionTable.build(set(lens("2"), lens("3", shifted)), alignmentEnabled = false, aspect = 1.33f)
        assertEquals(1f, table.sessionZoom, 0f)
        for (entry in listOf(table.entryFor("2"), table.entryFor("3"), table.defaultEntry)) {
            assertArrayEquals(LensCorrectionTable.IDENTITY_MATRIX4, entry.uvMatrix, 0f)
        }
    }

    @Test fun marginIsUniformAcrossLenses() {
        val shifted = Similarity(tx = 0.05f, ty = -0.02f, scale = 1.04f, rotationRad = 0.02f)
        val aspect = 4f / 3f
        val table = LensCorrectionTable.build(set(lens("2"), lens("3", shifted), lens("5", Similarity(scale = 0.97f))), true, aspect)
        val expected = LensAlignment.requiredZoom(shifted.tx, shifted.ty, shifted.scale, shifted.rotationRad, aspect)
        assertTrue(expected > 1f)
        assertEquals(expected, table.sessionZoom, 1e-6f)
        // The unit square's centre maps to 0.5 + t / zoom; a zoomed identity lens shows the pure margin.
        for (id in listOf("2", "5", "99", null)) {
            val e = table.entryFor(id)
            assertEquals("scale of $id", 1f / expected, e.uvMatrix[0] / (if (id == "5") 0.97f else 1f), 1e-5f)
        }
        // The reference lens (identity similarity) and the default crop identically.
        assertArrayEquals(table.entryFor("2").uvMatrix, table.defaultEntry.uvMatrix, 1e-6f)
        // The shifted lens needs exactly the session zoom: its mapped corners touch the edge, none beyond.
        val m = table.entryFor("3").uvMatrix
        for (u in listOf(0f, 1f)) for (v in listOf(0f, 1f)) {
            val su = m[0] * u + m[4] * v + m[12]
            val sv = m[1] * u + m[5] * v + m[13]
            assertTrue("u=$su", su in -1e-4f..1f + 1e-4f)
            assertTrue("v=$sv", sv in -1e-4f..1f + 1e-4f)
        }
    }

    @Test fun identityCalibrationAtAspectOneIsAnIdentityMatrix() {
        val table = LensCorrectionTable.build(set(lens("2"), lens("3")), true, 1f)
        assertArrayEquals(LensCorrectionTable.IDENTITY_MATRIX4, table.entryFor("3").uvMatrix, 1e-6f)
    }

    @Test fun multiplyAppliesTheRightFactorFirst() {
        // lhs translates by (0.1, 0), rhs scales by 2: lhs * rhs maps u -> 2u + 0.1.
        val translate = LensCorrectionTable.IDENTITY_MATRIX4.also { it[12] = 0.1f }
        val scale = LensCorrectionTable.IDENTITY_MATRIX4.also { it[0] = 2f }
        val product = multiplyColumnMajor4(translate, scale)
        assertEquals(2f, product[0], 0f)
        assertEquals(0.1f, product[12], 1e-7f)
        val reversed = multiplyColumnMajor4(scale, translate)
        assertEquals(0.2f, reversed[12], 1e-7f)
    }

    @Test fun timelineExactAndFallbackLookups() {
        val t = PhysicalTimeline()
        assertNull(t.lookup(5).physicalId)
        assertFalse(t.lookup(5).exact)
        t.note(100, "2"); t.note(200, "3"); t.note(300, "5")
        assertEquals("3", t.lookup(200).physicalId); assertTrue(t.lookup(200).exact)
        // No exact match: the greatest timestamp below.
        assertEquals("3", t.lookup(250).physicalId); assertFalse(t.lookup(250).exact)
        assertEquals("5", t.lookup(10_000).physicalId); assertFalse(t.lookup(10_000).exact)
        // Older than everything: the last known id.
        assertEquals("5", t.lookup(1).physicalId); assertFalse(t.lookup(1).exact)
    }

    @Test fun timelineWrapsAroundAfter64Entries() {
        val t = PhysicalTimeline()
        for (i in 1..100) t.note(i * 10L, "id$i")
        // Entries 37..100 survive; 1..36 were overwritten.
        assertEquals("id100", t.lookup(1000).physicalId); assertTrue(t.lookup(1000).exact)
        assertEquals("id37", t.lookup(370).physicalId); assertTrue(t.lookup(370).exact)
        val gone = t.lookup(360)
        assertFalse(gone.exact)
        assertEquals("id100", gone.physicalId) // older than the oldest kept: last noted id
    }

    @Test fun takeUsageCountsFramesAndFallbacks() {
        val table = LensCorrectionTable.build(set(lens("2"), lens("3")), true, 1f)
        val usage = LensTakeUsage(table, "abc")
        usage.record("2", true); usage.record("2", true); usage.record("3", false); usage.record(null, false)
        val e = usage.evidence()
        assertEquals(mapOf("2" to 2L, "3" to 1L, "unknown" to 1L), e.perLensFrameCounts)
        assertEquals(2L, e.fallbackFrames)
        assertEquals("abc", e.splicedTransformSha256)
        assertEquals(table.setSha256, e.setSha256)
        assertTrue(e.alignment)
    }
}
