/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeScreenTest {
    @Test
    fun layoutModeCoversPortraitLandscapeWideAndFoldable() {
        assertEquals(ProbeLayoutMode.PORTRAIT, probeLayoutMode(390, 844))
        assertEquals(ProbeLayoutMode.LANDSCAPE, probeLayoutMode(844, 390))
        assertEquals(ProbeLayoutMode.WIDE, probeLayoutMode(840, 900))
        assertEquals(ProbeLayoutMode.FOLDABLE, probeLayoutMode(390, 844, separatingFold = true))
    }
}
