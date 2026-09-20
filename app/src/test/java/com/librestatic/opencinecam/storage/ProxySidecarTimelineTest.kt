/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.AudioCaptureEpoch
import com.librestatic.opencinecam.camera.CaptureEpochReport
import com.librestatic.opencinecam.camera.CapturePauseWindow
import com.librestatic.opencinecam.camera.PcmSourceTimingReport
import com.librestatic.opencinecam.camera.SharedCapturePauseReport
import org.junit.Assert.*
import org.junit.Test

class ProxySidecarTimelineTest {
    private fun observed(container: ProxySidecarContainer = ProxySidecarContainer.WAV,
        audioNs: Long = 1_000_000_000, videoNs: Long = 1_000_000_000,
        frames: Long = 48_000, rate: Int = 48_000, videoFirstUs: Long = 0,
        videoEndUs: Long = 1_000_000): ProxySidecarObservation {
        val pcm = PcmSourceTimingReport(rate, 2, frames, frames, frames * 1_000_000_000L / rate,
            AudioCaptureEpoch(audioNs, true, 12), 2, 0, 0, audioNs, frames, audioNs + frames * 1_000_000_000L / rate)
        val shared = CaptureEpochReport("SHARED_BOOTTIME_CAPTURE_ANCHORS", true, videoNs, audioNs,
            minOf(audioNs, videoNs), true, 12, 777_000, null, null,
            submittedPcmFrames = frames, capturedPcmFrames = frames, audioStorage = "SEPARATE_${container.name}")
        return ProxySidecarObservation(container, shared, pcm, rate, frames, rate, frames, videoFirstUs, videoEndUs)
    }

    @Test fun wavAndFlacHaveIdenticalCompositionSemanticsAndNoImplicitProcessing() {
        val wav = proxySidecarTimeline(observed())
        val flac = proxySidecarTimeline(observed(ProxySidecarContainer.FLAC))
        assertEquals(wav, flac)
        assertEquals(0L, wav.audioStartUs); assertEquals(1_000_000L, wav.audioEndUsCeiling)
        assertEquals(1_000_000L, wav.outputEndUsCeiling)
        assertEquals(0L, wav.videoPtsShiftUs); assertEquals(0L, wav.trimmedPcmFrames)
        assertEquals(0L, wav.insertedSilenceFrames); assertEquals(0, wav.pauseCutsToApply)
        assertFalse(wav.waveformAlignmentVerified)
    }

    @Test fun earlierAudioPreservesExistingVideoOffsetAndAllAudioFrames() {
        val result = proxySidecarTimeline(observed(videoNs = 1_250_000_000, videoFirstUs = 250_000, videoEndUs = 2_000_000))
        assertEquals(250_000L, result.videoFirstPtsUs); assertEquals(0L, result.videoPtsShiftUs)
        assertEquals(0L, result.audioStartUs); assertEquals(48_000L, result.pcmFrames)
        assertEquals(250_000L, result.videoLeadingGapUs); assertEquals(0L, result.audioLeadingGapUs)
        assertEquals(1_000_000L, result.audioTrailingGapUs); assertEquals(0L, result.videoTrailingGapUs)
        assertEquals(2_000_000L, result.outputEndUsCeiling)
    }

    @Test fun laterAudioExtendsOutputRatherThanTrimmingOrInventingSilence() {
        val result = proxySidecarTimeline(observed(audioNs = 1_500_000_000))
        assertEquals(500_000L, result.audioStartUs); assertEquals(500_000L, result.audioLeadingGapUs)
        assertEquals(1_500_000L, result.audioEndUsCeiling); assertEquals(1_500_000L, result.outputEndUsCeiling)
        assertEquals(500_000L, result.videoTrailingGapUs); assertEquals(0L, result.audioTrailingGapUs)
        assertEquals(0L, result.trimmedPcmFrames); assertEquals(0L, result.insertedSilenceFrames)
    }

    @Test fun sharedPauseUsesAlreadyRetainedPcmWithoutCuttingItAgain() {
        val input = observed(frames = 96_000, videoEndUs = 2_000_000)
        val pause = SharedCapturePauseReport(48_000, 1_000_000_000,
            listOf(CapturePauseWindow(48_000, 96_000)), 144_000, 144_000, 96_000, null, null)
        val result = proxySidecarTimeline(input.copy(
            captureTiming = input.captureTiming.copy(capturedFrames = 144_000),
            sharedTiming = input.sharedTiming.copy(capturedPcmFrames = 144_000, sharedPause = pause)))
        assertEquals(96_000L, result.pcmFrames); assertEquals(2_000_000L, result.audioDurationUsFloor)
        assertEquals(2_000_000L, result.outputEndUsCeiling); assertEquals(0, result.pauseCutsToApply)
    }

    @Test fun fractionalPcmEndpointIsExactAndNeverAccumulatedPerSample() {
        val one = proxySidecarTimeline(observed(frames = 1, rate = 44_100, videoEndUs = 1))
        assertEquals(22L, one.audioDurationUsFloor); assertEquals(29_800L, one.audioEndSubMicroNumerator)
        assertEquals(44_100, one.audioEndSubMicroDenominator)
        assertEquals(23L, one.audioEndUsCeiling); assertEquals(23L, one.outputEndUsCeiling)
        val many = proxySidecarTimeline(observed(frames = 44_101, rate = 44_100, videoEndUs = 1))
        assertEquals(1_000_022L, many.audioDurationUsFloor)
        assertEquals(29_800L, many.audioEndSubMicroNumerator); assertEquals(1_000_023L, many.outputEndUsCeiling)
    }

    @Test fun anchorQuantizationMatchesCaptureMuxAndRejectsZeroOrDoubleAppliedVideoOffset() {
        val input = observed(videoNs = 1_250_000_999, videoFirstUs = 250_000, videoEndUs = 1_250_001)
        assertEquals(250_000L, proxySidecarTimeline(input).videoLeadingGapUs)
        reject(input.copy(videoFirstPtsUs = 0)); reject(input.copy(videoFirstPtsUs = 500_000))
        reject(input.copy(videoEndUs = 250_000)); reject(input.copy(videoEndUs = -1))
    }

    @Test fun decodedDeclaredWrittenAndSubmittedCountsMustAgree() {
        val input = observed()
        reject(input.copy(decodedFrames = 47_999)); reject(input.copy(declaredFrames = 48_001))
        reject(input.copy(captureTiming = input.captureTiming.copy(writtenFrames = 47_999)))
        reject(input.copy(sharedTiming = input.sharedTiming.copy(submittedPcmFrames = 48_001)))
        reject(input.copy(captureTiming = input.captureTiming.copy(durationNs = 999_999_999)))
        reject(input.copy(captureTiming = input.captureTiming.copy(capturedFrames = 48_001),
            sharedTiming = input.sharedTiming.copy(capturedPcmFrames = 48_001)))
    }

    @Test fun rateUnknownClockAnchorAndContainerMismatchesReject() {
        val input = observed()
        reject(input.copy(decodedSampleRateHz = 44_100)); reject(input.copy(declaredSampleRateHz = 44_100))
        reject(input.copy(captureTiming = input.captureTiming.copy(sampleRateHz = 44_100)))
        reject(input.copy(sharedTiming = input.sharedTiming.copy(cameraRealtime = false)))
        reject(input.copy(sharedTiming = input.sharedTiming.copy(policy = "BOOTTIME_ESTIMATED_AUDIO_START")))
        reject(input.copy(sharedTiming = input.sharedTiming.copy(audioTimestampBacked = false)))
        reject(input.copy(sharedTiming = input.sharedTiming.copy(sharedOriginNs = 999_999_999)))
        reject(input.copy(captureTiming = input.captureTiming.copy(epoch = null)))
        reject(input.copy(captureTiming = input.captureTiming.copy(epoch = AudioCaptureEpoch(1_000_000_001, true, 12))))
        reject(input.copy(container = ProxySidecarContainer.FLAC))
    }

    @Test fun unfinalizedOrInconsistentPauseNeverSilentlyChangesDuration() {
        val input = observed()
        val pause = SharedCapturePauseReport(48_000, 1_000_000_000, emptyList(), 48_000, 48_000, 48_000, null, null)
        fun withPause(value: SharedCapturePauseReport) = input.copy(sharedTiming = input.sharedTiming.copy(sharedPause = value))
        assertEquals(48_000L, proxySidecarTimeline(withPause(pause)).pcmFrames)
        reject(withPause(pause.copy(stopFrame = null)))
        reject(withPause(pause.copy(retainedPcmFrames = 47_999)))
        reject(withPause(pause.copy(sampleRateHz = 44_100)))
        reject(withPause(pause.copy(windows = listOf(CapturePauseWindow(100, null)))))
        reject(withPause(pause.copy(windows = listOf(CapturePauseWindow(100, 200)))))
        reject(withPause(pause.copy(windows = listOf(CapturePauseWindow(100, 200), CapturePauseWindow(150, 250)))))
        reject(withPause(pause.copy(windows = List(10_001) { CapturePauseWindow(0, 0) })))
    }

    @Test fun arithmeticOverflowIsExplicitInsteadOfWrappingOrTruncatingFrames() {
        val input = observed()
        val huge = input.copy(decodedSampleRateHz = 1, declaredSampleRateHz = 1,
            decodedFrames = Long.MAX_VALUE, declaredFrames = Long.MAX_VALUE,
            captureTiming = input.captureTiming.copy(sampleRateHz = 1, capturedFrames = Long.MAX_VALUE, writtenFrames = Long.MAX_VALUE),
            sharedTiming = input.sharedTiming.copy(capturedPcmFrames = Long.MAX_VALUE, submittedPcmFrames = Long.MAX_VALUE))
        assertThrows(ArithmeticException::class.java) { proxySidecarTimeline(huge) }
        reject(input.copy(decodedFrames = 0)); reject(input.copy(decodedFrames = -1))
        // Near-limit observed video endpoints are endpoints, not spans to add to the first PTS.
        assertEquals(Long.MAX_VALUE, proxySidecarTimeline(input.copy(videoEndUs = Long.MAX_VALUE)).outputEndUsCeiling)
    }

    private fun reject(input: ProxySidecarObservation) {
        assertThrows(IllegalArgumentException::class.java) { proxySidecarTimeline(input) }
    }
}
