/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.core.model.CalibrationFlag
import java.util.Locale
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class LensCalibrationFormatTest {
    @Test fun `ratio labels drop a needless decimal`() {
        assertEquals("0.5×", lensRatioLabel(0.5f))
        assertEquals("1×", lensRatioLabel(1f))
        assertEquals("2×", lensRatioLabel(2.0f))
        assertEquals("3.5×", lensRatioLabel(3.5f))
        assertEquals("0.6×", lensRatioLabel(0.6f))
    }

    @Test fun `before and after read as an arrow, a lone value or nothing`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("6.1 → 1.2", calibrationBeforeAfter(6.1f, 1.2f))
            assertEquals("1.2", calibrationBeforeAfter(null, 1.2f))
            assertEquals("6.1", calibrationBeforeAfter(6.1f, null))
            assertNull(calibrationBeforeAfter(null, null))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun `quality is a clamped percentage`() {
        val row = LensCalibrationLensRow("2", 1f, LensCalibrationLensStatus.DONE, score = 0.724f)
        assertEquals(72, row.qualityPercent())
        assertEquals(100, row.copy(score = 1.4f).qualityPercent())
        assertNull(row.copy(score = null).qualityPercent())
    }

    @Test fun `every failure and flag has a distinct message`() {
        val failures = LensCalibrationFailure.entries.map { it.messageRes() }
        assertEquals(failures.size, failures.toSet().size)
        val flags = CalibrationFlag.entries.map { it.labelRes() }
        assertEquals(flags.size, flags.toSet().size)
    }

    @Test fun `the reference is found by status or by id`() {
        val state = LensCalibrationUiState(referencePhysicalId = "2")
        assertTrue(LensCalibrationLensRow("2", 1f, LensCalibrationLensStatus.DONE).isReferenceIn(state))
        assertTrue(LensCalibrationLensRow("9", 1f, LensCalibrationLensStatus.REFERENCE).isReferenceIn(LensCalibrationUiState()))
        assertEquals(false, LensCalibrationLensRow("3", 0.5f, LensCalibrationLensStatus.DONE).isReferenceIn(state))
    }

    @Test fun `the catalog finds the page by what a user would type`() {
        val label: (Int) -> String = { "Lens calibration" }
        listOf("lens", "calibration", "zoom", "colour match", "sensor").forEach { query ->
            assertTrue(query, "lens-calibration" in SettingsCatalog.search(query, null, label))
        }
    }
}
