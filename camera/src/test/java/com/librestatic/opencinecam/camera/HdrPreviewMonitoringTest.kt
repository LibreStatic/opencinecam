/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HdrPreviewMonitoringTest {
    private val candidate = HlgDecisionTable.evaluate(
        HlgCapabilityReport(34, HlgCapabilityState.SUPPORTED, HlgCapabilityState.SUPPORTED,
            HlgCapabilityState.SUPPORTED, "evidence"),
    )

    @Test
    fun configuresHlgEncoderMetadataAndRecordsPreviewProvenance() {
        val configured = HlgSessionConfigurator.configure(candidate, HdrDisplayCapability.SUPPORTED)
        assertEquals(HlgSessionStatus.READY, configured.status)
        assertEquals(HdrPreviewMode.HDR, configured.previewMode)
        assertEquals("video/hevc", configured.encoder?.mime)
        assertEquals("BT.2020", configured.encoder?.colorStandard)
        assertNotNull(configured.provenance)
        assertNull(configured.warningCode)
    }

    @Test
    fun displayFallbackIsVisibleAndDoesNotChangeRecordingGraph() {
        val sdr = HlgSessionConfigurator.configure(candidate, HdrDisplayCapability.UNSUPPORTED)
        assertEquals(HlgSessionStatus.READY, sdr.status)
        assertEquals(HdrPreviewMode.SDR, sdr.previewMode)
        assertEquals("hdr-display-unsupported", sdr.warningCode)
        assertEquals("HLG10", sdr.graph?.dynamicRange)
        val unknown = HlgSessionConfigurator.configure(candidate, HdrDisplayCapability.UNKNOWN)
        assertEquals(HdrPreviewMode.UNKNOWN, unknown.previewMode)
        assertEquals("hdr-display-unknown", unknown.warningCode)
        val unsupported = HlgSessionConfigurator.configure(
            candidate.copy(status = HlgDecisionStatus.UNSUPPORTED, graph = null,
                reason = HlgUnsupportedReason("codec", "not available")),
            HdrDisplayCapability.SUPPORTED,
        )
        assertEquals(HlgSessionStatus.UNSUPPORTED, unsupported.status)
        assertNull(unsupported.graph)
    }

    @Test
    fun sessionControllerExposesDuplicateCancellationAndCleanup() {
        val controller = HlgSessionController()
        assertEquals(HlgSessionCommandStatus.CANCELLED, controller.cancel("cancelled"))
        assertEquals(HlgSessionCommandStatus.CANCELLED,
            controller.configure("cancelled", candidate, HdrDisplayCapability.SUPPORTED))
        assertEquals(HlgSessionCommandStatus.STARTED,
            controller.configure("one", candidate, HdrDisplayCapability.UNKNOWN))
        assertEquals(HlgSessionCommandStatus.DUPLICATE,
            controller.configure("one", candidate, HdrDisplayCapability.UNKNOWN))
        controller.close()
        assertEquals(HlgSessionCommandStatus.CLOSED,
            controller.configure("two", candidate, HdrDisplayCapability.SUPPORTED))
    }
}
