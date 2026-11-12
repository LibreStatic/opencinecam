/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawStorageRestTest {
    @Test
    fun theRazrsRestedSweepIsRested() {
        // Around the rested sweep's 1.16 GB/s median, its slowest window near its 0.996 GB/s p01.
        val result = RawStorageRest.judge(rates(rested()), null)
        assertTrue(result.rested)
        assertNull(result.rule)
        assertTrue(result.worstSpread!! < 0.1)
        assertNull(result.referenceBytesPerSecond)
        // Against its own median as the reference it stays rested.
        assertTrue(RawStorageRest.judge(rates(rested()), 1_161_875_296L).rested)
    }

    @Test
    fun theRazrsUnrestedSweepIsErratic() {
        val result = RawStorageRest.judge(rates(UNRESTED_MB.map { it * MB }), null)
        assertFalse(result.rested)
        assertEquals(RawStorageRest.RULE_ERRATIC, result.rule)
        assertEquals(60, UNRESTED_MB.size)
        // Median and p01 of the sixty 1 s windows: 225 MB/s, 15 MB/s (the device's 198 MB/s came from its real window lengths).
        assertEquals(225 * MB, result.medianBytesPerSecond)
        assertEquals(15 * MB, result.p01BytesPerSecond)
        assertTrue(result.worstSpread!! > RawStorageRest.ERRATIC_SPREAD)
        // With the rested reference it is still the erratic rule that fires first.
        assertEquals(RawStorageRest.RULE_ERRATIC, RawStorageRest.judge(rates(UNRESTED_MB.map { it * MB }), 1_161_875_296L).rule)
    }

    @Test
    fun aCliffInsideTheBurstWindowsIsAStepNotNoise() {
        // 20 windows at 1.2 GB/s, then 40 at 400 MB/s: the median is the slow side's, so no cliff, and the spread is 0.
        val step = RawStorageRest.judge(rates(List(20) { 1_200 * MB } + List(40) { 400 * MB }), null)
        assertTrue(step.rested)
        assertNull(step.rule)
        // 50 windows at 1.2 GB/s, then 10 at 200 MB/s: judged on each side of the cliff, the spread is 0.
        val late = RawStorageRest.judge(rates(List(50) { 1_200 * MB } + List(10) { 200 * MB }), null)
        assertTrue(late.rested)
        assertEquals(50, late.cliffWindow)
        assertEquals(0.0, late.worstSpread!!, 1e-9)
        // A cliff with noise on its slow side is still erratic there.
        val noisy = RawStorageRest.judge(rates(List(20) { 1_200 * MB } + List(40) { listOf(15L, 300, 120, 420, 90)[it % 5] * MB }), null)
        assertFalse(noisy.rested)
        assertEquals(RawStorageRest.RULE_ERRATIC, noisy.rule)
    }

    @Test
    fun oneSlowWindowInARestedSweepIsNotErratic() {
        // The rested Razr sweep of 2026-11-04: 1.19 GB/s median, one window at 217 MB/s (its p01 / median was 0.18).
        val windows = List(60) { i -> if (i == 23) 217 * MB else listOf(1_150L, 1_190, 1_210, 1_060)[i % 4] * MB }
        val result = RawStorageRest.judge(rates(windows), null)
        assertTrue(result.rested)
        assertTrue(result.worstSpread!! < 0.05)
    }

    @Test
    fun aSteadyButSlowSweepIsBelowTheReference() {
        // Steady at 300 MB/s: not erratic, but under 0.6x a 1.16 GB/s reference.
        val slow = rates(List(60) { if (it % 2 == 0) 290 * MB else 310 * MB })
        val result = RawStorageRest.judge(slow, 1_161_875_296L)
        assertFalse(result.rested)
        assertEquals(RawStorageRest.RULE_BELOW_REFERENCE, result.rule)
        assertEquals(1_161_875_296L, result.referenceBytesPerSecond)
        // Just above 0.6x the reference it passes.
        assertTrue(RawStorageRest.judge(rates(List(60) { 700 * MB }), 1_161_875_296L).rested)
    }

    @Test
    fun withoutAReferenceOnlyTheErraticRuleApplies() {
        val slow = rates(List(60) { 300 * MB })
        assertTrue(RawStorageRest.judge(slow, null).rested)
        assertTrue(RawStorageRest.judge(slow, 0L).rested)
        assertNull(RawStorageRest.judge(slow, 0L).referenceBytesPerSecond)
        assertTrue(RawStorageRest.judge(emptyList(), 1_000 * MB).rested)
    }

    @Test
    fun onlyARestedSweepRaisesTheReference() {
        val rested = RawStorageRest.judge(rates(rested()), null)
        assertEquals(rested.medianBytesPerSecond, RawStorageRest.nextReference(null, rested))
        assertEquals(1_300 * MB, RawStorageRest.nextReference(1_300 * MB, rested))
        val unrested = RawStorageRest.judge(rates(UNRESTED_MB.map { it * MB }), null)
        assertNull(RawStorageRest.nextReference(null, unrested))
        assertEquals(1_161_875_296L, RawStorageRest.nextReference(1_161_875_296L, unrested))
    }

    /** Sixty windows around 1.16 GB/s, the slowest at 0.996 GB/s. */
    private fun rested(): List<Long> = List(60) { i ->
        when {
            i == 17 -> 996_042_850L
            i % 4 == 0 -> 1_200_008_640L
            i % 4 == 1 -> 1_155_008_316L
            i % 4 == 2 -> 1_170_008_424L
            else -> 1_035_007_452L
        }
    }

    private fun rates(bytesPerSecond: List<Long>) = bytesPerSecond.map { RawThroughputSample(1000, it, 0) }

    private companion object {
        const val MB = 1_000_000L

        /** The Razr's unrested sweep, MB per 1 s window, exactly as measured. */
        val UNRESTED_MB = listOf(
            300L, 300, 120, 60, 165, 90, 75, 105, 135, 15, 120, 240, 225, 420, 105, 675, 195, 435, 105, 150,
            15, 150, 240, 210, 135, 255, 420, 120, 90, 150, 150, 345, 105, 465, 675, 465, 225, 270, 285, 285,
            270, 270, 360, 285, 270, 315, 345, 285, 285, 300, 285, 285, 90, 225, 270, 180, 90, 120, 105, 90,
        )
    }
}
