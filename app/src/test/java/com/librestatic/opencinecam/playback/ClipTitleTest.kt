/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.storage.LocalMediaArtifact
import com.librestatic.opencinecam.storage.LocalMediaKind
import com.librestatic.opencinecam.storage.LocalMediaRelationStatus
import com.librestatic.opencinecam.storage.LocalMediaTake
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipTitleTest {
    private fun take(slate: ProductionSlateSettings?) = LocalMediaTake("id",
        LocalMediaArtifact("content://media/1", "OCC_0001.mp4", "video/mp4", 1024, 1_700_000_000),
        emptyList(), emptyList(), LocalMediaKind.VIDEO, slate, LocalMediaRelationStatus.DECLARED)

    @Test fun slateFieldsBecomeTitleParts() {
        val parts = clipTitleParts(take(ProductionSlateSettings(scene = " 3 ", reel = "A001", camera = "B", takeNumber = 2)))
        assertEquals(ClipTitleParts("3", "2", "A001", "B", 1_700_000_000), parts)
    }

    @Test fun blankSlateTextIsDropped() {
        assertEquals(ClipTitleParts(null, "1", null, null, 1_700_000_000),
            clipTitleParts(take(ProductionSlateSettings(scene = "  ", reel = "", camera = " "))))
    }

    @Test fun missingSlateKeepsOnlyTheDate() {
        assertEquals(ClipTitleParts(null, null, null, null, 1_700_000_000), clipTitleParts(take(null)))
    }
}
