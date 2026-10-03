/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.AnamorphicOutputMode
import com.librestatic.opencinecam.camera.AnamorphicSqueeze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureStatusFormatTest {
    @Test fun timeLeftNeedsBothFreeSpaceAndBitrate() {
        assertNull(recordTimeLeftMs(null, 20))
        assertNull(recordTimeLeftMs(0L, 20))
        assertNull(recordTimeLeftMs(1_000_000_000L, 0))
        assertEquals(400_000L, recordTimeLeftMs(1_000_000_000L, 20))
        assertEquals(6_400_000L, recordTimeLeftMs(32_000_000_000L, 40))
    }

    @Test fun timeLeftReadsInHoursAndMinutes() {
        assertEquals("< 1 min", formatRecordTimeLeft(59_999L))
        assertEquals("< 1 min", formatRecordTimeLeft(-5L))
        assertEquals("1 min", formatRecordTimeLeft(60_000L))
        assertEquals("45 min", formatRecordTimeLeft(45 * 60_000L))
        assertEquals("3 h", formatRecordTimeLeft(3 * 3_600_000L))
        assertEquals("2 h 15 min", formatRecordTimeLeft(135 * 60_000L))
        // Minutes round down so the estimate never promises more than the card holds.
        assertEquals("1 h 46 min", formatRecordTimeLeft(6_400_000L))
    }

    @Test fun anamorphicLabelOnlyWithASqueeze() {
        assertNull(anamorphicStatusLabel(AnamorphicSqueeze.NONE, AnamorphicOutputMode.DESQUEEZED))
        assertEquals("ANA 1.33x/DQ", anamorphicStatusLabel(AnamorphicSqueeze.SQUEEZE_1_33X, AnamorphicOutputMode.DESQUEEZED))
        assertEquals("ANA 2x/SQ", anamorphicStatusLabel(AnamorphicSqueeze.SQUEEZE_2X, AnamorphicOutputMode.SQUEEZED))
    }
}
