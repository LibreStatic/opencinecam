/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.storage.*
import org.junit.Assert.*
import org.junit.Test

class MediaShareBlockerTest {
    private val original = LocalMediaArtifact("content://media/external_primary/video/media/1", "OCC_1.mp4", "video/mp4", 10, 1)
    private val sidecar = LocalMediaArtifact("content://media/external_primary/downloads/2", "OCC_1.json", "application/json", 1, 1)
    private fun take(status: LocalMediaRelationStatus = LocalMediaRelationStatus.DECLARED,
        slate: ProductionSlateSettings? = ProductionSlateSettings(scene = "1"), originals: List<LocalMediaArtifact> = listOf(original)) =
        LocalMediaTake("1", original, originals, listOf(sidecar), LocalMediaKind.VIDEO, slate, status)
    private fun settings(content: MediaShareContent = MediaShareContent.ORIGINALS_AND_METADATA,
        metadata: MediaShareMetadata = MediaShareMetadata.BOTH) = MediaSharingSettings(content, metadata)

    @Test fun aCompleteTakeIsReady() {
        assertNull(mediaShareBlocker(take(), settings()))
        assertTrue(canPrepareMediaShare(take(), settings()))
    }

    @Test fun anUncheckedGroupBlocksMetadataAndOffersOriginalsOnly() {
        for (status in LocalMediaRelationStatus.entries - LocalMediaRelationStatus.DECLARED) {
            val blocked = take(status)
            assertEquals(MediaShareBlocker.GROUP_UNCHECKED, mediaShareBlocker(blocked, settings()))
            assertEquals(MediaShareBlocker.GROUP_UNCHECKED, mediaShareBlocker(blocked, settings(MediaShareContent.METADATA_ONLY)))
            val fixed = mediaShareFix(MediaShareBlocker.GROUP_UNCHECKED, settings())!!
            assertEquals(MediaShareContent.ORIGINALS_ONLY, fixed.content)
            assertNull(mediaShareBlocker(blocked, fixed))
        }
    }

    @Test fun aMissingSlateBlocksProductionDetailsAndOffersTechnicalOnly() {
        val unslated = take(slate = null)
        for (metadata in listOf(MediaShareMetadata.PRODUCTION, MediaShareMetadata.BOTH)) {
            assertEquals(MediaShareBlocker.NO_SLATE, mediaShareBlocker(unslated, settings(metadata = metadata)))
        }
        assertNull(mediaShareBlocker(unslated, settings(metadata = MediaShareMetadata.TECHNICAL)))
        val fixed = mediaShareFix(MediaShareBlocker.NO_SLATE, settings())!!
        assertEquals(MediaShareMetadata.TECHNICAL, fixed.metadata)
        assertNull(mediaShareBlocker(unslated, fixed))
    }

    @Test fun originalsOnlyNeedsOnlyOriginals() {
        assertNull(mediaShareBlocker(take(LocalMediaRelationStatus.MISSING_METADATA, slate = null), settings(MediaShareContent.ORIGINALS_ONLY)))
        assertEquals(MediaShareBlocker.NO_ORIGINALS,
            mediaShareBlocker(take(originals = emptyList()), settings(MediaShareContent.ORIGINALS_ONLY)))
        assertNull(mediaShareFix(MediaShareBlocker.NO_ORIGINALS, settings()))
        assertFalse(canPrepareMediaShare(take(originals = emptyList()), settings(MediaShareContent.ORIGINALS_ONLY)))
    }
}
