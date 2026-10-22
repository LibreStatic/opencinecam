/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.HardwareTruthHudState
import com.librestatic.opencinecam.ROUTE_KEY
import com.librestatic.opencinecam.RouteTruth
import com.librestatic.opencinecam.camera.CapturePauseWindow
import com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.SharedCapturePauseReport
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipFactsAssemblerTest {
    private fun evidence() = OpenCineLogRecordingEvidence(
        sourcePath = OpenCineLogSourcePath.HLG10_BT2020, sourceDynamicRange = "HLG10", sourceColorSpace = "BT2020_HLG",
        sourceTransfer = "HLG", sourcePrecision = "10-bit", sourceDataSpace = null, codecName = "c2.qti.hevc.encoder",
        targetFps = 24, transformSha256 = "a".repeat(64), encodedFrames = 100, firstPtsUs = 0, lastPtsUs = 4_000_000,
        maxVideoPtsGapUs = 41_667, videoPtsGapsOverThreshold = 0, geometryMode = RecordingGeometryMode.COMPATIBLE,
        deviceOrientationDegrees = 0, pixelRotationDegrees = 0, rendererRotationDegrees = 0, containerRotationDegrees = 90,
        encodedWidth = 1080, encodedHeight = 1920, displayWidth = 1080, displayHeight = 1920,
    )

    private fun input(
        evidence: OpenCineLogRecordingEvidence? = null, hdr: Boolean = false, lutBaked: Boolean = false, software: String? = null,
        pause: SharedCapturePauseReport? = null, mediaRecorder: Boolean? = true,
    ) = ClipFactsInput(
        startedAt = Instant.parse("2026-10-22T10:00:00Z"), intent = null, cameraId = "0",
        hardware = HardwareTruthHudState(requested = mapOf(ROUTE_KEY to "5"), reported = mapOf(ROUTE_KEY to "5"), routeTruth = RouteTruth.PHYSICAL_CONFIRMED),
        recordingWidth = 3840, recordingHeight = 2160, targetFps = 30, graphEvidence = evidence, hdr = hdr, lutBaked = lutBaked,
        timelapse = false, mediaRecorder = mediaRecorder, recorderCodecMime = "video/hevc", softwareEncoderName = software, sharedPause = pause,
        separateAudio = ClipSeparateAudio("WAV", 48_000, 24, listOf("take.wav", "take.audio.json")), embeddedAudio = false,
        deviceProfile = null, noticeCodes = listOf("low-storage"), hardwareWarnings = listOf("route-mismatch"), stopReason = "user",
        durationMs = 4_000, encodedFrames = 100, success = true, nativeRetired = true,
    )

    @Test fun theRecorderIsWhatTheEngineReportedAtTheStartNeverInferred() {
        assertEquals("MediaCodec", assembleClipFacts(input(mediaRecorder = false)).graph.recorder)
        assertNull(assembleClipFacts(input(mediaRecorder = null)).graph.recorder)
    }

    @Test fun plainSdrTakeIsWrittenByMediaRecorderWithTheRequestedGeometry() {
        val facts = assembleClipFacts(input())
        assertEquals("MediaRecorder", facts.graph.recorder)
        assertEquals("SDR_BT709", facts.graph.colorKind)
        assertEquals(3840, facts.graph.encodedWidth)
        assertEquals("video/hevc", facts.graph.codecMime)
        assertEquals("PHYSICAL_CONFIRMED", facts.graph.routeTruth)
        assertEquals("5", facts.graph.requestedRoute)
        assertEquals("SEPARATE_WAV", facts.graph.audioStorage)
        assertNull(facts.graph.hardwareAccelerated)
        assertEquals(listOf("runtime-notice:low-storage", "hardware-truth-warning"), facts.warnings.map { it.code })
        assertEquals(listOf("take.wav", "take.audio.json"), facts.audioFiles)
    }

    @Test fun hlgAndLogTakesGoThroughMediaCodecWithTheirOwnColour() {
        val hlg = assembleClipFacts(input(evidence(), hdr = true, mediaRecorder = false)).graph
        assertEquals("MediaCodec", hlg.recorder)
        assertEquals("HLG", hlg.colorKind)
        assertEquals("c2.qti.hevc.encoder", hlg.encoderName)
        assertEquals(90, hlg.containerRotationDegrees)
        assertEquals(24, hlg.fps)
        val log = assembleClipFacts(input(evidence())).graph
        assertEquals("OCLog2", log.colorKind)
        assertEquals("OCLog2", log.colorTransfer)
        // A LUT baked into the pixels means the file is no longer LOG.
        val baked = assembleClipFacts(input(evidence(), lutBaked = true)).graph
        assertEquals("SDR_BT709", baked.colorKind)
        assertNull(baked.colorTransfer)
    }

    @Test fun softwareEncoderIsNeverReportedAsHardwareAndRaisesAWarning() {
        val facts = assembleClipFacts(input(software = "c2.android.avc.encoder"))
        assertTrue(facts.graph.softwareEncoder)
        assertEquals(false, facts.graph.hardwareAccelerated)
        assertEquals("c2.android.avc.encoder", facts.graph.encoderName)
        assertTrue(facts.warnings.any { it.code == "software-video-encoder" })
        assertFalse(assembleClipFacts(input()).graph.softwareEncoder)
    }

    @Test fun pauseWindowsAreConvertedFromPcmFramesToMicroseconds() {
        val report = SharedCapturePauseReport(
            sampleRateHz = 48_000, audioFrameZeroNs = 0, windows = listOf(CapturePauseWindow(48_000, 96_000), CapturePauseWindow(144_000, null)),
            stopFrame = null, capturedPcmFrames = 192_000, retainedPcmFrames = 100_000, lastCommandNs = null, lastEffectiveBoundaryNs = null,
        )
        val pauses = assembleClipFacts(input(pause = report)).pauses
        assertEquals(1_000_000L, pauses[0].startUs)
        assertEquals(2_000_000L, pauses[0].endUs)
        assertEquals(3_000_000L, pauses[1].startUs)
        assertNull(pauses[1].endUs)
    }
}
