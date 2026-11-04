/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCC-PLAN-071 §8 route 2: the CinemaDNG folders the export writes under Documents never reach
 * the gallery. A row is listed only when [isCatalogPath] holds, so every exported DNG, WAV and
 * `.nomedia` must fail it, while the RAW take folder beside them keeps working.
 */
class CinemaDngExportCatalogTest {
    private val exported = listOf(
        "Documents/OpenCineCam/CinemaDNG/",
        "Documents/OpenCineCam/CinemaDNG/A001_C001/",
        "Documents/OpenCineCam/CinemaDNG/A001_C001_2/",
        "documents/opencinecam/cinemadng/A001_C001/",
    )

    @Test fun exportedFoldersAreNeverCatalogPaths() {
        for (path in exported) {
            assertTrue(path, isCinemaDngExportPath(path))
            assertFalse(path, isCatalogPath(path))
            assertNull(path, mediaNamespace(path))
            assertFalse(path, isRawVideoRow(path, "A001_C001_000000.dng"))
            assertFalse(path, isRawVideoRow(path, "A001_C001.wav"))
            // Even an .ocraw someone copies there is not a take.
            assertFalse(path, isRawVideoRow(path, "A001_C001.ocraw"))
        }
    }

    @Test fun theRawFolderAndTheOtherRootsAreUnaffected() {
        assertFalse(isCinemaDngExportPath(RAW_VIDEO_PATH))
        assertTrue(isCatalogPath(RAW_VIDEO_PATH))
        assertTrue(isRawVideoRow(RAW_VIDEO_PATH, "A001_C001.ocraw"))
        assertTrue(isCatalogPath("DCIM/OpenCineCam/"))
        assertFalse(isCinemaDngExportPath("Documents/OpenCineCam/CinemaDNGs/"))
        assertFalse(isCinemaDngExportPath("Documents/OpenCineCam/"))
    }
}
