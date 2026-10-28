/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.MonitoringOptions
import com.librestatic.opencinecam.camera.PREVIEW_GRAPH_ANALYSIS
import com.librestatic.opencinecam.camera.PREVIEW_GRAPH_PREVIEW
import com.librestatic.opencinecam.camera.PREVIEW_GRAPH_RAW
import com.librestatic.opencinecam.camera.PREVIEW_GRAPH_STILL
import com.librestatic.opencinecam.camera.PreviewGraphLoss
import com.librestatic.opencinecam.camera.StillPhotoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphFallbackUiTest {
    private val noMonitors = CameraSettings(zebraEnabled = false, peakingEnabled = false, histogramEnabled = false)
    private val both = PreviewGraphLoss(analysis = true, raw = true)

    @Test fun rawNoticeOnlyWhenTheModeWantsADng() {
        assertEquals(R.string.graph_fallback_raw, graphFallbackNoticeText(CaptureMode.RAW_PHOTO, noMonitors, both))
        assertEquals(R.string.graph_fallback_raw, graphFallbackNoticeText(CaptureMode.PHOTO, noMonitors.copy(photoFormat = StillPhotoFormat.RAW_JPEG), both))
        assertNull(graphFallbackNoticeText(CaptureMode.PHOTO, noMonitors.copy(photoFormat = StillPhotoFormat.JPEG), both))
        assertNull(graphFallbackNoticeText(CaptureMode.PHOTO, noMonitors.copy(photoFormat = StillPhotoFormat.HEIC), both))
        assertNull(graphFallbackNoticeText(CaptureMode.VIDEO, noMonitors.copy(photoFormat = StillPhotoFormat.RAW_JPEG), both))
    }

    @Test fun scopesNoticeOnlyWhenAnAnalysisMonitorIsOn() {
        val analysis = PreviewGraphLoss(analysis = true)
        assertNull(graphFallbackNoticeText(CaptureMode.VIDEO, noMonitors, analysis))
        listOf(
            noMonitors.copy(zebraEnabled = true),
            noMonitors.copy(peakingEnabled = true),
            noMonitors.copy(histogramEnabled = true),
            noMonitors.copy(monitoring = MonitoringOptions(waveformEnabled = true)),
            noMonitors.copy(monitoring = MonitoringOptions(vectorscopeEnabled = true)),
            noMonitors.copy(monitoring = MonitoringOptions(falseColorEnabled = true)),
        ).forEach { settings ->
            assertTrue(analysisMonitorsWanted(settings))
            assertEquals(R.string.graph_fallback_scopes, graphFallbackNoticeText(CaptureMode.VIDEO, settings, analysis))
        }
        assertFalse(analysisMonitorsWanted(noMonitors))
    }

    @Test fun bothLossesCombineIntoOneNotice() {
        assertEquals(R.string.graph_fallback_raw_scopes, graphFallbackNoticeText(CaptureMode.RAW_PHOTO, noMonitors.copy(zebraEnabled = true), both))
        assertEquals(R.string.graph_fallback_scopes, graphFallbackNoticeText(CaptureMode.RAW_PHOTO, noMonitors.copy(zebraEnabled = true), PreviewGraphLoss(analysis = true)))
    }

    @Test fun aLostPhysicalStillSizeHasNoNotice() {
        assertNull(graphFallbackNoticeText(CaptureMode.PHOTO, CameraSettings(), PreviewGraphLoss(physicalStill = true)))
    }

    @Test fun aFullGraphSessionClearsTheNotice() {
        val settings = noMonitors.copy(zebraEnabled = true)
        assertEquals(R.string.graph_fallback_raw_scopes, graphFallbackNoticeText(CaptureMode.RAW_PHOTO, settings, both))
        // The next regular session reports what it kept; a full graph reports no loss, and every opening resets to NONE.
        val full = listOf(PREVIEW_GRAPH_PREVIEW, PREVIEW_GRAPH_STILL, PREVIEW_GRAPH_RAW, PREVIEW_GRAPH_ANALYSIS)
        assertNull(graphFallbackNoticeText(CaptureMode.RAW_PHOTO, settings, PreviewGraphLoss.of(full, full, physicalStillFallback = false)))
        assertNull(graphFallbackNoticeText(CaptureMode.RAW_PHOTO, settings, CameraUiState().previewGraphLoss))
    }

    @Test fun theShutterNamesTheLostRawOnlyForFormatsThatNeedIt() {
        val raw = PreviewGraphLoss(raw = true)
        assertTrue(stillFormatLostRaw(StillPhotoFormat.DNG, raw))
        assertTrue(stillFormatLostRaw(StillPhotoFormat.RAW_JPEG, raw))
        assertFalse(stillFormatLostRaw(StillPhotoFormat.JPEG, raw))
        assertFalse(stillFormatLostRaw(StillPhotoFormat.HEIC, raw))
        assertFalse(stillFormatLostRaw(StillPhotoFormat.DNG, PreviewGraphLoss(analysis = true)))
    }
}
