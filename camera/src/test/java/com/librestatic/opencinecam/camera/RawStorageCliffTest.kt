/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawStorageCliffTest {
    @Test
    fun razrSeriesFindsTheCliffAfterFiftySixWindows() {
        val samples = rates(List(56) { BURST } + post(20))
        val found = RawStorageCliff.detect(samples)
        assertTrue(found.cliffDetected)
        assertEquals(56, found.cliffWindow)
        assertEquals(56 * BURST, found.cacheBytes)
        assertNull(found.cacheBytesAtLeast)
        assertEquals(BURST, found.burstBytesPerSecond)
        assertEquals(20, found.windowsAfterCliff)
        assertEquals(76, found.windows)
        val sorted = post(20).sorted()
        assertEquals((sorted[9] + sorted[10]) / 2, found.sustainedBytesPerSecond)
    }

    @Test
    fun steadyStorageHasNoCliff() {
        val samples = rates(List(150) { if (it % 3 == 0) BURST - 50_000_000L else BURST })
        val found = RawStorageCliff.detect(samples)
        assertFalse(found.cliffDetected)
        assertNull(found.cliffWindow)
        assertNull(found.cacheBytes)
        assertEquals(samples.sumOf { it.bytesWritten }, found.cacheBytesAtLeast)
        assertEquals(found.burstBytesPerSecond, found.sustainedBytesPerSecond)
    }

    @Test
    fun aCliffLateInTheExtraPhaseIsFound() {
        val samples = rates(List(120) { BURST } + post(15))
        val found = RawStorageCliff.detect(samples)
        assertTrue(found.cliffDetected)
        assertEquals(120, found.cliffWindow)
        assertEquals(120 * BURST, found.cacheBytes)
        assertEquals(15, found.windowsAfterCliff)
    }

    @Test
    fun singleSlowWindowsAreNotACliff() {
        val values = MutableList(80) { BURST }
        values[30] = 200_000_000L
        values[70] = 200_000_000L
        values[79] = 200_000_000L
        assertFalse(RawStorageCliff.detect(rates(values)).cliffDetected)
    }

    @Test
    fun aRecoveryAfterADipIsNotACliff() {
        val values = List(60) { BURST } + post(8) + List(10) { BURST }
        assertFalse(RawStorageCliff.detect(rates(values)).cliffDetected)
    }

    @Test
    fun aRollingMedianStartingOnFastWindowsMovesToTheFirstSlowOne() {
        // The window at 60 is the first under the line; the median from 58 is already under it.
        val values = List(60) { BURST } + List(20) { 400_000_000L }
        val found = RawStorageCliff.detect(rates(values))
        assertEquals(60, found.cliffWindow)
    }

    @Test
    fun tooFewWindowsAfterTheCliffLeaveTheSustainedRateUnknown() {
        val found = RawStorageCliff.detect(rates(List(56) { BURST } + post(9)))
        assertTrue(found.cliffDetected)
        assertEquals(9, found.windowsAfterCliff)
        assertNull(found.sustainedBytesPerSecond)
        assertNull(RawStorageCliff.burstSeconds(found, FULL60, RING))
    }

    @Test
    fun theSweepStopsOnceTheTailIsMeasured() {
        assertNull(RawStorageCliff.sweepStop(rates(List(59) { BURST }), 60))
        // Razr: the cliff at 56 inside the burst windows, so only 15 windows past it are needed.
        assertNull(RawStorageCliff.sweepStop(rates(List(56) { BURST } + post(14)), 60))
        assertEquals(RawStorageCliff.STOP_TAIL_COMPLETE, RawStorageCliff.sweepStop(rates(List(56) { BURST } + post(15)), 60))
        // No cliff: it gives up 90 s past the burst windows (here with no byte cap, which would stop it first).
        assertNull(RawStorageCliff.sweepStop(rates(List(149) { BURST }), 60, maxTotalBytes = Long.MAX_VALUE))
        assertEquals(RawStorageCliff.STOP_MAX_DURATION, RawStorageCliff.sweepStop(rates(List(150) { BURST }), 60, maxTotalBytes = Long.MAX_VALUE))
        assertEquals(RawStorageCliff.STOP_MAX_DURATION, RawStorageCliff.sweepStop(rates(List(150) { 400_000_000L }), 60))
        assertEquals(RawStorageCliff.STOP_DISABLED, RawStorageCliff.sweepStop(rates(List(60) { BURST }), 60, maxExtraSeconds = 0))
    }

    @Test
    fun theSweepStopsAtNinetySixGigabytesWithoutACliff() {
        assertEquals(96_000_000_000L, RawStorageCliff.SUSTAINED_MAX_TOTAL_BYTES)
        // 1.2 GB/s with no cliff: 79 windows are 94.8 GB, the 80th reaches 96 GB.
        assertNull(RawStorageCliff.sweepStop(rates(List(79) { BURST }), 60))
        assertEquals(RawStorageCliff.STOP_MAX_BYTES, RawStorageCliff.sweepStop(rates(List(80) { BURST }), 60))
        // Never inside the burst windows, however much they wrote.
        assertNull(RawStorageCliff.sweepStop(rates(List(59) { 2 * BURST }), 60))
        assertEquals(RawStorageCliff.STOP_MAX_BYTES, RawStorageCliff.sweepStop(rates(List(60) { 2 * BURST }), 60))
        // A tail measured first still says so.
        assertEquals(RawStorageCliff.STOP_TAIL_COMPLETE, RawStorageCliff.sweepStop(rates(List(56) { BURST } + post(15)), 60, maxTotalBytes = 1L))
        // What the capped sweep found: no cliff, an unknown sustained rate, a cache of at least what it wrote.
        val found = RawStorageCliff.detect(rates(List(80) { BURST }), 60)
        assertFalse(found.cliffDetected)
        assertEquals(96_000_000_000L, found.cacheBytesAtLeast)
        assertNull(RawStorageCliff.burstSeconds(found, FULL60, RING))
    }

    @Test
    fun burstSecondsIsNullWhenTheSustainedRateKeepsUp() {
        val found = cliff(cacheBytes = 66_000_000_000L, sustained = 500_000_000L)
        assertNull(RawStorageCliff.burstSeconds(found, 450_000_000L, RING))
        assertNull(RawStorageCliff.burstSeconds(found, 500_000_000L, RING))
        assertNull(RawStorageCliff.burstSeconds(RawStorageCliff.detect(rates(List(100) { BURST })), FULL60, RING))
        assertNull(RawStorageCliff.burstSeconds(null, FULL60, RING))
    }

    @Test
    fun razrFullSixtyLastsAboutSeventyFiveSeconds() {
        val found = cliff(cacheBytes = 66_000_000_000L, sustained = 500_000_000L)
        // 66e9 / 936.36e6 = 70.5 s of cache, then 2 GiB / (936.36e6 - 500e6) = 4.9 s of ring.
        assertEquals(75L, RawStorageCliff.burstSeconds(found, FULL60, RING))
    }

    @Test
    fun lj92UsesTheCompressedRateAndTheRingInCompressedBytes() {
        val found = cliff(cacheBytes = 66_000_000_000L, sustained = 500_000_000L)
        assertEquals(624_240_000L, RawStorageCliff.compressedBytesPerSecond(FULL60, 1.5))
        // 66e9 / 624.24e6 = 105.7 s, then (2 GiB / 1.5) / (624.24e6 - 500e6) = 11.5 s.
        assertEquals(117L, RawStorageCliff.burstSeconds(found, FULL60, RING, 1.5))
        // At 2.0 the compressed stream (468 MB/s) is under the sustained rate.
        assertNull(RawStorageCliff.burstSeconds(found, FULL60, RING, 2.0))
    }

    @Test
    fun withoutACliffTheSlowestRunThatFellBehindGivesTheSustainedRate() {
        // The Razr: the sweep stopped at 96 GB with no cliff.
        val capped = RawStorageCliff.detect(rates(List(91) { 1_050_000_000L }), 60)
        assertFalse(capped.sustainedKnown)
        val evidence = listOf(
            RawWriteEvidence("3840x2160@60", 578_000_000L, fellBehind = true),
            RawWriteEvidence("full@60", 733_000_000L, fellBehind = true),
            RawWriteEvidence("full@24", 400_000_000L, fellBehind = false),
        )
        val derived = RawStorageCliff.withLongRunFallback(capped, evidence)!!
        assertTrue(derived.sustainedKnown)
        assertFalse(derived.cliffDetected)
        assertEquals(RawStorageCliff.SOURCE_LONG_RUNS, derived.source)
        // The minimum of the runs that fell behind; the one that kept up is ignored, though slower.
        assertEquals(578_000_000L, derived.sustainedBytesPerSecond)
        assertEquals(listOf("3840x2160@60", "full@60"), derived.derivedFrom)
        assertEquals(capped.cacheBytesAtLeast, derived.cacheBytes)
        // burstSeconds is now known: 95.55 GB / 936.36 MB/s + 2 GiB / (936.36 - 578) MB/s.
        val expected = kotlin.math.floor(capped.cacheBytesAtLeast!!.toDouble() / FULL60 + RING.toDouble() / (FULL60 - 578_000_000L)).toLong()
        assertEquals(expected, RawStorageCliff.burstSeconds(derived, FULL60, RING))
        assertNull(RawStorageCliff.burstSeconds(capped, FULL60, RING))
    }

    @Test
    fun theLongRunFallbackChangesNothingWithACliffOrNoRunBehind() {
        val behind = listOf(RawWriteEvidence("full@60", 300_000_000L, fellBehind = true))
        val real = RawStorageCliff.detect(rates(List(56) { BURST } + post(20)))
        assertEquals(real, RawStorageCliff.withLongRunFallback(real, behind))
        val capped = RawStorageCliff.detect(rates(List(80) { BURST }), 60)
        assertEquals(capped, RawStorageCliff.withLongRunFallback(capped, listOf(RawWriteEvidence("full@24", 300_000_000L, fellBehind = false))))
        assertEquals(capped, RawStorageCliff.withLongRunFallback(capped, emptyList()))
        assertEquals(capped, RawStorageCliff.withLongRunFallback(capped, listOf(RawWriteEvidence("full@60", 0L, fellBehind = true))))
        assertNull(RawStorageCliff.withLongRunFallback(null, behind))
    }

    @Test
    fun aRunThatReachedSevereIsNotStorageEvidence() {
        val capped = RawStorageCliff.detect(rates(List(80) { BURST }), 60)
        val evidence = listOf(
            RawWriteEvidence("full@60", 615_000_000L, fellBehind = true, thermalPeak = 2, storageBytesPerSecond = 900_000_000L),
            RawWriteEvidence("3840x2160@60", 354_000_000L, fellBehind = true, thermalPeak = 3, storageBytesPerSecond = 622_080_000L),
        )
        val derived = RawStorageCliff.withLongRunFallback(capped, evidence)!!
        assertEquals(615_000_000L, derived.sustainedBytesPerSecond)
        assertEquals(listOf("full@60"), derived.derivedFrom)
        assertNull(derived.floorBytesPerSecond)
        assertNull(derived.floorFrom)
    }

    @Test
    fun theRazrRunKeepsTheFallbackAtTheCoolRunThatFellBehind() {
        // full-qual2: only full@60 counts; full@30 kept up at 450 MB/s after it, under its 615 MB/s.
        val capped = RawStorageCliff.detect(rates(List(80) { BURST }), 60)
        val evidence = listOf(
            RawWriteEvidence("full@60", 615_365_218L, fellBehind = true, thermalPeak = 2, storageBytesPerSecond = 900_000_000L),
            RawWriteEvidence("3840x2160@60", 353_796_597L, fellBehind = true, thermalPeak = 3, storageBytesPerSecond = 622_080_000L),
            RawWriteEvidence("full@30", 762_638_571L, fellBehind = false, thermalPeak = 3, storageBytesPerSecond = 450_000_000L),
            RawWriteEvidence("full@24", 640_232_205L, fellBehind = false, thermalPeak = 3, storageBytesPerSecond = 360_000_000L),
            RawWriteEvidence("3840x2160@30", 783_140_808L, fellBehind = false, thermalPeak = 3, storageBytesPerSecond = 311_040_000L),
            RawWriteEvidence("full@15", 918_198_905L, fellBehind = false, thermalPeak = 2, storageBytesPerSecond = 225_000_000L),
        )
        val derived = RawStorageCliff.withLongRunFallback(capped, evidence)!!
        assertEquals(615_365_218L, derived.sustainedBytesPerSecond)
        assertEquals(listOf("full@60"), derived.derivedFrom)
        assertEquals(450_000_000L, derived.floorBytesPerSecond)
        assertEquals("full@30", derived.floorFrom)
    }

    @Test
    fun aLaterRunThatKeptUpRaisesTheRate() {
        val capped = RawStorageCliff.detect(rates(List(80) { BURST }), 60)
        val evidence = listOf(
            RawWriteEvidence("full@60", 400_000_000L, fellBehind = true, thermalPeak = 1, storageBytesPerSecond = 900_000_000L),
            RawWriteEvidence("full@30", 700_000_000L, fellBehind = false, thermalPeak = 1, storageBytesPerSecond = 450_000_000L),
            RawWriteEvidence("full@24", 600_000_000L, fellBehind = false, thermalPeak = 1, storageBytesPerSecond = 360_000_000L),
        )
        val derived = RawStorageCliff.withLongRunFallback(capped, evidence)!!
        assertEquals(450_000_000L, derived.sustainedBytesPerSecond)
        assertEquals(listOf("full@60"), derived.derivedFrom)
        assertEquals(450_000_000L, derived.floorBytesPerSecond)
        assertEquals("full@30", derived.floorFrom)
    }

    @Test
    fun theFloorOnlyComesFromRunsAfterTheFirstCountedOneThatFellBehind() {
        val capped = RawStorageCliff.detect(rates(List(80) { BURST }), 60)
        val evidence = listOf(
            // Before the first counted run (the sweep's cache may still have helped it): no floor.
            RawWriteEvidence("full@30", 700_000_000L, fellBehind = false, thermalPeak = 1, storageBytesPerSecond = 450_000_000L),
            // A severe run that fell behind does not start the floor either.
            RawWriteEvidence("3840x2160@60", 300_000_000L, fellBehind = true, thermalPeak = 3, storageBytesPerSecond = 622_080_000L),
            RawWriteEvidence("full@24", 600_000_000L, fellBehind = false, thermalPeak = 1, storageBytesPerSecond = 360_000_000L),
            RawWriteEvidence("full@60", 400_000_000L, fellBehind = true, thermalPeak = 1, storageBytesPerSecond = 900_000_000L),
            RawWriteEvidence("full@15", 800_000_000L, fellBehind = false, thermalPeak = 1, storageBytesPerSecond = 225_000_000L),
        )
        val derived = RawStorageCliff.withLongRunFallback(capped, evidence)!!
        assertEquals(400_000_000L, derived.sustainedBytesPerSecond)
        assertEquals(225_000_000L, derived.floorBytesPerSecond)
        assertEquals("full@15", derived.floorFrom)
    }

    @Test
    fun noFallbackWhenEveryRunThatFellBehindReachedSevere() {
        val capped = RawStorageCliff.detect(rates(List(80) { BURST }), 60)
        val evidence = listOf(
            RawWriteEvidence("full@60", 615_000_000L, fellBehind = true, thermalPeak = 3, storageBytesPerSecond = 900_000_000L),
            RawWriteEvidence("3840x2160@60", 354_000_000L, fellBehind = true, thermalPeak = 4, storageBytesPerSecond = 622_080_000L),
            RawWriteEvidence("full@30", 762_000_000L, fellBehind = false, thermalPeak = 1, storageBytesPerSecond = 450_000_000L),
        )
        assertEquals(capped, RawStorageCliff.withLongRunFallback(capped, evidence))
    }

    @Test
    fun storageSlowerThanTheStreamLastsOnlyAsLongAsTheRing() {
        // A hard disk on USB: no cliff, 100 MB/s from the first window, against a 250 MB/s stream and a 600 MB ring.
        val steady = RawStorageCliff.detect(rates(List(150) { 100_000_000L }))
        assertFalse(steady.sustainedKnown)
        assertEquals(4L, RawStorageCliff.burstSeconds(steady, 250_000_000L, 600_000_000L))
        // Compressed 1.8x: the S24's take to the WD Elements, 450 MB/s raw, a 1061 MB ring, 79 MB/s. It lasted 3.9 s.
        val wd = RawStorageCliff.detect(rates(List(150) { 79_000_000L }))
        assertEquals(3L, RawStorageCliff.burstSeconds(wd, 450_000_000L, 1_061_208_000L, 1.8))
        // The long runs' "cache" is what the sweep wrote, which never absorbed the stream: the ring still fills at once.
        val derived = RawSustainedWrite(false, null, 12_000_000_000L, null, 100_000_000L, 60_000_000L, 150, 0, RawStorageCliff.SOURCE_LONG_RUNS)
        assertEquals(4L, RawStorageCliff.burstSeconds(derived, 250_000_000L, 600_000_000L))
        // A cache that ends first: 2 s at 100 MB/s fill 300 MB, the other 300 MB fill at 250 - 50 MB/s in 1.5 s.
        val short = derived.copy(cacheBytes = 200_000_000L, sustainedBytesPerSecond = 50_000_000L)
        assertEquals(3L, RawStorageCliff.burstSeconds(short, 250_000_000L, 600_000_000L))
        // A cliff whose tail was not measured stays unknown, and storage that keeps up has no burst.
        val unmeasured = RawSustainedWrite(true, 56, 5_600_000_000L, null, 100_000_000L, null, 65, 9)
        assertNull(RawStorageCliff.burstSeconds(unmeasured, 250_000_000L, 600_000_000L))
        assertNull(RawStorageCliff.burstSeconds(steady, 100_000_000L, 600_000_000L))
    }

    private fun cliff(cacheBytes: Long, sustained: Long) =
        RawSustainedWrite(true, 56, cacheBytes, null, BURST, sustained, 76, 20)

    private fun post(count: Int): List<Long> {
        val cycle = listOf(270L, 880L, 450L, 600L, 320L, 700L, 500L, 380L).map { it * 1_000_000L }
        return List(count) { cycle[it % cycle.size] }
    }

    private fun rates(bytesPerSecond: List<Long>) = bytesPerSecond.map { RawThroughputSample(1000, it, 0) }

    private companion object {
        const val BURST = 1_200_000_000L
        const val FULL60 = 936_360_000L
        const val RING = 2L shl 30
    }
}
