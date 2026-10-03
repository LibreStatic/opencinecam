/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.*
import org.junit.Test

class MediaLayoutTest {
    private val compact = WindowWidthClass.COMPACT
    private val medium = WindowWidthClass.MEDIUM
    private val expanded = WindowWidthClass.EXPANDED
    private val large = WindowWidthClass.LARGE
    private val short = WindowHeightClass.COMPACT
    private val regular = WindowHeightClass.MEDIUM
    private val tall = WindowHeightClass.EXPANDED

    @Test fun columnsFollowTheWindowClass() {
        assertEquals(2, galleryColumns(411f, compact, tall))
        assertEquals(1, galleryColumns(260f, compact, regular))
        // Phone landscape: expanded width, short height.
        assertEquals(3, galleryColumns(915f, expanded, short))
        assertEquals(3, galleryColumns(700f, medium, tall))
        assertEquals(3, galleryColumns(620f, expanded, regular))
        assertEquals(4, galleryColumns(1100f, expanded, regular))
        assertEquals(4, galleryColumns(900f, large, tall))
        assertEquals(5, galleryColumns(1180f, large, tall))
        assertEquals(6, galleryColumns(2400f, large, tall))
    }

    @Test fun columnsStayWithinTheBriefRanges() {
        for (width in 300..2600 step 20) {
            assertTrue(galleryColumns(width.toFloat(), expanded, regular) in 3..4)
            assertTrue(galleryColumns(width.toFloat(), large, tall) in 4..6)
            assertEquals(3, galleryColumns(width.toFloat(), medium, regular))
        }
    }

    @Test fun inspectorSitsBesideTheGridOnlyOnLargeOrTallLandscape() {
        assertTrue(mediaInspectorSide(large, tall, landscape = false))
        assertTrue(mediaInspectorSide(large, short, landscape = true))
        assertTrue(mediaInspectorSide(expanded, regular, landscape = true))
        assertFalse(mediaInspectorSide(expanded, short, landscape = true))
        assertFalse(mediaInspectorSide(expanded, tall, landscape = false))
        assertFalse(mediaInspectorSide(medium, regular, landscape = true))
        assertFalse(mediaInspectorSide(compact, tall, landscape = false))
    }

    @Test fun inspectorWidthIsClamped() {
        assertEquals(360f, mediaInspectorWidthDp(700f), 0.001f)
        assertEquals(420f, mediaInspectorWidthDp(1000f), 0.001f)
        assertEquals(480f, mediaInspectorWidthDp(2000f), 0.001f)
    }

    @Test fun detailsAndDialogPlacement() {
        assertTrue(mediaDetailsAsBottomSheet(compact, landscape = false))
        assertFalse(mediaDetailsAsBottomSheet(compact, landscape = true))
        assertFalse(mediaDetailsAsBottomSheet(expanded, landscape = true))
        assertFalse(mediaDialogAtSide(compact)); assertFalse(mediaDialogAtSide(medium))
        assertTrue(mediaDialogAtSide(expanded)); assertTrue(mediaDialogAtSide(large))
    }
}
