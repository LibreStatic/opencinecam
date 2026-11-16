/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.AnamorphicOutputMode
import com.librestatic.opencinecam.camera.AnamorphicSqueeze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStatusFormatTest {
    @Test fun timeLeftNeedsBothFreeSpaceAndBitrate() {
        assertNull(recordTimeLeftMs(null, 150_000_000L))
        assertNull(recordTimeLeftMs(0L, 150_000_000L))
        assertNull(recordTimeLeftMs(1_000_000_000L, 0L))
        assertNull(recordTimeLeftMs(1_000_000_000L, null))
        // 20 Mbps is 150 MB a minute.
        assertEquals(400_000L, recordTimeLeftMs(1_000_000_000L, 150_000_000L))
        assertEquals(6_400_000L, recordTimeLeftMs(32_000_000_000L, 300_000_000L))
        // A huge drive does not overflow.
        assertTrue(recordTimeLeftMs(Long.MAX_VALUE / 2, 1_000L)!! > 0L)
    }

    @Test fun timeLeftHalvesAtSixtyFpsWithTheSameBitrateSetting() {
        val settings = com.librestatic.opencinecam.CameraSettings(videoBitrateMbps = 20, audioEnabled = false)
        fun left(fps: Int) = recordTimeLeftMs(64_000_000_000L,
            com.librestatic.opencinecam.service.projectedTakeBytesPerMinute(CaptureMode.VIDEO, settings, fps, false, false))!!
        assertEquals(2.0, left(30).toDouble() / left(60), 0.01)
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
