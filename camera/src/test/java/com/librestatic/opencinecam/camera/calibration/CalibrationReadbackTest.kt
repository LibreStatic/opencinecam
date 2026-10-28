/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationReadbackTest {
    private fun frame() = RgbaFrame(2, 2, ByteArray(16))
    private fun meta(ts: Long, id: String = "0") = CalibrationFrameMeta(ts, id, 2, 2, 1f, 10_000_000L, 100, null)

    @Test
    fun joinsWhicheverSideArrivesFirst() {
        val out = mutableListOf<CalibrationSample>()
        val joiner = CalibrationFrameJoiner { out += it }
        joiner.offerMeta(meta(100, "a"))
        joiner.offerFrame(100, frame())
        joiner.offerFrame(200, frame())
        joiner.offerMeta(meta(200, "b"))
        assertEquals(listOf("a", "b"), out.map { it.physicalId })
        assertEquals(listOf(100L, 200L), out.map { it.timestampNs })
    }

    @Test
    fun matchesWithinTheToleranceAndNotBeyond() {
        val out = mutableListOf<CalibrationSample>()
        val joiner = CalibrationFrameJoiner(toleranceNs = 1_000) { out += it }
        joiner.offerMeta(meta(10_000))
        joiner.offerFrame(10_900, frame())
        assertEquals(1, out.size)
        joiner.offerMeta(meta(50_000))
        joiner.offerFrame(60_000, frame())
        assertEquals(1, out.size)
    }

    @Test
    fun aMetadataEntryIsUsedOnce() {
        val out = mutableListOf<CalibrationSample>()
        val joiner = CalibrationFrameJoiner { out += it }
        joiner.offerMeta(meta(100))
        joiner.offerFrame(100, frame())
        joiner.offerFrame(100, frame())
        assertEquals(1, out.size)
    }

    @Test
    fun unmatchedEntriesAreDroppedOldestFirst() {
        val out = mutableListOf<CalibrationSample>()
        val joiner = CalibrationFrameJoiner(capacity = 3) { out += it }
        for (i in 1..5) joiner.offerMeta(meta(i * 10_000_000L))
        joiner.offerFrame(10_000_000L, frame()) // evicted
        assertTrue(out.isEmpty())
        joiner.offerFrame(50_000_000L, frame())
        assertEquals(1, out.size)
    }

    @Test
    fun theReadbackProgramIsTheBareSignalAndSharesTheTierYcbcrExpression() {
        val plain = OpenCineLogGpuPipeline.calibrationShader(shaderYcbcr = false)
        assertTrue(plain.contains("outColor = vec4(texture(uTexture, vTexCoord).rgb, 1.0);"))
        for (forbidden in listOf("uSceneGain", "uOutputMode", "uLens", "uOperatorLut")) assertFalse(forbidden, plain.contains(forbidden))

        val ycbcr = OpenCineLogGpuPipeline.calibrationShader(shaderYcbcr = true)
        val expression = "clamp(uYcbcrToRgb * (texture(uTexture, vTexCoord).rgb - uYcbcrOffset), 0.0, 1.0)"
        assertTrue(ycbcr.contains(expression))
        assertTrue(ycbcr.contains("uniform __samplerExternal2DY2YEXT uTexture;"))
        for (path in OpenCineLogSourcePath.entries) {
            assertTrue(OpenCineLogGpuPipeline.transformShader(path, shaderYcbcr = true).contains(expression))
        }
    }
}
