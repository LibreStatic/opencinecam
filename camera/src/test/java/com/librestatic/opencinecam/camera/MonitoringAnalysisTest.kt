/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class MonitoringAnalysisTest {
    private val enabled = MonitoringOptions(waveformEnabled = true, vectorscopeEnabled = true, falseColorEnabled = true)
    private fun packed(vararg colors: Int): ByteArray = ByteArray(colors.size * 3) { i ->
        (colors[i / 3] shr (16 - (i % 3) * 8)).toByte()
    }
    private fun gray(value: Int) = value * 0x010101
    private fun analyze(width: Int, height: Int, bytes: ByteArray, options: MonitoringOptions = enabled) =
        analyzeMonitoringRgb(width, height, bytes, options, MonitoringSignalDomain.SDR_BT709_CODE)
    private fun single(color: Int, options: MonitoringOptions = enabled) = analyze(1, 1, packed(color), options)

    @Test fun dimensionsCountsAndDeclaredDomainRemainExact() {
        val bytes = ByteArray(320 * 180 * 3) { 127 }
        for (domain in MonitoringSignalDomain.entries) {
            val result = analyzeMonitoringRgb(320, 180, bytes, enabled, domain)
            assertEquals(domain, result.domain)
            assertEquals(320, result.sampledWidth); assertEquals(180, result.sampledHeight)
            assertEquals(57600, result.sampleCount)
            assertEquals(4096, result.waveformDensity.size); assertEquals(4096, result.vectorscopeCounts.size)
            assertEquals(2304, result.falseColorBands.size)
            assertEquals(57600, result.waveformDensity.sum()); assertEquals(57600, result.vectorscopeCounts.sum())
        }
    }
    @Test fun disabledScopesHaveNoArraysButRetainSampleGeometry() {
        val result = single(0xff0000, MonitoringOptions())
        assertTrue(result.waveformDensity.isEmpty()); assertTrue(result.vectorscopeCounts.isEmpty()); assertTrue(result.falseColorBands.isEmpty())
        assertEquals(1, result.sampleCount)
    }
    @Test fun enablingEachScopeDoesNotEnableItsNeighbors() {
        val waveform = single(0, MonitoringOptions(waveformEnabled = true))
        assertEquals(4096, waveform.waveformDensity.size); assertTrue(waveform.vectorscopeCounts.isEmpty()); assertTrue(waveform.falseColorBands.isEmpty())
        val vectors = single(0, MonitoringOptions(vectorscopeEnabled = true))
        assertTrue(vectors.waveformDensity.isEmpty()); assertEquals(4096, vectors.vectorscopeCounts.size); assertTrue(vectors.falseColorBands.isEmpty())
        val falseColor = single(0, MonitoringOptions(falseColorEnabled = true))
        assertTrue(falseColor.waveformDensity.isEmpty()); assertTrue(falseColor.vectorscopeCounts.isEmpty()); assertEquals(2304, falseColor.falseColorBands.size)
    }
    @Test fun everyNeutralCodeIsExactlyCenteredInVectorscope() {
        val values = IntArray(256) { gray(it) }
        val result = analyze(256, 1, packed(*values))
        assertEquals(256, result.vectorscopeCounts[32 * 64 + 32])
        assertEquals(1, result.vectorscopeCounts.count { it != 0 })
    }
    @Test fun bt709PrimaryAndSecondaryVectorsHaveCorrectAxesAndSaturatedEdgeBins() {
        val cases = listOf(0xff0000 to (24 to 0), 0x00ff00 to (7 to 61), 0x0000ff to (63 to 34),
            0x00ffff to (39 to 63), 0xff00ff to (56 to 2), 0xffff00 to (0 to 29))
        cases.forEach { (color, point) ->
            val counts = single(color).vectorscopeCounts
            assertEquals("color=$color point=$point", 1, counts[point.second * 64 + point.first])
            assertEquals(1, counts.sum())
        }
    }
    @Test fun waveformGradientPreservesHorizontalPositionAndAllSamples() {
        val result = analyze(256, 1, packed(*IntArray(256) { gray(it) }))
        for (column in 0..63) assertEquals(4, result.waveformDensity[(63 - column) * 64 + column])
        assertEquals(64, result.waveformDensity.count { it != 0 })
        assertEquals(256, result.waveformDensity.sum())
    }
    @Test fun waveformUsesRounded54_183_19CodeLumaAndHighValuesAtTop() {
        assertEquals(1, single(0).waveformDensity[63 * 64])
        assertEquals(1, single(0xffffff).waveformDensity[0])
        assertEquals(1, single(0xff0000).waveformDensity[50 * 64])
        assertEquals(1, single(0x00ff00).waveformDensity[18 * 64])
        assertEquals(1, single(0x0000ff).waveformDensity[59 * 64])
    }
    @Test fun waveformCountsVerticalSamplesWithoutInvertingInputRows() {
        val result = analyze(2, 2, packed(0, 0xffffff, 0, 0xffffff))
        assertEquals(2, result.waveformDensity[63 * 64])
        assertEquals(2, result.waveformDensity[32])
        assertEquals(FalseColorBand.BLACK, result.falseColorBands[0])
        assertEquals(FalseColorBand.CLIP, result.falseColorBands[63])
    }
    @Test fun falseColorUsesConfiguredBoundariesAndReplicatesSmallInputsOnlySpatially() {
        val options = enabled.copy(falseColorBlackPercent = 20, falseColorShadowPercent = 40,
            falseColorHighlightPercent = 60, falseColorClipPercent = 80)
        val expected = mapOf(0 to FalseColorBand.BLACK, 51 to FalseColorBand.BLACK, 52 to FalseColorBand.SHADOW,
            102 to FalseColorBand.SHADOW, 103 to FalseColorBand.MID, 152 to FalseColorBand.MID,
            153 to FalseColorBand.HIGHLIGHT, 203 to FalseColorBand.HIGHLIGHT, 204 to FalseColorBand.CLIP, 255 to FalseColorBand.CLIP)
        expected.forEach { (value, band) ->
            val result = single(gray(value), options)
            assertEquals(1, result.sampleCount); assertEquals(setOf(band), result.falseColorBands.toSet())
        }
    }
    @Test fun falseColorAveragesCellsBeforeClassificationRatherThanVoting() {
        val bytes = packed(*IntArray(128 * 36) { if (it % 2 == 0) 0 else 0xffffff })
        val result = analyze(128, 36, bytes)
        assertEquals(setOf(FalseColorBand.MID), result.falseColorBands.toSet())
    }
    @Test fun falseColorRetainsTopDownSpatialCellOrder() {
        val bytes = packed(*IntArray(64 * 36) { if (it / 64 < 18) 0 else 0xffffff })
        val result = analyze(64, 36, bytes)
        assertTrue(result.falseColorBands.take(64 * 18).all { it == FalseColorBand.BLACK })
        assertTrue(result.falseColorBands.drop(64 * 18).all { it == FalseColorBand.CLIP })
    }
    @Test fun invalidDimensionsOverflowAndMismatchedPackedBuffersAlwaysReject() {
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 2, Int.MAX_VALUE to Int.MAX_VALUE, 321 to 180)) {
            assertThrows(IllegalArgumentException::class.java) { analyze(width, height, byteArrayOf()) }
        }
        for (size in listOf(0, 1, 2, 4, 6)) {
            assertThrows(IllegalArgumentException::class.java) { analyze(1, 1, ByteArray(size), MonitoringOptions()) }
        }
    }
    @Test fun pixelBudgetDoesNotInventAnIndependentAspectRatioLimit() {
        val result = analyze(57600, 1, ByteArray(57600 * 3), MonitoringOptions())
        assertEquals(57600, result.sampleCount); assertEquals(57600, result.sampledWidth)
    }
    @Test fun computationNeverMutatesOrRetainsInputBytes() {
        val bytes = packed(0xff0000, 0x0000ff); val before = bytes.copyOf()
        val result = analyze(2, 1, bytes)
        assertArrayEquals(before, bytes)
        val vectors = result.vectorscopeCounts.toList(); bytes.fill(0)
        assertEquals(vectors, result.vectorscopeCounts)
    }
    @Test fun resultListsAreDefensiveAndUnmodifiable() {
        val counts = MutableList(4096) { if (it == 0) 1 else 0 }
        val bands = MutableList(2304) { FalseColorBand.MID }
        val result = MonitoringScopeFrame(counts, counts, bands, 1, MonitoringSignalDomain.SDR_BT709_CODE, 1, 1)
        counts[0] = 100; bands[0] = FalseColorBand.CLIP
        assertEquals(1, result.waveformDensity[0]); assertEquals(1, result.vectorscopeCounts[0]); assertEquals(FalseColorBand.MID, result.falseColorBands[0])
        assertThrows(UnsupportedOperationException::class.java) { (result.waveformDensity as MutableList<Int>)[0] = 2 }
        assertThrows(UnsupportedOperationException::class.java) { (result.vectorscopeCounts as MutableList<Int>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.falseColorBands as MutableList<FalseColorBand>)[0] = FalseColorBand.BLACK }
    }
    @Test fun resultConstructorRejectsMalformedShapeNegativeCountsAndWrongTotals() {
        val domain = MonitoringSignalDomain.SDR_BT709_CODE
        assertThrows(IllegalArgumentException::class.java) { MonitoringScopeFrame(listOf(1), emptyList(), emptyList(), 1, domain, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { MonitoringScopeFrame(List(4096) { 0 }, emptyList(), emptyList(), 1, domain, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { MonitoringScopeFrame(List(4096) { if (it == 0) -1 else 0 }, emptyList(), emptyList(), 1, domain, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { MonitoringScopeFrame(emptyList(), emptyList(), listOf(FalseColorBand.MID), 1, domain, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { MonitoringScopeFrame(emptyList(), emptyList(), emptyList(), 2, domain, 1, 1) }
    }
    @Test fun freshnessRejectsFutureMissingExpiredAndOverflowLikeTimestamps() {
        val normal = MonitoringOptions()
        assertFalse(monitoringSampleFresh(0, 1, normal)); assertFalse(monitoringSampleFresh(101, 100, normal))
        assertTrue(monitoringSampleFresh(100, 1100, normal)); assertFalse(monitoringSampleFresh(100, 1101, normal))
        assertFalse(monitoringSampleFresh(1, Long.MAX_VALUE, normal))
        assertFalse(monitoringSampleFresh(Long.MAX_VALUE, Long.MIN_VALUE, normal))
        val slow = normal.copy(refreshHz = 1)
        assertTrue(monitoringSampleFresh(100, 3100, slow)); assertFalse(monitoringSampleFresh(100, 3101, slow))
    }
}
