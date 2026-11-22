/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.capture.RawQualificationItemStatus
import com.librestatic.opencinecam.capture.RawVideoConfigKeys
import com.librestatic.opencinecam.capture.RawVideoConfigOption
import com.librestatic.opencinecam.capture.RawVideoCropPreview
import com.librestatic.opencinecam.capture.RawVideoCropStatus
import com.librestatic.opencinecam.capture.RawVideoSelection
import com.librestatic.opencinecam.rawvideo.RawVideoArmState
import com.librestatic.opencinecam.rawvideo.RawVideoCrop
import com.librestatic.opencinecam.capture.RawVideoLeftOutOption
import com.librestatic.opencinecam.capture.RawVideoLeftOutReason
import com.librestatic.opencinecam.capture.rawQualificationRunStatus
import com.librestatic.opencinecam.rawvideo.RawQualificationRisk
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
        .sortedWith(RAW_VIDEO_CONFIG_ORDER)
}

/** The order the RAW chooser lists configurations in: highest data rate, then frame rate, uncompressed before compressed. */
private val RAW_VIDEO_CONFIG_ORDER: Comparator<RawVideoConfig> =
    compareByDescending<RawVideoConfig> { it.streamBytesPerSecond }.thenByDescending { it.fps }.thenBy { it.isCompressed }
        .thenBy { RawVideoConfig.COMPRESSED_ENCODINGS.indexOf(it.encoding) }

/** A configuration the qualification judged but the chooser cannot offer, with the result-row [status] that explains why. */
internal data class RawVideoLeftOutConfig(val config: RawVideoConfig, val status: RawQualificationItemStatus)

/**
 * The configurations of [cameraId] the qualification on [destinationId] judged that are not [usable], each with
 * why ([rawQualificationRunStatus] of its newest record among [current], already limited to this build and protocol),
 * in the chooser's order ([rawVideoUsableConfigs]). Only records at a RAW10 size the camera still advertises
 * ([raw10Sizes]) and of RAW10 or block-pack count (LJ92 and unknown encodings are ignored), and a usable key is never
 * left out. A record that reads PASSED or RISKY but is not usable is a compressed configuration whose uncompressed twin
 * failed for the camera, or whose codec is missing: it takes the twin's newest status when that one is a failure, and
 * is omitted otherwise. Skips are stored as UNKNOWN verdicts whose reason is the skip's (`thermal-skip`,
 * `insufficient-space`), so they classify the same way. Display only: the gate and selection never read it.
 */
internal fun rawVideoLeftOutConfigs(
    cameraId: String,
    raw10Sizes: List<Pair<Int, Int>>,
    current: List<RawVideoQualificationRecord>,
    destinationId: String,
    usable: List<RawVideoConfig>,
): List<RawVideoLeftOutConfig> {
    val usableKeys = usable.mapTo(mutableSetOf(), RawVideoConfigKeys::key)
    val newest = current
        .filter { it.destinationId == destinationId && it.config.cameraId == cameraId && (it.config.sensorWidth to it.config.sensorHeight) in raw10Sizes }
        .groupBy { it.config }
        .mapValues { (_, records) -> records.maxBy(RawVideoQualificationRecord::measuredAtEpochMs) }
    fun statusOf(record: RawVideoQualificationRecord): RawQualificationItemStatus =
        rawQualificationRunStatus(record.qualification.status.name, record.qualification.reasonCode, record.qualification.reasonCodes, RawQualificationRisk.effectiveBurstSeconds(record))
    fun usableStatus(status: RawQualificationItemStatus) = status == RawQualificationItemStatus.PASSED || status == RawQualificationItemStatus.RISKY
    return newest.values
        .filter { it.config.encoding == RawVideoConfig.ENCODING_RAW10 || it.config.encoding == RawVideoConfig.ENCODING_BLOCK_PACK }
        .filter { RawVideoConfigKeys.key(it.config) !in usableKeys }
        .mapNotNull { record ->
            val own = statusOf(record)
            val status = if (!usableStatus(own)) own
                else if (record.config.isCompressed) newest[record.config.copy(encoding = RawVideoConfig.ENCODING_RAW10)]?.let(::statusOf)?.takeUnless(::usableStatus)
                else null
            status?.let { RawVideoLeftOutConfig(record.config, it) }
        }
        .sortedWith(compareBy(RAW_VIDEO_CONFIG_ORDER) { it.config })
        .distinctBy { RawVideoConfigKeys.key(it.config) }
}

/** What the chooser says about a left-out configuration's [status]: heat, the camera's rate, the storage, or not qualified. */
internal fun rawVideoLeftOutReason(status: RawQualificationItemStatus): RawVideoLeftOutReason = when (status) {
    RawQualificationItemStatus.SKIPPED_HEAT -> RawVideoLeftOutReason.HEAT
    RawQualificationItemStatus.CAMERA_LIMIT -> RawVideoLeftOutReason.CAMERA_LIMIT
    RawQualificationItemStatus.STORAGE_LIMIT -> RawVideoLeftOutReason.STORAGE
    else -> RawVideoLeftOutReason.NOT_QUALIFIED
}

/** [leftOut] as [com.librestatic.opencinecam.CameraUiState.rawVideoLeftOut] lists it. */
internal fun rawVideoLeftOutOptions(leftOut: List<RawVideoLeftOutConfig>): List<RawVideoLeftOutOption> =
    leftOut.map { RawVideoLeftOutOption(RawVideoConfigOption.of(it.config), it.config.fps, rawVideoLeftOutReason(it.status)) }

/** PLAN-076, what [rawVideoCustomVerdict] says about a window of the operator's size. */
internal sealed interface RawVideoCustomVerdict {
    /**
     * Covered by a passed configuration. [reducedRate] is true when every coverer is reduced-rate, and then
     * [achievedFps] is the highest rate one of them achieved (null when none is known).
     */
    data class Passed(val reducedRate: Boolean, val achievedFps: Double?) : RawVideoCustomVerdict

    /** Choosable by hand with a warning; [burstSeconds] as [RawVideoConfigOption.burstSeconds], null when unknown. */
    data class Risky(val burstSeconds: Long?) : RawVideoCustomVerdict

    data class Refused(val reason: RawVideoLeftOutReason) : RawVideoCustomVerdict
}

/**
 * PLAN-076, whether [config], a size the qualification may never have run, can be recorded on the destination whose
 * [usable] configurations ([rawVideoUsableConfigs]), [risky] and [reduced] ones ([rawVideoRiskyConfigs],
 * [rawVideoReducedRates]) and [leftOut] ones ([rawVideoLeftOutConfigs]) are given. A configuration covers it when it
 * has the same camera, sensor, rate and encoding and streams at least as much. In order:
 * - a qualified [config] keeps its own verdict;
 * - the camera must be known to hold the rate: a usable configuration at it, or one left out for the storage alone;
 *   otherwise it is refused for the left-out ones' reason at that rate (camera limit, then heat), or not qualified;
 * - uncompressed, a usable coverer that is not risky passes it; else the storage decides from
 *   [sweepMedianBytesPerSecond]: risky when it meets the margin, keeps up below it, a risky configuration covers it, or
 *   the [ringBytes] ring lasts [RawQualificationRisk.MIN_RISKY_BURST_SECONDS]; otherwise refused for the storage;
 * - block-pack needs a usable block-pack configuration at the rate with at least as many pixels (the encoder's cost),
 *   risky when only risky ones have them; any other compressed encoding is not qualified.
 */
internal fun rawVideoCustomVerdict(
    config: RawVideoConfig,
    usable: List<RawVideoConfig>,
    risky: Set<RawVideoConfig>,
    reduced: Map<RawVideoConfig, Double?>,
    leftOut: List<RawVideoLeftOutConfig>,
    sweepMedianBytesPerSecond: Long?,
    ringBytes: Long?,
): RawVideoCustomVerdict {
    fun passed(coverers: List<RawVideoConfig>): RawVideoCustomVerdict.Passed {
        val reducedRate = coverers.all { it in reduced }
        return RawVideoCustomVerdict.Passed(reducedRate, if (reducedRate) coverers.mapNotNull { reduced[it] }.maxOrNull() else null)
    }
    if (config in usable) return if (config in risky) RawVideoCustomVerdict.Risky(null) else passed(listOf(config))

    fun sameCamera(other: RawVideoConfig) = other.cameraId == config.cameraId &&
        other.sensorWidth == config.sensorWidth && other.sensorHeight == config.sensorHeight && other.fps == config.fps
    val leftOutAtRate = leftOut.filter { sameCamera(it.config) }
    val cameraHolds = usable.any(::sameCamera) || leftOutAtRate.any { it.status == RawQualificationItemStatus.STORAGE_LIMIT }
    if (!cameraHolds) {
        val reasons = leftOutAtRate.map { rawVideoLeftOutReason(it.status) }
        val reason = listOf(RawVideoLeftOutReason.CAMERA_LIMIT, RawVideoLeftOutReason.HEAT).firstOrNull { it in reasons }
            ?: RawVideoLeftOutReason.NOT_QUALIFIED
        return RawVideoCustomVerdict.Refused(reason)
    }

    val sameKind = usable.filter { sameCamera(it) && it.encoding == config.encoding }
    return when (config.encoding) {
        RawVideoConfig.ENCODING_RAW10 -> {
            val stream = config.streamBytesPerSecond
            val coverers = sameKind.filter { it.streamBytesPerSecond >= stream }
            val safe = coverers.filterNot { it in risky }
            val median = sweepMedianBytesPerSecond
            val burst = RawQualificationRisk.estimateBurstSeconds(stream, median, ringBytes)
            when {
                safe.isNotEmpty() -> passed(safe)
                median != null && median * 10_000 >= stream * RawThroughputBenchmark.MARGIN_BASIS_POINTS -> RawVideoCustomVerdict.Risky(null)
                // Only the margin is missed: the cache does not limit it, as RawQualificationRisk.isRisky reads a null burst.
                median != null && median >= stream -> RawVideoCustomVerdict.Risky(null)
                coverers.isNotEmpty() || (burst != null && burst >= RawQualificationRisk.MIN_RISKY_BURST_SECONDS) ->
                    RawVideoCustomVerdict.Risky(burst)
                else -> RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.STORAGE)
            }
        }
        RawVideoConfig.ENCODING_BLOCK_PACK -> {
            val pixels = config.cropWidth.toLong() * config.cropHeight
            val coverers = sameKind.filter { it.cropWidth.toLong() * it.cropHeight >= pixels }
            val safe = coverers.filterNot { it in risky }
            when {
                safe.isNotEmpty() -> passed(safe)
                coverers.isNotEmpty() -> RawVideoCustomVerdict.Risky(null)
                else -> RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.NOT_QUALIFIED)
            }
        }
        else -> RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.NOT_QUALIFIED)
    }
}

/**
 * PLAN-076: what windows of the operator's on [cameraId] are judged against, as the last gate recompute read it for
 * one destination: its RAW output ([sensorWidth] x [sensorHeight], the size the qualification reads), the [usable],
 * [risky], [reduced] and [leftOut] configurations, the storage's sweep median and ring ([rawVideoCustomVerdict]), and
 * the burst each risky configuration's record names ([burstSeconds]).
 */
internal data class RawVideoCustomContext(
    val cameraId: String,
    val sensorWidth: Int,
    val sensorHeight: Int,
    val usable: List<RawVideoConfig>,
    val risky: Set<RawVideoConfig> = emptySet(),
    val reduced: Map<RawVideoConfig, Double?> = emptyMap(),
    val leftOut: List<RawVideoLeftOutConfig> = emptyList(),
    val sweepMedianBytesPerSecond: Long? = null,
    val ringBytes: Long? = null,
    val burstSeconds: Map<RawVideoConfig, Long> = emptyMap(),
    /** The ring armed now, whose budget a take of its own configuration and window runs with. */
    val armed: RawVideoArmState? = null,
) {
    /**
     * [key] parsed on this camera ([RawVideoConfigKeys.parse]) and judged ([rawVideoCustomVerdict]); null when it does not
     * parse here. A usable configuration at its centred origin is no window of the operator's: its verdict is null. A
     * risky one of a usable size keeps its record's burst.
     */
    fun judge(key: String): RawVideoResolvedSelection? {
        val selection = RawVideoConfigKeys.parse(key, cameraId, sensorWidth, sensorHeight) ?: return null
        val config = selection.config
        if (config in usable && selection.crop.isCentredIn(config)) return RawVideoResolvedSelection(selection, null)
        val verdict = rawVideoCustomVerdict(config, usable, risky, reduced, leftOut, sweepMedianBytesPerSecond, ringBytes)
            .let { if (it is RawVideoCustomVerdict.Risky && it.burstSeconds == null && config in usable) RawVideoCustomVerdict.Risky(burstSeconds[config]) else it }
            .let { armedBurst(config, selection.crop, it) }
        return RawVideoResolvedSelection(selection, verdict)
    }

    /**
     * A risky window's burst estimate from the [armed] ring when that ring is armed for it, instead of the ring the
     * qualification used ([ringBytes]); [verdict] otherwise. Only the estimate changes, never the verdict, so arming a
     * smaller ring cannot refuse the window it was armed for.
     */
    private fun armedBurst(config: RawVideoConfig, crop: RawVideoCrop, verdict: RawVideoCustomVerdict): RawVideoCustomVerdict {
        val ring = armed?.takeIf { it.config == config && it.crop == crop }?.ringBytes ?: return verdict
        if (verdict !is RawVideoCustomVerdict.Risky || verdict.burstSeconds == null || config in usable) return verdict
        val burst = RawQualificationRisk.estimateBurstSeconds(config.streamBytesPerSecond, sweepMedianBytesPerSecond, ring) ?: return verdict
        return RawVideoCustomVerdict.Risky(burst)
    }

    /** The frame rates a window can be judged at ([rawVideoCustomRates]). */
    val customRates: List<Int> get() = rawVideoCustomRates(cameraId, sensorWidth, sensorHeight, usable, leftOut)
}

/**
 * PLAN-076: a configuration and window the gate selects; [custom] is the verdict of a window of the operator's (one that is
 * not a usable configuration at its centred origin), null for a usable one.
 */
internal data class RawVideoResolvedSelection(val selection: RawVideoSelection, val custom: RawVideoCustomVerdict? = null) {
    val config: RawVideoConfig get() = selection.config
    val crop: RawVideoCrop get() = selection.crop

    /** The canonical key: with the origin only when it is not centred. */
    val key: String get() = RawVideoConfigKeys.key(selection.config, selection.crop)

    val isCustom: Boolean get() = custom != null
}

/** [config] at its centred origin, as the gate selects a usable configuration. */
internal fun rawVideoCentredSelection(config: RawVideoConfig): RawVideoResolvedSelection =
    RawVideoResolvedSelection(RawVideoSelection(config, RawVideoCrop.centred(config)))

/**
 * PLAN-076, what a RAW_VIDEO session runs for the wanted [key]: the [usable] configuration it names (centred); else, with
 * a [custom] context, the window it names when [RawVideoCustomContext.judge] passes it or finds it risky; else what an
 * unknown key falls back to ([selectRawVideoConfig] with no key, which never picks [manualOnly] ones). Automatic (the
 * empty key) never yields a window of the operator's. Null when nothing can be selected.
 */
internal fun resolveRawVideoSelection(
    key: String,
    usable: List<RawVideoConfig>,
    manualOnly: Set<RawVideoConfig> = emptySet(),
    custom: RawVideoCustomContext? = null,
): RawVideoResolvedSelection? {
    usable.firstOrNull { RawVideoConfigKeys.key(it) == key }?.let { return rawVideoCentredSelection(it) }
    if (key.isNotEmpty()) {
        custom?.judge(key)?.takeIf { it.custom !is RawVideoCustomVerdict.Refused }?.let { return it }
    }
    return selectRawVideoConfig(usable, "", manualOnly)?.let(::rawVideoCentredSelection)
}

/**
 * PLAN-076: the frame rates of [cameraId] a window can be judged at on its [sensorWidth] x [sensorHeight] output, those
 * the camera is known to hold ([rawVideoCustomVerdict]): a [usable] configuration's, or one the storage alone left out
 * ([leftOut]). Ascending.
 */
internal fun rawVideoCustomRates(
    cameraId: String,
    sensorWidth: Int,
    sensorHeight: Int,
    usable: List<RawVideoConfig>,
    leftOut: List<RawVideoLeftOutConfig>,
): List<Int> {
    fun here(config: RawVideoConfig) =
        config.cameraId == cameraId && config.sensorWidth == sensorWidth && config.sensorHeight == sensorHeight
    return (usable.filter(::here).map { it.fps } +
        leftOut.filter { here(it.config) && it.status == RawQualificationItemStatus.STORAGE_LIMIT }.map { it.config.fps })
        .distinct().sorted()
}

/**
 * PLAN-076, the crop editor's read-out of [key] in [context]: its canonical key, its rates as an option of it shows them
 * ([RawVideoConfigOption.of]) and its verdict; a usable configuration at its centred origin reads its own record (risky,
 * reduced or passed). Null when [key] does not parse on this camera.
 */
internal fun rawVideoCropPreview(context: RawVideoCustomContext, key: String): RawVideoCropPreview? {
    val resolved = context.judge(key) ?: return null
    val config = resolved.config
    val verdict = resolved.custom
        ?: if (config in context.risky) RawVideoCustomVerdict.Risky(context.burstSeconds[config])
        else RawVideoCustomVerdict.Passed(config in context.reduced, context.reduced[config])
    val option = RawVideoConfigOption.of(config)
    return when (verdict) {
        is RawVideoCustomVerdict.Passed -> RawVideoCropPreview(resolved.key, option.bytesPerSecond, option.capacityBytesPerSecond,
            RawVideoCropStatus.PASSED, achievedFps = verdict.achievedFps.takeIf { verdict.reducedRate })
        is RawVideoCustomVerdict.Risky -> RawVideoCropPreview(resolved.key, option.bytesPerSecond, option.capacityBytesPerSecond,
            RawVideoCropStatus.RISKY, burstSeconds = verdict.burstSeconds)
        is RawVideoCustomVerdict.Refused -> RawVideoCropPreview(resolved.key, option.bytesPerSecond, option.capacityBytesPerSecond,
            RawVideoCropStatus.REFUSED, refusedReason = verdict.reason)
    }
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
 * passes reduced-rate configurations ([rawVideoReducedRates]) in [risky] as well. A rate above
 * [RAW_VIDEO_AUTOMATIC_MAX_FPS] (the small high-rate windows, PLAN-076) is only picked when nothing at
 * or below it is usable.
 */
internal fun selectRawVideoConfig(usable: List<RawVideoConfig>, key: String, risky: Set<RawVideoConfig> = emptySet()): RawVideoConfig? =
    usable.firstOrNull { RawVideoConfigKeys.key(it) == key } ?: usable.filterNot { it.isCompressed || it in risky }.maxWithOrNull(
        compareBy<RawVideoConfig> { !RawVideoConfigKeys.isCrop(it) }.thenBy { it.fps <= RAW_VIDEO_AUTOMATIC_MAX_FPS }
            .thenBy { it.fps }.thenBy { it.streamBytesPerSecond },
    )

/** The highest rate the automatic choice prefers ([selectRawVideoConfig]); faster ones are slow-motion windows chosen by hand. */
internal const val RAW_VIDEO_AUTOMATIC_MAX_FPS = 60

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

/** A record judged by a camera-only run, which never tested the storage ([RawQualificationRisk.REASON_STORAGE_NOT_TESTED]). */
internal fun rawVideoRecordStorageUntested(record: RawVideoQualificationRecord): Boolean =
    RawQualificationRisk.REASON_STORAGE_NOT_TESTED in record.qualification.reasonCodes

/**
 * The [risky] configurations whose newest record among [current] for [destinationId] ([rawVideoNewestRecord]) a
 * camera-only run left: their storage was never tested, so their warnings say so instead of a burst or a slow storage.
 */
internal fun rawVideoUntestedConfigs(
    risky: Collection<RawVideoConfig>,
    current: List<RawVideoQualificationRecord>,
    destinationId: String,
): Set<RawVideoConfig> =
    risky.filterTo(mutableSetOf()) { config -> rawVideoNewestRecord(current, destinationId, config)?.let(::rawVideoRecordStorageUntested) == true }

/**
 * The verdict [record] keeps in force, as a qualification row reads it ([rawQualificationRunStatus] on its effective
 * burst), with its measured burst and achieved rate: what the done screen shows for a configuration whose
 * storage-tested record a camera-only run left in place ([com.librestatic.opencinecam.capture.rawQualificationItemsInForce]).
 */
internal fun rawQualificationInForceOf(record: RawVideoQualificationRecord): com.librestatic.opencinecam.capture.RawQualificationInForce {
    val q = record.qualification
    return com.librestatic.opencinecam.capture.RawQualificationInForce(
        status = rawQualificationRunStatus(q.status.name, q.reasonCode, q.reasonCodes, RawQualificationRisk.effectiveBurstSeconds(record)),
        burstSeconds = record.burstSeconds,
        achievedFps = record.achievedFps,
        storageUntested = rawVideoRecordStorageUntested(record),
    )
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
