/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.stillSizeChoices
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionGroupsTest {

    /** The Razr Fold back camera's LOG sizes, as the RES panel receives them. */
    private val razr = listOf(
        3840 to 2160, 4000 to 2250, 3376 to 1898, 3264 to 1836,
        4000 to 3000, 3840 to 2880, 3264 to 2448,
        4000 to 1714, 3840 to 1644,
        3000 to 3000, 2880 to 2880,
        2376 to 2160,
    )

    @Test fun razrSizesFallIntoTheAspectGroupsInPanelOrder() {
        val groups = groupResolutions(razr.shuffled(java.util.Random(7)))
        assertEquals(
            listOf(ResolutionAspect.WIDE, ResolutionAspect.STANDARD, ResolutionAspect.SCOPE, ResolutionAspect.SQUARE, ResolutionAspect.OPEN),
            groups.map { it.aspect },
        )
        assertEquals(listOf(4000 to 2250, 3840 to 2160, 3376 to 1898, 3264 to 1836), groups[0].sizes)
        assertEquals(listOf(4000 to 3000, 3840 to 2880, 3264 to 2448), groups[1].sizes)
        assertEquals(listOf(4000 to 1714, 3840 to 1644), groups[2].sizes)
        assertEquals(listOf(3000 to 3000, 2880 to 2880), groups[3].sizes)
        assertEquals(listOf(2376 to 2160), groups[4].sizes)
    }

    @Test fun emptyGroupsAreLeftOut() {
        assertEquals(listOf(ResolutionAspect.WIDE), groupResolutions(listOf(1920 to 1080, 1280 to 720)).map { it.aspect })
        assertEquals(emptyList<ResolutionGroup>(), groupResolutions(emptyList()))
    }

    @Test fun shortNames() {
        val expected = mapOf(
            (3840 to 2160) to "4K", (4000 to 2250) to "4K+", (3376 to 1898) to "3.4K", (3264 to 1836) to "3.3K",
            (1920 to 1080) to "1080p", (1280 to 720) to "720p", (2560 to 1440) to "1440p",
            (4000 to 3000) to "12 MP", (3840 to 2880) to "4K 4:3", (3264 to 2448) to "8 MP",
            (4000 to 1714) to "4K+ scope", (3840 to 1644) to "4K scope",
            (3000 to 3000) to "3K", (2880 to 2880) to "2.9K",
            (2376 to 2160) to "2.4K",
            (2160 to 3840) to "4K",
        )
        expected.forEach { (size, name) -> assertEquals("$size", name, resolutionShortName(size.first, size.second)) }
    }

    @Test fun megapixelNames() {
        assertEquals("12 MP", megapixelName(4000, 3000))
        assertEquals("8 MP", megapixelName(3264, 2448))
        assertEquals("5 MP", megapixelName(2592, 1944))
        assertEquals("1.6 MP", megapixelName(1440, 1080))
        assertEquals("50 MP", megapixelName(8192, 6144))
    }

    /**
     * OCC-PLAN-069: the Razr Fold's hidden full-FOV still merges above the logical sizes, and its
     * 12.6 MP round to a "13 MP" label so the panel shows it next to — not instead of — "12 MP".
     */
    @Test fun physicalFullFovStillMergesAboveTheLogicalSizes() {
        val logical = listOf(4000 to 3000, 3840 to 2880, 3264 to 2448, 1920 to 1440)
        val merged = logical + listOf(4096 to 3072)
        assertEquals(
            listOf(4096 to 3072, 4000 to 3000, 3840 to 2880, 3264 to 2448, 1920 to 1440),
            stillSizeChoices(merged.shuffled(java.util.Random(3))),
        )
        assertEquals("13 MP", megapixelName(4096, 3072))
    }
}
