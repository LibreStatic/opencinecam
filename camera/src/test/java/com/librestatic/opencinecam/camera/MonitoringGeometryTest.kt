/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera
import org.junit.Assert.*
import org.junit.Test
class MonitoringGeometryTest {
    @Test fun frameCarriesImmutableSampleConfigurationAcrossLaterEdits() {
        val options = MonitoringOptions(falseColorEnabled = true, falseColorClipPercent = 97)
        val frame = analyzeMonitoringRgb(1,1,byteArrayOf(0,0,0),options,MonitoringSignalDomain.SDR_BT709_CODE)
        assertEquals(options,frame.options)
        assertNotEquals(options.copy(falseColorClipPercent = 98),frame.options)
    }
    @Test fun sensorAndGpuRasterApplySensorRotationExactlyOnce() {
        val raw = MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR
        val gpu = MonitoringSignalDomain.SDR_BT709_CODE
        assertEquals(1f to 0f, monitoringDisplayPoint(0f, 0f, raw, 90, 0, false))
        assertEquals(0f to 0f, monitoringDisplayPoint(0f, 0f, gpu, 90, 0, false))
        assertEquals(0f to 0f, monitoringDisplayPoint(0f, 0f, raw, 90, 90, false))
        assertEquals(0f to 1f, monitoringDisplayPoint(0f, 0f, gpu, 90, 90, false))
    }
    @Test fun anamorphicOverlayFollowsExactGpuQuadNotFullSurface() {
        for (sensor in listOf(0,90,180,270)) for (display in listOf(0,90,180,270)) for (squeeze in listOf(1f,1.33f,2f)) {
            val expected = OpenCineLogPreviewGeometryCalculator.calculate(1920,1080,748,1000,sensor,display,false,squeeze)
            assertEquals(expected.scaleX to expected.scaleY, monitoringPreviewScale(1920,1080,748,1000,sensor,display,false,squeeze))
        }
        val scale = monitoringPreviewScale(1920,1080,748,1000,90,0,false,1.33f)
        assertEquals(.5654f,scale.first,.001f); assertEquals(1f,scale.second,0f)
    }
    @Test fun allQuarterTurnsKeepCornersBijectiveAndFrontMirrored() {
        for (domain in MonitoringSignalDomain.entries) for (sensor in listOf(0,90,180,270)) for (display in listOf(0,90,180,270)) {
            val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)
            for (front in listOf(false,true)) assertEquals(corners.toSet(), corners.map { (x,y) ->
                monitoringDisplayPoint(x,y,domain,sensor,display,front) }.toSet())
        }
        assertEquals(1f to 0f, monitoringDisplayPoint(0f,0f,MonitoringSignalDomain.OCLOG2_CODE,90,0,true))
    }
}
