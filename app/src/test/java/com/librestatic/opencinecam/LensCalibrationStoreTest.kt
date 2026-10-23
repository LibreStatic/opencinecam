/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.core.model.CalibrationSessionKind
import com.librestatic.opencinecam.core.model.CalibrationSet
import com.librestatic.opencinecam.core.model.CalibrationSourcePath
import com.librestatic.opencinecam.core.model.LensCorrection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LensCalibrationStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun set(fingerprint: String = "fp", logical: String = "0", kind: CalibrationSessionKind = CalibrationSessionKind.LOGICAL, at: Long = 1L) =
        CalibrationSet(
            fingerprint = fingerprint, logicalId = logical, sessionKind = kind, referencePhysicalId = "2",
            sourcePath = CalibrationSourcePath.SDR, createdAtMs = at, illuminantHint = null,
            lenses = listOf(LensCorrection.identity("2"), LensCorrection.identity("3").copy(evOffset = 0.5f)),
        )

    private fun store() = LensCalibrationStore(File(folder.root, "lens-calibration"))

    @Test fun savesAndLoadsCreatingTheDirectory() {
        val store = store()
        assertNull(store.load("fp", "0", CalibrationSessionKind.LOGICAL))
        store.save(set())
        assertEquals(set().copy(lenses = set().lenses), store.load("fp", "0", CalibrationSessionKind.LOGICAL))
        assertEquals(listOf("json"), File(folder.root, "lens-calibration").listFiles()!!.map { it.extension })
    }

    @Test fun keysAreFingerprintLogicalIdAndKind() {
        val store = store()
        store.save(set())
        assertNull(store.load("other", "0", CalibrationSessionKind.LOGICAL))
        assertNull(store.load("fp", "1", CalibrationSessionKind.LOGICAL))
        assertNull(store.load("fp", "0", CalibrationSessionKind.DIRECT_PHYSICAL))
    }

    @Test fun saveReplacesAndDeleteRemoves() {
        val store = store()
        store.save(set(at = 1L))
        store.save(set(at = 2L))
        assertEquals(2L, store.load("fp", "0", CalibrationSessionKind.LOGICAL)!!.createdAtMs)
        assertEquals(1, store.all().size)
        store.delete("fp", "0", CalibrationSessionKind.LOGICAL)
        assertNull(store.load("fp", "0", CalibrationSessionKind.LOGICAL))
        store.delete("fp", "0", CalibrationSessionKind.LOGICAL)
    }

    @Test fun invalidFileReadsAsNullAndIsSkippedByAll() {
        val store = store()
        store.save(set())
        store.save(set(logical = "1", at = 5L))
        File(folder.root, "lens-calibration").listFiles()!!.first().writeText("{broken")
        assertEquals(1, store.all().size)
        val broken = listOf("0", "1").count { store.load("fp", it, CalibrationSessionKind.LOGICAL) == null }
        assertEquals(1, broken)
    }

    @Test fun allListsNewestFirst() {
        val store = store()
        store.save(set(logical = "0", at = 1L))
        store.save(set(logical = "1", at = 9L))
        assertEquals(listOf("1", "0"), store.all().map { it.logicalId })
    }

    @Test fun noStrayTempFilesRemain() {
        val store = store()
        store.save(set())
        assertTrue(File(folder.root, "lens-calibration").listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
