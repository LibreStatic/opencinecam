/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IspHistogramTest {
    private fun analysis(atMs: Long, bins: Int = 4) = Camera2Analysis(
        histogram = List(bins) { 1f / bins }, redHistogram = emptyList(), greenHistogram = emptyList(),
        blueHistogram = emptyList(), zebraCells = emptyList(), capturedAtElapsedRealtimeMs = atMs,
    )

    @Test
    fun gateNeedsTheEnableKeyAndASaneBucketCount() {
        val keys = listOf("android.control.mode", IspHistogramKeys.ENABLE)
        assertEquals(256, ispHistogramBuckets(keys, 256))
        assertNull(ispHistogramBuckets(listOf("android.control.mode"), 256))
        assertNull(ispHistogramBuckets(keys, null))
        assertNull(ispHistogramBuckets(keys, 0))
        assertNull(ispHistogramBuckets(keys, 1_000_000))
    }

    @Test
    fun statsTypeOneIsLumaAndAnythingElseIsLabelledBayer() {
        assertEquals(HistogramSource.ISP, ispHistogramSource(1))
        assertEquals(HistogramSource.ISP_BAYER, ispHistogramSource(0))
        assertEquals(HistogramSource.ISP_BAYER, ispHistogramSource(null))
    }

    @Test
    fun mergesBucketsAndPreservesTheTotal() {
        val raw = IntArray(256) { 1 }
        val out = normalizeIspHistogram(raw, 64)!!
        assertEquals(64, out.size)
        out.forEach { assertEquals(1f / 64, it, 1e-6f) }
        assertEquals(1f, out.sum(), 1e-5f)
    }

    @Test
    fun spreadsFewerBucketsOverMoreBins() {
        val out = normalizeIspHistogram(intArrayOf(10, 0), 4)!!
        assertEquals(listOf(.5f, .5f, 0f, 0f), out)
    }

    @Test
    fun sharesABucketAcrossBinsItStraddles() {
        // 3 buckets onto 2 bins: the middle bucket is split in half.
        val out = normalizeIspHistogram(intArrayOf(3, 3, 3), 2)!!
        assertEquals(.5f, out[0], 1e-6f)
        assertEquals(.5f, out[1], 1e-6f)
    }

    @Test
    fun identityWhenSizesMatchAndNegativesReadAsZero() {
        val out = normalizeIspHistogram(intArrayOf(1, -5, 3), 3)!!
        assertEquals(listOf(.25f, 0f, .75f), out)
    }

    @Test
    fun emptyOrZeroHistogramsAreRejected() {
        assertNull(normalizeIspHistogram(IntArray(0), 64))
        assertNull(normalizeIspHistogram(IntArray(8), 64))
        assertNull(normalizeIspHistogram(intArrayOf(1), 0))
    }

    @Test
    fun freshIspReplacesOnlyTheLumaHistogram() {
        val hold = IspHistogramHold()
        hold.offer(intArrayOf(0, 0, 0, 8), HistogramSource.ISP, nowMs = 1_000)
        val merged = hold.merge(analysis(atMs = 1_200))
        assertEquals(listOf(0f, 0f, 0f, 1f), merged.histogram)
        assertEquals(HistogramSource.ISP, merged.histogramSource)
        assertEquals(1_200L, merged.capturedAtElapsedRealtimeMs)
    }

    @Test
    fun staleOrMissingIspFallsBackToTheFrame() {
        val hold = IspHistogramHold()
        val frame = analysis(atMs = 5_000)
        assertSame(frame, hold.merge(frame))
        hold.offer(intArrayOf(1, 1), HistogramSource.ISP_BAYER, nowMs = 5_000 - ISP_HISTOGRAM_STALE_MS)
        assertEquals(HistogramSource.ISP_BAYER, hold.merge(frame).histogramSource)
        hold.offer(intArrayOf(1, 1), HistogramSource.ISP_BAYER, nowMs = 5_000 - ISP_HISTOGRAM_STALE_MS - 1)
        assertSame(frame, hold.merge(frame))
        assertEquals(HistogramSource.FRAME, frame.histogramSource)
    }

    @Test
    fun anIspFrameNewerThanTheAnalysisIsFreshAndClearDropsIt() {
        val hold = IspHistogramHold()
        hold.offer(intArrayOf(2, 2), HistogramSource.ISP, nowMs = 2_000)
        assertTrue(hold.merge(analysis(atMs = 1_900)).histogramSource == HistogramSource.ISP)
        hold.clear()
        assertEquals(HistogramSource.FRAME, hold.merge(analysis(atMs = 2_000)).histogramSource)
    }

    @Test
    fun anUnbinnableFrameHistogramIsLeftAlone() {
        val hold = IspHistogramHold()
        hold.offer(intArrayOf(1, 1), HistogramSource.ISP, nowMs = 0)
        val empty = analysis(atMs = 10, bins = 0)
        assertSame(empty, hold.merge(empty))
    }
}
