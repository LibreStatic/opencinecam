/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.RawVideoConfigKeys
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoQualificationRecord
import com.librestatic.opencinecam.camera.RawVideoReasons
import com.librestatic.opencinecam.media.raw.NativeRawCodec
import com.librestatic.opencinecam.rawvideo.RawQualificationFrameRate
import com.librestatic.opencinecam.rawvideo.RawTakeReasons

/** OCC-PLAN-071 stable reasons the service refuses or ends a RAW take with; shown to the operator. */
internal object RawVideoServiceReasons {
    /** The camera has no qualified configuration for this build (the gate is CANDIDATE). */
    const val NOT_QUALIFIED = "raw-video-not-qualified"
    /** The camera advertises no RAW10 output. */
    const val UNSUPPORTED = "raw-video-unsupported"
    /** The camera refused the RAW_VIDEO graph in this service session. */
    const val FAILED = "raw-video-failed"
    /** The RAM ring could not be planned or allocated. */
    const val MEMORY = "raw-video-memory"
    /** A compressed configuration (block-pack or LJ92), but the native encoder could not be created. */
    const val CODEC = RawTakeReasons.CODEC_UNAVAILABLE
    /** Less free storage than [RAW_TAKE_MIN_SECONDS] of the configuration's data rate. */
    const val STORAGE = "raw-video-storage"
    /** The output, the recorder or the engine refused the take. */
    const val START_FAILED = "raw-video-start-failed"
}

/** A RAW take needs room for at least this many seconds of its stream before it starts. */
internal const val RAW_TAKE_MIN_SECONDS = 10L

/**
 * The configurations of [cameraId] a RAW_VIDEO session can run: [qualified] (passed or risky) for
 * this build, recorded at a RAW10 size the camera still advertises. Highest data rate first, then
 * the higher frame rate, then the uncompressed variant before its compressed one.
 *
 * A compressed configuration (block-pack, the "Save space" codec, or LJ92 from older records)
 * counts on its own record (protocol 3 judges it from the encode benchmark,
 * its twin's camera and the storage at the compressed rate, PLAN-071 decision 1), so its
 * uncompressed twin may be risky or even unrecorded; it needs [codecAvailable] (the native codec
 * library loaded) and stays out when the twin's newest record failed for the camera
 * ([cameraFailed]: any failure that is not only the storage's). Both read "Compressed" in the UI, so
 * when block-pack and LJ92 of the same geometry are both usable only block-pack stays. Unknown
 * encodings are ignored.
 */
internal fun rawVideoUsableConfigs(
    cameraId: String,
    raw10Sizes: List<Pair<Int, Int>>,
    qualified: List<RawVideoConfig>,
    codecAvailable: Boolean = NativeRawCodec.available,
    cameraFailed: Collection<RawVideoConfig> = emptyList(),
): List<RawVideoConfig> {
    val candidates = qualified.filter { it.cameraId == cameraId && (it.sensorWidth to it.sensorHeight) in raw10Sizes }
    val failed = cameraFailed.toSet()
    return candidates
        .filter {
            when (it.encoding) {
                RawVideoConfig.ENCODING_RAW10 -> true
                in RawVideoConfig.COMPRESSED_ENCODINGS -> codecAvailable && it.copy(encoding = RawVideoConfig.ENCODING_RAW10) !in failed
                else -> false
            }
        }
        .distinctBy(RawVideoConfigKeys::key)
        .let { usable ->
            val blockPack = usable.filter { it.encoding == RawVideoConfig.ENCODING_BLOCK_PACK }
                .mapTo(mutableSetOf()) { it.copy(encoding = RawVideoConfig.ENCODING_RAW10) }
            usable.filterNot { it.encoding == RawVideoConfig.ENCODING_LJ92 && it.copy(encoding = RawVideoConfig.ENCODING_RAW10) in blockPack }
        }
        .sortedWith(compareByDescending<RawVideoConfig> { it.streamBytesPerSecond }.thenByDescending { it.fps }.thenBy { it.isCompressed }
            .thenBy { RawVideoConfig.COMPRESSED_ENCODINGS.indexOf(it.encoding) })
}

/**
 * PLAN-071 decision 10, the RAW_VIDEO gate of one camera: UNSUPPORTED without a RAW10 output,
 * FAILED once the camera refused the RAW_VIDEO graph in this service session, AVAILABLE with at
 * least one [usable] configuration (a risky one counts: it can be chosen by hand), otherwise
 * CANDIDATE (choosing the mode asks to qualify the camera).
 */
internal fun rawVideoGate(raw10Sizes: List<Pair<Int, Int>>, usable: List<RawVideoConfig>, failedThisSession: Boolean): ModeGateState = when {
    raw10Sizes.isEmpty() -> ModeGateState.UNSUPPORTED
    failedThisSession -> ModeGateState.FAILED
    usable.isNotEmpty() -> ModeGateState.AVAILABLE
    else -> ModeGateState.CANDIDATE
}

/**
 * The [usable] configuration [key] names; unknown, empty or no longer qualified keys fall back to the
 * full sensor field of view at its highest qualified rate, uncompressed. A crop (narrower field of
 * view, PLAN-071 decision 5) and a compressed variant are only ever explicit choices; a camera with only crops
 * qualified falls back to the highest data rate among them. A [risky] configuration is only ever an
 * explicit choice too: the fallback never picks one, so with only risky ones it is null. The caller
 * passes reduced-rate configurations ([rawVideoReducedRates]) in [risky] as well.
 */
internal fun selectRawVideoConfig(usable: List<RawVideoConfig>, key: String, risky: Set<RawVideoConfig> = emptySet()): RawVideoConfig? =
    usable.firstOrNull { RawVideoConfigKeys.key(it) == key } ?: usable.filterNot { it.isCompressed || it in risky }.maxWithOrNull(
        compareBy<RawVideoConfig> { !RawVideoConfigKeys.isCrop(it) }.thenBy { it.fps }.thenBy { it.streamBytesPerSecond },
    )

/**
 * The risky configurations among [usable]: those [riskyRecorded] (the storage is too slow for them).
 * A compressed variant has its own record, judged at its compressed rate, so a risky twin does not make
 * it risky (protocol 3).
 */
internal fun rawVideoRiskyConfigs(usable: List<RawVideoConfig>, riskyRecorded: Collection<RawVideoConfig>): Set<RawVideoConfig> {
    val recorded = riskyRecorded.toSet()
    return usable.filter { it in recorded }.toSet()
}

/**
 * The reduced-rate configurations among [usable] (PLAN-071): those whose newest record among [current]
 * for [destinationId] ([rawVideoNewestRecord]) says the camera delivered under its target
 * ([RawQualificationFrameRate.isReducedRate]), each with the rate it achieved (null when the record
 * names none). Automatic treats them like risky ones: never picked, choosable by hand.
 */
internal fun rawVideoReducedRates(
    usable: List<RawVideoConfig>,
    current: List<RawVideoQualificationRecord>,
    destinationId: String,
): Map<RawVideoConfig, Double?> = usable.mapNotNull { config ->
    rawVideoNewestRecord(current, destinationId, config)?.takeIf(RawQualificationFrameRate::isReducedRate)?.let { config to it.achievedFps }
}.toMap()

/**
 * The newest record of [config] among [current] records (already limited to this build and
 * protocol) for [destinationId]; a compressed configuration without its own record falls back to
 * its uncompressed twin's, which streams the same rate into the ring. Protocol 3 stores compressed records,
 * so the fallback only serves a record set that lacks one.
 */
internal fun rawVideoNewestRecord(
    current: List<RawVideoQualificationRecord>,
    destinationId: String,
    config: RawVideoConfig,
): RawVideoQualificationRecord? {
    fun newest(of: RawVideoConfig) = current.filter { it.destinationId == destinationId && it.config == of }
        .maxByOrNull(RawVideoQualificationRecord::measuredAtEpochMs)
    return newest(config) ?: if (config.isCompressed) newest(config.copy(encoding = RawVideoConfig.ENCODING_RAW10)) else null
}

/**
 * PLAN-071 decision 9, the runtime risk: the ring armed for a take is smaller than its qualification
 * needed. It is risky when the qualification's highest ring use, the larger of the live run's peak
 * and the simulated backlog, passes [RawThroughputBenchmark.RING_LIMIT_PERCENT] of [armedRingBytes]
 * (the rule qualification itself applies). A record without either figure falls back to comparing
 * the rings: risky when [armedRingBytes] is smaller than the one it was qualified with. No record,
 * no runtime risk.
 */
internal fun rawVideoRingTooSmall(record: RawVideoQualificationRecord?, armedRingBytes: Long): Boolean {
    if (record == null || armedRingBytes <= 0) return false
    val q = record.qualification
    val peak = listOfNotNull(q.live?.peakRingBytes, q.backlog?.maxBacklogBytes).maxOrNull()
        ?: return armedRingBytes < record.ringBytes
    return peak > armedRingBytes * RawThroughputBenchmark.RING_LIMIT_PERCENT / 100L
}

/** Free bytes a take at [config] must find before it starts. */
internal fun rawTakeMinimumFreeBytes(config: RawVideoConfig): Long = config.streamBytesPerSecond * RAW_TAKE_MIN_SECONDS

internal sealed interface RawTakeAdmission {
    data object Admit : RawTakeAdmission
    data class Refuse(val reason: String) : RawTakeAdmission
}

/**
 * Whether a RAW take may start. Never an encoded fallback: every refusal names its reason.
 * [ringUnavailable] is true when arming the ring for [config] already failed for lack of memory.
 * An unreadable free-space figure refuses, as a take of this rate cannot start blind.
 */
internal fun admitRawTake(gate: ModeGateState?, config: RawVideoConfig?, ringUnavailable: Boolean, freeBytes: Long?): RawTakeAdmission {
    val gateReason = when (gate) {
        ModeGateState.AVAILABLE -> null
        ModeGateState.UNSUPPORTED -> RawVideoServiceReasons.UNSUPPORTED
        ModeGateState.FAILED -> RawVideoServiceReasons.FAILED
        else -> RawVideoServiceReasons.NOT_QUALIFIED
    }
    if (gateReason != null) return RawTakeAdmission.Refuse(gateReason)
    if (config == null) return RawTakeAdmission.Refuse(RawVideoServiceReasons.NOT_QUALIFIED)
    if (ringUnavailable) return RawTakeAdmission.Refuse(RawVideoServiceReasons.MEMORY)
    if (freeBytes == null || freeBytes < rawTakeMinimumFreeBytes(config)) return RawTakeAdmission.Refuse(RawVideoServiceReasons.STORAGE)
    return RawTakeAdmission.Admit
}

/**
 * Why a RAW take ended, or null for the operator's stop. The recorder's own reason is the root cause
 * (a full ring, a writer fault, the stream failing); then the engine's; then the live policy's stop
 * (heat, storage), which reaches the engine as an ordinary stop.
 */
internal fun rawTakeEndReason(recorderReason: String?, engineReason: String?, policyReason: String?): String? =
    recorderReason ?: engineReason ?: policyReason

/** What the operator is told about a RAW take's [reason]. */
internal enum class RawTakeEndKind { RING_FULL, WRITER, STREAM, FRAME, STALLED, OTHER }

internal fun rawTakeEndKind(reason: String): RawTakeEndKind = when {
    reason == RawTakeReasons.RING_FULL || reason == RawVideoReasons.RING_FULL -> RawTakeEndKind.RING_FULL
    reason == RawTakeReasons.WRITER_ERROR -> RawTakeEndKind.WRITER
    reason == RawTakeReasons.WRITER_STALLED -> RawTakeEndKind.STALLED
    reason == RawTakeReasons.FRAME_INVALID || reason == RawVideoReasons.FRAME_LOST ||
        reason == RawTakeReasons.STREAM_FAILED_PREFIX + RawVideoReasons.FRAME_LOST -> RawTakeEndKind.FRAME
    reason.startsWith(RawTakeReasons.STREAM_FAILED_PREFIX) || reason == RawTakeReasons.STREAM_MISMATCH || reason in setOf(
        RawVideoReasons.CAMERA_DISCONNECTED, RawVideoReasons.CAMERA_ERROR, RawVideoReasons.SESSION_LOST, RawVideoReasons.SESSION_CLOSED,
    ) -> RawTakeEndKind.STREAM
    else -> RawTakeEndKind.OTHER
}
