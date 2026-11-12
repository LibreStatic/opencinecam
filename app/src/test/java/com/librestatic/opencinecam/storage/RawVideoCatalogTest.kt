/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.GalleryMediaKind
import com.librestatic.opencinecam.GallerySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCC-PLAN-071 slice F1: how the gallery maps MediaStore.Files rows to RAW takes. */
class RawVideoCatalogTest {
    private val artifact = LocalMediaArtifact("content://media/external_primary/file/41", "OCC_TAKE_A001_S13_T01.ocraw",
        RawVideoOutput.MIME_TYPE, 19_660_000_000L, 1_790_000_000L)

    @Test fun theCatalogPathMatchesWhereRawTakesAreWritten() {
        assertEquals(RawVideoOutput.RELATIVE_PATH, RAW_VIDEO_PATH)
        assertEquals(RawVideoOutput.EXTENSION, RAW_VIDEO_EXTENSION)
        assertTrue(isCatalogPath(RAW_VIDEO_PATH))
        // A RAW path carries no take namespace: the take is its one file.
        assertNull(mediaNamespace(RAW_VIDEO_PATH))
    }

    @Test fun onlyOcrawFilesDirectlyInTheRawFolderAreListed() {
        assertTrue(isRawVideoRow("Documents/OpenCineCam/RAW/", "OCC_TAKE_1.ocraw"))
        assertTrue(isRawVideoRow("Documents/OpenCineCam/RAW/", "OCC_TAKE_1.OCRAW"))
        assertFalse(isRawVideoRow("Documents/OpenCineCam/RAW/", ".ocraw"))
        assertFalse(isRawVideoRow("Documents/OpenCineCam/RAW/", "notes.txt"))
        assertFalse(isRawVideoRow("Documents/OpenCineCam/RAW/", "OCC_TAKE_1.ocraw.partial"))
        assertFalse(isRawVideoRow("Documents/OpenCineCam/RAW/sub/", "OCC_TAKE_1.ocraw"))
        assertFalse(isRawVideoRow("Documents/OpenCineCam/", "OCC_TAKE_1.ocraw"))
        assertFalse(isRawVideoRow("Download/OpenCineCam/RAW/", "OCC_TAKE_1.ocraw"))
    }

    @Test fun aRawTakeIsItsOneFile() {
        val take = rawVideoTake(artifact)
        assertEquals(LocalMediaKind.RAW_VIDEO, take.kind)
        assertEquals(artifact, take.primary)
        assertEquals(listOf(artifact), take.originals)
        assertTrue(take.metadata.isEmpty() && take.attachments.isEmpty())
        assertNull(take.slate)
        assertEquals(LocalMediaRelationStatus.LEGACY, take.relationStatus)
        assertEquals("raw:${artifact.uri}", take.id)
    }

    @Test fun theVideoFilterShowsRawTakesAndTheOthersDoNot() {
        val take = rawVideoTake(artifact)
        assertTrue(catalogMatches(take, GallerySettings(kind = GalleryMediaKind.ALL), ""))
        assertTrue(catalogMatches(take, GallerySettings(kind = GalleryMediaKind.VIDEO), ""))
        assertFalse(catalogMatches(take, GallerySettings(kind = GalleryMediaKind.PHOTO), ""))
        assertFalse(catalogMatches(take, GallerySettings(kind = GalleryMediaKind.AUDIO), ""))
        assertFalse(catalogMatches(take, GallerySettings(goodTakesOnly = true), ""))
        assertTrue(catalogMatches(take, GallerySettings(), "s13_t01"))
        assertFalse(catalogMatches(take, GallerySettings(), "s14"))
    }

    @Test fun rawRowsSortAfterTheOtherKindsAtTheSameInstant() {
        val rows = listOf(
            CatalogRow(artifact, 41, LocalMediaKind.RAW_VIDEO, null),
            CatalogRow(artifact.copy(uri = "content://media/external_primary/video/media/40"), 40, LocalMediaKind.VIDEO, null),
            CatalogRow(artifact.copy(uri = "content://media/external_primary/file/39"), 39, null, null),
        )
        assertEquals(listOf(40L, 41L, 39L), rows.sortedWith(catalogComparator(newestFirst = true)).map { it.id })
    }
}
