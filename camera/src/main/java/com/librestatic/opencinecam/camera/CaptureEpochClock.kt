/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Client PCM frame positions, not codec packet counts or wall-clock read completion times. */
fun pcmFrameDurationNs(frame: Long, rate: Int): Long {
    require(frame >= 0 && rate in 1..384000)
    return Math.addExact(Math.multiplyExact(frame / rate, 1_000_000_000L), frame % rate * 1_000_000_000L / rate)
}

data class AudioCaptureEpoch(val frameZeroNs: Long, val timestampBacked: Boolean, val maxResidualNs: Long = 0)

/** One immutable frame-zero calibration; later observations measure drift, never jump the file clock. */
class PcmCaptureEpoch(private val rate: Int) {
    init { require(rate in 1..384000) }
    private var epoch: AudioCaptureEpoch? = null
    private var lastPosition: Long? = null
    private var lastTimeNs: Long? = null
    fun observe(framePosition: Long, captureTimeNs: Long): AudioCaptureEpoch {
        require(framePosition >= 0 && captureTimeNs > 0)
        check(lastPosition?.let { framePosition < it } != true && lastTimeNs?.let { captureTimeNs < it } != true) {
            "Audio capture timestamp regressed"
        }
        val offset = pcmFrameDurationNs(framePosition, rate)
        val origin = Math.subtractExact(captureTimeNs, offset)
        require(origin > 0) { "Audio capture epoch is not positive" }
        val current = epoch
        if (current == null) epoch = AudioCaptureEpoch(origin, true)
        else if (current.timestampBacked) {
            val residual = kotlin.math.abs(Math.subtractExact(origin, current.frameZeroNs))
            epoch = current.copy(maxResidualNs = maxOf(current.maxResidualNs, residual))
        }
        lastPosition = framePosition; lastTimeNs = captureTimeNs
        return requireNotNull(epoch)
    }
    fun estimate(startReceiptNs: Long): AudioCaptureEpoch {
        require(startReceiptNs > 0)
        if (epoch == null) epoch = AudioCaptureEpoch(startReceiptNs, false)
        return requireNotNull(epoch)
    }
    fun current(): AudioCaptureEpoch? = epoch
}

data class CaptureEpochReport(
    val policy: String,
    val cameraRealtime: Boolean,
    val videoFrameZeroNs: Long?,
    val audioFrameZeroNs: Long?,
    val sharedOriginNs: Long?,
    val audioTimestampBacked: Boolean,
    val audioMaxResidualNs: Long?,
    val videoEncoderFirstPtsUs: Long?,
    val audioEncoderFirstPtsUs: Long?,
    val audioEncoderDelayFrames: Int?,
    // Shared capture anchors do not by themselves measure microphone/codec priming or lip sync.
    val waveformAlignmentVerified: Boolean = false,
    val submittedPcmFrames: Long = 0,
    val encodedAudioPackets: Long = 0,
    val aacCalibration: AacCodecCalibration? = null,
    val audioDrainPaddingFrames: Long = 0,
    val audioSourceWindow: AacSourceWindowResult? = null,
    val audioPresentationOffsetUs: Long? = null,
    val capturedPcmFrames: Long = 0,
    val sharedPause: SharedCapturePauseReport? = null,
    val audioStorage: String = "EMBEDDED_AAC",
)

/** The GL video producer and PCM feeder publish their anchors before encoded samples are muxed. */
class CaptureEpochClock(val cameraRealtime: Boolean, val sampleRateHz: Int? = null) {
    private var videoNs: Long? = null
    private var audio: AudioCaptureEpoch? = null
    private var readFrames = 0L
    private var lastVideoNs: Long? = null
    private var pause: SharedCapturePause? = null
    private fun preparePause() {
        if (pause == null && cameraRealtime && videoNs != null && audio?.timestampBacked == true && sampleRateHz != null) {
            pause = SharedCapturePause(sampleRateHz, requireNotNull(audio).frameZeroNs, readFrames, lastVideoNs)
        }
    }
    @Synchronized fun pauseAvailable(): Boolean = pause != null
    /** Why shared pause is not available yet (or at all) for this take; null once it is. */
    @Synchronized fun pauseUnavailableReason(): CapturePauseRejection? = when {
        pause != null -> null
        !cameraRealtime || sampleRateHz == null -> CapturePauseRejection.UNSUPPORTED_CLOCK
        videoNs == null || audio == null -> CapturePauseRejection.ANCHORS_PENDING
        else -> CapturePauseRejection.ESTIMATED_AUDIO_ANCHOR
    }
    /** Reason for the most recent rejected [requestPaused]/[setPaused]; cleared by an applied command. */
    @Volatile var lastPauseRejection: CapturePauseRejection? = null
        private set
    @Synchronized fun requestPaused(value: Boolean, nowNs: Long): CapturePauseRejection? =
        (pause?.requestPaused(value, nowNs) ?: pauseUnavailableReason()).also { lastPauseRejection = it }
    @Synchronized fun setPaused(value: Boolean, nowNs: Long): Boolean = requestPaused(value, nowNs) == null
    @Synchronized fun regressedVideoFrames(): Long = pause?.regressedVideoFrames ?: 0
    @Synchronized fun finishPause(nowNs: Long) { pause?.finish(nowNs) }
    @Synchronized fun mapVideoInput(sourceNs: Long): Long? {
        videoInput(sourceNs); preparePause()
        val mapped = if (pause != null) pause!!.video(sourceNs) else sourceNs
        // A later-created pause must not place a boundary before an already submitted (higher) timestamp.
        lastVideoNs = maxOf(lastVideoNs ?: sourceNs, sourceNs)
        return mapped
    }
    @Synchronized fun selectAudio(startFrame: Long, count: Int): List<PcmKeepSpan> {
        require(startFrame == readFrames && count >= 0)
        val spans = pause?.audio(startFrame, count) ?: if (count == 0) emptyList() else listOf(PcmKeepSpan(0, count))
        readFrames = Math.addExact(startFrame, count.toLong())
        return spans
    }
    @Synchronized fun requireRetainedAudioFrames(submitted: Long) {
        check(submitted == (pause?.report()?.retainedPcmFrames ?: readFrames)) { "Selected PCM did not reach the AAC encoder" }
    }
    @Synchronized fun videoInput(sourceNs: Long) {
        require(sourceNs >= 0 && (!cameraRealtime || sourceNs > 0))
        if (videoNs == null) videoNs = sourceNs
    }
    @Synchronized fun audioInput(epoch: AudioCaptureEpoch) {
        val previous = audio
        check(previous == null || previous.frameZeroNs == epoch.frameZeroNs && previous.timestampBacked == epoch.timestampBacked) {
            "Audio capture anchor changed during the take"
        }
        audio = epoch
        preparePause()
    }
    @Synchronized fun ready(): Boolean = !cameraRealtime || videoNs != null && audio != null
    @Synchronized fun offsetUs(video: Boolean): Long {
        check(ready()) { "Capture anchors are pending" }
        if (!cameraRealtime) return 0
        val videoOrigin = requireNotNull(videoNs)
        val audioOrigin = requireNotNull(audio).frameZeroNs
        val origin = minOf(videoOrigin, audioOrigin)
        return ((if (video) videoOrigin else audioOrigin) - origin) / 1000
    }
    @Synchronized fun report(videoEncoderFirstPtsUs: Long?, audioEncoderFirstPtsUs: Long?, encoderDelayFrames: Int?): CaptureEpochReport {
        val known = videoNs != null && audio != null
        return CaptureEpochReport(
            policy = when {
                !cameraRealtime -> "INDEPENDENT_UNKNOWN_CAMERA_EPOCH"
                !known -> "CAPTURE_ANCHORS_PENDING"
                audio?.timestampBacked == true -> "SHARED_BOOTTIME_CAPTURE_ANCHORS"
                else -> "BOOTTIME_ESTIMATED_AUDIO_START"
            }, cameraRealtime = cameraRealtime, videoFrameZeroNs = videoNs,
            audioFrameZeroNs = audio?.frameZeroNs,
            sharedOriginNs = if (cameraRealtime && known) minOf(requireNotNull(videoNs), requireNotNull(audio).frameZeroNs) else null,
            audioTimestampBacked = audio?.timestampBacked == true, audioMaxResidualNs = audio?.takeIf { it.timestampBacked }?.maxResidualNs,
            videoEncoderFirstPtsUs = videoEncoderFirstPtsUs, audioEncoderFirstPtsUs = audioEncoderFirstPtsUs,
            audioEncoderDelayFrames = encoderDelayFrames,
            capturedPcmFrames = readFrames, sharedPause = pause?.report(),
        )
    }
}

/** Requested AAC needs captured PCM and encoded payload, not merely a track format or priming packet. */
fun hasRequiredEncodedSamples(videoFrames: Long, audioPcmFrames: Long?, audioPackets: Long): Boolean =
    videoFrames > 0 && (audioPcmFrames == null || audioPcmFrames > 0 && audioPackets > 0)
