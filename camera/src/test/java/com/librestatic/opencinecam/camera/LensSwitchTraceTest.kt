/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensSwitchTraceTest {
    private val frameNs = 33_333_333L

    @Test fun logsTheWindowAroundASwitchAndTheGapInIt() {
        val lines = mutableListOf<String>()
        val trace = LensSwitchTrace(before = 2, after = 3, log = { lines += it })
        var ts = 1_000_000_000L
        for (frame in 0L until 10L) { trace.onResult(frame, ts, "2", 2.9f, 10_000_000L); ts += frameNs }
        assertTrue(lines.isEmpty())
        // The switch frame arrives four intervals late and three frame numbers were skipped.
        ts += 3 * frameNs
        trace.onResult(13L, ts, "5", 3.0f, 10_000_000L)
        for (frame in 14L until 20L) { ts += frameNs; trace.onResult(frame, ts, "5", 3.0f, 10_000_000L) }
        assertEquals(2 + 1 + 3, lines.size)
        assertTrue(lines[0], lines[0].startsWith("result frame=8 "))
        assertEquals("switch 2->5 frame=13 ts=$ts".substringBefore(" ts="), lines[2].substringBefore(" ts="))
        assertTrue(lines[3], lines[3].contains("frame=13 dt=133.3ms skipped=3 lens=5"))
    }

    @Test fun outsideASwitchOnlyGapsAreLogged() {
        val lines = mutableListOf<String>()
        val trace = LensSwitchTrace(log = { lines += it })
        var ts = 1_000_000_000L
        for (frame in 0L until 5L) { trace.onResult(frame, ts, "2", 1f, null); ts += frameNs }
        ts += frameNs
        trace.onResult(5L, ts, "2", 1f, null)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("gap result frame=5 dt=66.7ms skipped=0"))
    }

    @Test fun viewfinderReportsLateAndRepeatedFrames() {
        val lines = mutableListOf<String>()
        val trace = ViewfinderGapTrace(gapMs = 50.0, log = { lines += it })
        trace.onFrame(1_000L, 0L)
        trace.onFrame(1_000L + frameNs, frameNs)
        trace.onFrame(1_000L + frameNs, 2 * frameNs)
        trace.onFrame(1_000L + 4 * frameNs, 5 * frameNs)
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("viewfinder repeat"))
        assertTrue(lines[1], lines[1].contains("wall=100.0ms sensor=100.0ms"))
    }
}
