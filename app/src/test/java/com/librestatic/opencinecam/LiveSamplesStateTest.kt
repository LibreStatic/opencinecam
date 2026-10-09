/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.AudioChannelLevel
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSamplesStateTest {
    private val base = CameraUiState(sensitivityIso = 400)

    private fun live(n: Int) = base.copy(
        effectiveFps = 23.97 + n,
        analysisIntervalMs = 250L + n,
        analysisUpdatedAtMs = 1000L + n,
        histogram = listOf(.1f, .2f * n),
        redHistogram = listOf(.3f),
        greenHistogram = listOf(.4f),
        blueHistogram = listOf(.5f),
        zebraCells = listOf(true, n > 1),
        audioLevels = AudioLevelSnapshot(listOf(AudioChannelLevel(-12f, -15f)), false, 1000L + n),
    )

    @Test
    fun resetsLiveFieldsAndKeepsTheRest() {
        val stripped = live(1).withoutLiveSamples()
        assertNull(stripped.effectiveFps)
        assertEquals(0L, stripped.analysisIntervalMs)
        assertEquals(0L, stripped.analysisUpdatedAtMs)
        assertNull(stripped.monitoringScopes)
        assertTrue(stripped.histogram.isEmpty() && stripped.redHistogram.isEmpty())
        assertTrue(stripped.greenHistogram.isEmpty() && stripped.blueHistogram.isEmpty())
        assertTrue(stripped.zebraCells.isEmpty())
        assertNull(stripped.focusPeakingMask)
        assertNull(stripped.audioLevels)
        assertEquals(400, stripped.sensitivityIso)
    }

    @Test
    fun statesDifferingOnlyInLiveFieldsAreEqualOnceStripped() {
        assertNotEquals(live(1), live(2))
        assertEquals(live(1).withoutLiveSamples(), live(2).withoutLiveSamples())
        assertNotEquals(live(1).withoutLiveSamples(), live(1).copy(sensitivityIso = 800).withoutLiveSamples())
    }
}
