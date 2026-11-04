/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class RawThroughputSample(
    val durationMs: Long,
    val bytesWritten: Long,
    val droppedFrames: Int,
) {
    init {
        require(durationMs > 0 && bytesWritten >= 0 && droppedFrames >= 0) {
            "throughput sample values are invalid"
        }
    }

    val bytesPerSecond: Long
        get() = bytesWritten * 1000L / durationMs
}

enum class RawBenchmarkStatus {
    PASS,
    FAIL,
    UNKNOWN,
}

data class RawThroughputQualification(
    val status: RawBenchmarkStatus,
    val windowSeconds: Long,
    val streamBytesPerSecond: Long,
    val p01BytesPerSecond: Long?,
    val requiredBytesPerSecond: Long?,
    val droppedFrames: Int,
    val reasonCode: String,
)

object RawThroughputBenchmark {
    /**
     * The qualification protocol records are measured under; records from another version are stale.
     * Version 3 is the staged qualification (PLAN-071 decision 10): only the heaviest configurations
     * get a long disk run, lighter ones are extrapolated from a passing one plus a RAM-only camera
     * check, and compressed twins are judged from one encode benchmark, so a record's verdict means something else
     * than a version 2 record's and every older record must be measured again.
     */
    const val PROTOCOL_VERSION = 3

    /** A storage sweep needs at least this many one-second windows (60 s). */
    const val SWEEP_MIN_WINDOWS = 60

    /** Rule v1 ([qualifyWithoutBuffer]) needs a p01 write rate of 1.25x the stream rate. */
    const val MARGIN_PERCENT = 125L

    /**
     * Rule v2 ([qualifyWithBuffer]) needs a sweep median of 1.1225x the stream rate, in basis
     * points so the check stays in integer maths. Lowering it from 1.25x keeps records valid: any
     * median that met 1.25x also meets 1.1225x, so that change alone did not bump [PROTOCOL_VERSION].
     */
    const val MARGIN_BASIS_POINTS = 11_225L

    /** Neither the simulated backlog nor the live ring peak may pass half the ring. */
    const val RING_LIMIT_PERCENT = 50L

    /** The live run must last at least 20 s. */
    const val LIVE_MIN_DURATION_MS = 20_000L

    /** Rule v1, kept for existing callers: see [qualifyWithoutBuffer]. */
    fun qualify(
        windowSeconds: Long,
        streamBytesPerSecond: Long?,
        samples: List<RawThroughputSample>,
    ): RawThroughputQualification = qualifyWithoutBuffer(windowSeconds, streamBytesPerSecond, samples)

    /**
     * Rule v1: a window of at least 60 s, zero drops and a p01 write rate of at least 1.25x the
     * stream. It assumes no RAM buffer, so it is kept for comparison and evidence only.
     */
    fun qualifyWithoutBuffer(
        windowSeconds: Long,
        streamBytesPerSecond: Long?,
        samples: List<RawThroughputSample>,
    ): RawThroughputQualification {
        if (streamBytesPerSecond == null || samples.isEmpty()) {
            return RawThroughputQualification(
                RawBenchmarkStatus.UNKNOWN, windowSeconds, streamBytesPerSecond ?: 0,
                null, null, samples.sumOf { it.droppedFrames }, "benchmark-evidence-unknown",
            )
        }
        if (windowSeconds < 60 || streamBytesPerSecond <= 0) {
            return RawThroughputQualification(
                RawBenchmarkStatus.FAIL, windowSeconds, streamBytesPerSecond,
                null, null, samples.sumOf { it.droppedFrames }, "sustained-window-invalid",
            )
        }
        val p01 = percentile01(samples.map(RawThroughputSample::bytesPerSecond).sorted())
        val required = streamBytesPerSecond * MARGIN_PERCENT / 100L
        val dropped = samples.sumOf { it.droppedFrames }
        val pass = dropped == 0 && p01 >= required
        return RawThroughputQualification(
            if (pass) RawBenchmarkStatus.PASS else RawBenchmarkStatus.FAIL,
            windowSeconds,
            streamBytesPerSecond,
            p01,
            required,
            dropped,
            if (pass) "raw-throughput-pass" else if (dropped > 0) "raw-frame-loss" else "raw-throughput-margin-fail",
        )
    }

    /**
     * A fluid model of the ring: each sweep window adds the stream's bytes for its duration and
     * drains what the storage wrote in it, so `backlog = max(0, backlog + stream - capability)`.
     * It runs the windows in their measured order, and again sorted ascending, the worst case where
     * every dip comes back to back. The backlog is not capped at the ring, so it shows how far an
     * overflow would go.
     */
    fun simulateBacklog(
        streamBytesPerSecond: Long,
        ringBytes: Long,
        sweepSamples: List<RawThroughputSample>,
        compressionRatio: Double = 1.0,
    ): BacklogSimulation {
        require(streamBytesPerSecond >= 0) { "stream rate must not be negative" }
        require(ringBytes > 0) { "ring size must be positive" }
        require(compressionRatio >= 1.0) { "compression ratio must be at least 1" }
        val inOrder = maxBacklog(streamBytesPerSecond, sweepSamples, compressionRatio)
        val worst = maxBacklog(streamBytesPerSecond, sweepSamples.sortedBy(RawThroughputSample::bytesPerSecond), compressionRatio)
        return BacklogSimulation(
            ringBytes = ringBytes,
            maxBacklogBytes = inOrder,
            maxBacklogFraction = inOrder.toDouble() / ringBytes,
            overflowed = inOrder > ringBytes,
            worstCaseMaxBacklogBytes = worst,
            worstCaseMaxBacklogFraction = worst.toDouble() / ringBytes,
            worstCaseOverflowed = worst > ringBytes,
        )
    }

    /**
     * Rule v2 (PLAN-071 decision 9). A configuration passes only when (a) the storage sweep has at
     * least 60 windows and a median of at least 1.1225x ([MARGIN_BASIS_POINTS]) the stream, (b) the in-order simulated backlog
     * stays within half the ring (the sorted worst case is evidence only), and (c) a live run at
     * the same configuration lasted at least 20 s with every completed capture written, no overflow
     * and a ring peak within half its ring. Every failing check adds its reason code; a missing live
     * run alone leaves the result [RawBenchmarkStatus.UNKNOWN].
     *
     * [compressionRatio] above 1 judges a losslessly compressed configuration whose frames reach the
     * storage [compressionRatio] times smaller: the ring still fills with RAW10 frames at the stream
     * rate, but drains [compressionRatio] times faster, so (a) needs a median of 1.1225x the stream
     * divided by the ratio and (b) drains each window's bytes times the ratio. The stored stream
     * rate, median and ring stay the measured ones.
     */
    fun qualifyWithBuffer(
        config: RawVideoConfig,
        ringBytes: Long,
        sweep: RawStorageSweep,
        live: RawLiveRun?,
        compressionRatio: Double = 1.0,
    ): RawVideoQualification {
        require(ringBytes > 0) { "ring size must be positive" }
        require(compressionRatio >= 1.0) { "compression ratio must be at least 1" }
        val stream = config.streamBytesPerSecond
        val requiredMedian = if (compressionRatio == 1.0) stream * MARGIN_BASIS_POINTS / 10_000L
        else kotlin.math.ceil(stream * (MARGIN_BASIS_POINTS / 10_000.0) / compressionRatio).toLong()
        val ringLimit = ringBytes * RING_LIMIT_PERCENT / 100L
        val reasons = mutableListOf<String>()

        val median = sweep.medianBytesPerSecond
        val p01 = sweep.p01BytesPerSecond
        if (sweep.windowCount < SWEEP_MIN_WINDOWS || sweep.totalDurationMs < SWEEP_MIN_WINDOWS * 1_000L) {
            reasons += REASON_SWEEP_WINDOW_INVALID
        }
        if (median == null || median < requiredMedian) reasons += REASON_SWEEP_MEDIAN_MARGIN_FAIL

        val backlog = if (sweep.samples.isEmpty()) null else simulateBacklog(stream, ringBytes, sweep.samples, compressionRatio)
        if (backlog != null && backlog.maxBacklogBytes > ringLimit) reasons += REASON_BUFFER_BACKLOG_EXCEEDED

        val livePeakFraction = live?.let { it.peakRingBytes.toDouble() / it.ringBytes }
        if (live == null) {
            reasons += REASON_LIVE_RUN_MISSING
        } else {
            if (live.config != config) reasons += REASON_LIVE_RUN_CONFIG_MISMATCH
            if (live.durationMs < LIVE_MIN_DURATION_MS) reasons += REASON_LIVE_RUN_TOO_SHORT
            if (live.framesWritten != live.completedCaptures) reasons += REASON_RAW_FRAME_LOSS
            if (live.ringOverflow) reasons += REASON_RING_OVERFLOW
            if (live.peakRingBytes > live.ringBytes * RING_LIMIT_PERCENT / 100L) reasons += REASON_RING_PEAK_EXCEEDED
        }

        val status = when {
            reasons.isEmpty() -> RawBenchmarkStatus.PASS
            reasons == listOf(REASON_LIVE_RUN_MISSING) -> RawBenchmarkStatus.UNKNOWN
            else -> RawBenchmarkStatus.FAIL
        }
        val codes = if (reasons.isEmpty()) listOf(REASON_PASS) else reasons.toList()
        return RawVideoQualification(
            status = status,
            reasonCode = codes.first(),
            reasonCodes = codes,
            streamBytesPerSecond = stream,
            requiredMedianBytesPerSecond = requiredMedian,
            sweepWindows = sweep.windowCount,
            sweepMedianBytesPerSecond = median,
            sweepP01BytesPerSecond = p01,
            sweepMinBytesPerSecond = sweep.minBytesPerSecond,
            medianMarginRatio = median?.let { ratio(it, stream)?.times(compressionRatio) },
            p01MarginRatio = p01?.let { ratio(it, stream)?.times(compressionRatio) },
            ringBytes = ringBytes,
            backlogLimitBytes = ringLimit,
            backlog = backlog,
            live = live,
            livePeakFraction = livePeakFraction,
        )
    }

    const val REASON_PASS = "raw-video-pass"
    const val REASON_SWEEP_WINDOW_INVALID = "sweep-window-invalid"
    const val REASON_SWEEP_MEDIAN_MARGIN_FAIL = "sweep-median-margin-fail"
    const val REASON_BUFFER_BACKLOG_EXCEEDED = "buffer-backlog-exceeded"
    const val REASON_LIVE_RUN_MISSING = "live-run-missing"
    const val REASON_LIVE_RUN_CONFIG_MISMATCH = "live-run-config-mismatch"
    const val REASON_LIVE_RUN_TOO_SHORT = "live-run-too-short"
    const val REASON_RAW_FRAME_LOSS = "raw-frame-loss"
    const val REASON_RING_OVERFLOW = "ring-overflow"
    const val REASON_RING_PEAK_EXCEEDED = "ring-peak-exceeded"

    internal fun percentile01(sortedRates: List<Long>): Long = sortedRates[((sortedRates.size - 1) * 0.01).toInt()]

    private fun ratio(rate: Long, stream: Long): Double? = if (stream > 0) rate.toDouble() / stream else null

    private fun maxBacklog(streamBytesPerSecond: Long, samples: List<RawThroughputSample>, compressionRatio: Double): Long {
        var backlog = 0L
        var max = 0L
        for (sample in samples) {
            val arriving = streamBytesPerSecond * sample.durationMs / 1_000L
            val drained = if (compressionRatio == 1.0) sample.bytesWritten else (sample.bytesWritten * compressionRatio).toLong()
            backlog = maxOf(0L, backlog + arriving - drained)
            max = maxOf(max, backlog)
        }
        return max
    }
}

/**
 * One RAW video configuration. A crop narrows only what is written (the sensor still reads out
 * full size), so the stream rate follows the crop. RAW10 packs 4 pixels in 5 bytes.
 */
data class RawVideoConfig(
    val cameraId: String,
    val sensorWidth: Int,
    val sensorHeight: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val fps: Int,
    val encoding: String = ENCODING_RAW10,
) {
    init {
        require(cameraId.isNotBlank()) { "camera ID must not be blank" }
        require(sensorWidth > 0 && sensorHeight > 0) { "sensor size must be positive" }
        require(cropWidth in 1..sensorWidth && cropHeight in 1..sensorHeight) { "crop must fit the sensor" }
        require(fps > 0) { "fps must be positive" }
        require(encoding.isNotBlank()) { "encoding must not be blank" }
    }

    val bytesPerFrame: Long
        get() = cropWidth.toLong() * cropHeight * 5L / 4L

    /**
     * The uncompressed RAW10 rate. It is also the rate a compressed configuration ([ENCODING_BLOCK_PACK],
     * [ENCODING_LJ92]) is qualified and shown at: compression is never counted on for throughput
     * (PLAN-071 decision 1).
     */
    val streamBytesPerSecond: Long
        get() = bytesPerFrame * fps

    /** True when frames are written losslessly compressed ([ENCODING_BLOCK_PACK] or [ENCODING_LJ92]). */
    val isCompressed: Boolean
        get() = encoding != ENCODING_RAW10

    companion object {
        const val ENCODING_RAW10 = "raw10"

        /**
         * The Bayer block-pack codec (OCRAW frame encoding 2), the opt-in "Save space" variant of a
         * RAW10 configuration the app qualifies and records.
         */
        const val ENCODING_BLOCK_PACK = "bp"

        /**
         * Lossless JPEG (OCRAW frame encoding 1). Still recorded when a configuration names it (records
         * qualified before block-pack, explicit `+lj92` device-test specs); no longer offered by the plan.
         */
        const val ENCODING_LJ92 = "lj92"

        /** The compressed encodings the recorder can arm, block-pack first. */
        val COMPRESSED_ENCODINGS: List<String> = listOf(ENCODING_BLOCK_PACK, ENCODING_LJ92)
    }
}

/**
 * An unthrottled write to one destination, one sample per window of about 1 s; drops are ignored.
 * [samples] are the burst windows rule v2 reads (the first [RawThroughputBenchmark.SWEEP_MIN_WINDOWS]);
 * [sustained] is what the windows written after them found about the write cache ([RawStorageCliff]),
 * null when the sweep had no sustained phase.
 */
data class RawStorageSweep(
    val destinationId: String,
    val samples: List<RawThroughputSample>,
    val sustained: RawSustainedWrite? = null,
) {
    val windowCount: Int
        get() = samples.size

    val totalDurationMs: Long
        get() = samples.sumOf { it.durationMs }

    private val sortedRates: List<Long> by lazy { samples.map(RawThroughputSample::bytesPerSecond).sorted() }

    /** The median window rate; with an even count, the mean of the two middle windows. */
    val medianBytesPerSecond: Long?
        get() = sortedRates.takeIf { it.isNotEmpty() }?.let { rates ->
            val middle = rates.size / 2
            if (rates.size % 2 == 1) rates[middle] else (rates[middle - 1] + rates[middle]) / 2
        }

    val p01BytesPerSecond: Long?
        get() = sortedRates.takeIf { it.isNotEmpty() }?.let(RawThroughputBenchmark::percentile01)

    val minBytesPerSecond: Long?
        get() = sortedRates.firstOrNull()
}

/** The real camera, ring and writer at one configuration. */
data class RawLiveRun(
    val config: RawVideoConfig,
    val durationMs: Long,
    val completedCaptures: Long,
    val framesWritten: Long,
    val ringOverflow: Boolean,
    val peakRingBytes: Long,
    val ringBytes: Long,
    val maxWriteMs: Double?,
) {
    init {
        require(durationMs >= 0 && completedCaptures >= 0 && framesWritten >= 0) { "live run counts must not be negative" }
        require(ringBytes > 0 && peakRingBytes >= 0) { "live run ring values are invalid" }
    }
}

/** The ring backlog of [RawThroughputBenchmark.simulateBacklog], in measured order and sorted worst case. */
data class BacklogSimulation(
    val ringBytes: Long,
    val maxBacklogBytes: Long,
    val maxBacklogFraction: Double,
    val overflowed: Boolean,
    val worstCaseMaxBacklogBytes: Long,
    val worstCaseMaxBacklogFraction: Double,
    val worstCaseOverflowed: Boolean,
)

/** Rule v2's verdict with every number it used, so a record is its own evidence. */
data class RawVideoQualification(
    val status: RawBenchmarkStatus,
    val reasonCode: String,
    val reasonCodes: List<String>,
    val streamBytesPerSecond: Long,
    val requiredMedianBytesPerSecond: Long,
    val sweepWindows: Int,
    val sweepMedianBytesPerSecond: Long?,
    val sweepP01BytesPerSecond: Long?,
    val sweepMinBytesPerSecond: Long?,
    val medianMarginRatio: Double?,
    val p01MarginRatio: Double?,
    val ringBytes: Long,
    val backlogLimitBytes: Long,
    val backlog: BacklogSimulation?,
    val live: RawLiveRun?,
    val livePeakFraction: Double?,
)

/**
 * One qualification, tied to the build, protocol and destination it was measured on. [method] says
 * how it was judged (`long-run`, `extrapolated` from the configuration [coveredBy] names, or
 * `compressed-extrapolated` from the encode benchmark, `lj92-extrapolated` on older records); [evidence] is the JSON text of what decided it
 * (the camera check, the benchmark figures). All three are null on records written before them.
 * [burstSeconds] is set on a risky record only: how long a take writes before it drops frames once
 * the storage's write cache is full ([RawStorageCliff.burstSeconds]); null when the cache does not
 * limit it, when no cliff was seen, and on records written before it. [achievedFps] is the rate the
 * camera delivered, to a tenth (the RAM camera check when one ran, else the long run); a record
 * whose rate is under [RawVideoConfig.fps] passed at a reduced rate (PLAN-071). Null on records
 * written before it, which read as reaching their target.
 */
data class RawVideoQualificationRecord(
    val fingerprint: String,
    val protocolVersion: Int = RawThroughputBenchmark.PROTOCOL_VERSION,
    val destinationId: String,
    val config: RawVideoConfig,
    val ringBytes: Long,
    val qualification: RawVideoQualification,
    val measuredAtEpochMs: Long,
    val method: String? = null,
    val coveredBy: String? = null,
    val evidence: String? = null,
    val burstSeconds: Long? = null,
    val achievedFps: Double? = null,
)

/**
 * The stored records. Lookups ignore stale ones (another fingerprint or protocol version), and for
 * each configuration only the newest record counts, so a later failure revokes an earlier pass.
 */
class RawVideoQualificationSet(val records: List<RawVideoQualificationRecord> = emptyList()) {
    fun current(fingerprint: String, protocolVersion: Int = RawThroughputBenchmark.PROTOCOL_VERSION): List<RawVideoQualificationRecord> =
        records.filter { it.fingerprint == fingerprint && it.protocolVersion == protocolVersion }

    fun qualifiedConfigs(
        fingerprint: String,
        protocolVersion: Int = RawThroughputBenchmark.PROTOCOL_VERSION,
        destinationId: String,
        cameraId: String,
    ): List<RawVideoConfig> = current(fingerprint, protocolVersion)
        .filter { it.destinationId == destinationId && it.config.cameraId == cameraId }
        .groupBy { it.config }
        .mapNotNull { (config, entries) ->
            config.takeIf { entries.maxBy(RawVideoQualificationRecord::measuredAtEpochMs).qualification.status == RawBenchmarkStatus.PASS }
        }

    /** Adds [record], replacing any record for the same build, protocol, destination and configuration. */
    fun upsert(record: RawVideoQualificationRecord): RawVideoQualificationSet = RawVideoQualificationSet(
        records.filterNot {
            it.fingerprint == record.fingerprint && it.protocolVersion == record.protocolVersion &&
                it.destinationId == record.destinationId && it.config == record.config
        } + record,
    )

    /** Drops every record that is stale for this build and protocol. */
    fun pruned(fingerprint: String, protocolVersion: Int = RawThroughputBenchmark.PROTOCOL_VERSION): RawVideoQualificationSet =
        RawVideoQualificationSet(current(fingerprint, protocolVersion))
}

enum class RawBenchmarkCommandStatus {
    STARTED,
    DUPLICATE,
    CANCELLED,
    CLOSED,
}

class RawBenchmarkSession(private val maxRuns: Int = 4) {
    private val results = LinkedHashMap<String, RawThroughputQualification>()
    private val cancelled = HashSet<String>()
    private var closed = false

    init {
        require(maxRuns > 0) { "maxRuns must be positive" }
    }

    fun run(
        runId: String,
        windowSeconds: Long,
        streamBytesPerSecond: Long?,
        samples: List<RawThroughputSample>,
    ): RawBenchmarkCommandStatus {
        if (closed) return RawBenchmarkCommandStatus.CLOSED
        if (cancelled.contains(runId)) return RawBenchmarkCommandStatus.CANCELLED
        if (runId.isBlank() || results.containsKey(runId) || results.size >= maxRuns) {
            return if (results.containsKey(runId)) RawBenchmarkCommandStatus.DUPLICATE
            else RawBenchmarkCommandStatus.CLOSED
        }
        results[runId] = RawThroughputBenchmark.qualify(windowSeconds, streamBytesPerSecond, samples)
        return RawBenchmarkCommandStatus.STARTED
    }

    fun cancel(runId: String): RawBenchmarkCommandStatus {
        if (closed) return RawBenchmarkCommandStatus.CLOSED
        if (runId.isBlank() || results.containsKey(runId)) return RawBenchmarkCommandStatus.CLOSED
        cancelled += runId
        return RawBenchmarkCommandStatus.CANCELLED
    }

    fun result(runId: String): RawThroughputQualification? = results[runId]

    fun close() {
        results.clear()
        cancelled.clear()
        closed = true
    }
}

/**
 * A stable JSON form of [RawVideoQualificationSet] for the app's qualification store. Records that
 * do not parse are skipped, so one damaged entry never hides the others.
 */
object RawVideoQualificationCodec {
    const val SCHEMA = "opencinecam.raw-video-qualifications"
    const val SCHEMA_VERSION = 1

    fun encode(set: RawVideoQualificationSet): String = buildJsonObject {
        put("schema", SCHEMA)
        put("schemaVersion", SCHEMA_VERSION)
        put("records", JsonArray(set.records.map(::encodeRecord)))
    }.toString()

    fun decode(raw: String): RawVideoQualificationSet {
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return RawVideoQualificationSet()
        if (root["schema"]?.jsonPrimitive?.contentOrNull != SCHEMA) return RawVideoQualificationSet()
        if (root["schemaVersion"]?.jsonPrimitive?.intOrNull != SCHEMA_VERSION) return RawVideoQualificationSet()
        val records = (root["records"] as? JsonArray).orEmpty().mapNotNull { element ->
            runCatching { decodeRecord(element.jsonObject) }.getOrNull()
        }
        return RawVideoQualificationSet(records)
    }

    fun encodeRecord(record: RawVideoQualificationRecord): JsonObject = buildJsonObject {
        put("fingerprint", record.fingerprint)
        put("protocolVersion", record.protocolVersion)
        put("destinationId", record.destinationId)
        put("config", encodeConfig(record.config))
        put("ringBytes", record.ringBytes)
        put("measuredAtEpochMs", record.measuredAtEpochMs)
        put("qualification", encodeQualification(record.qualification))
        record.method?.let { put("method", it) }
        record.coveredBy?.let { put("coveredBy", it) }
        record.evidence?.let { put("evidence", it) }
        record.burstSeconds?.let { put("burstSeconds", it) }
        record.achievedFps?.let { put("achievedFps", it) }
    }

    fun decodeRecord(json: JsonObject): RawVideoQualificationRecord = RawVideoQualificationRecord(
        fingerprint = json.string("fingerprint"),
        protocolVersion = json.int("protocolVersion"),
        destinationId = json.string("destinationId"),
        config = decodeConfig(json.obj("config")),
        ringBytes = json.long("ringBytes"),
        qualification = decodeQualification(json.obj("qualification")),
        measuredAtEpochMs = json.long("measuredAtEpochMs"),
        method = json.stringOrNull("method"),
        coveredBy = json.stringOrNull("coveredBy"),
        evidence = json.stringOrNull("evidence"),
        burstSeconds = json.longOrNull("burstSeconds"),
        achievedFps = json.doubleOrNull("achievedFps"),
    )

    private fun encodeConfig(config: RawVideoConfig) = buildJsonObject {
        put("cameraId", config.cameraId)
        put("sensorWidth", config.sensorWidth)
        put("sensorHeight", config.sensorHeight)
        put("cropWidth", config.cropWidth)
        put("cropHeight", config.cropHeight)
        put("fps", config.fps)
        put("encoding", config.encoding)
    }

    private fun decodeConfig(json: JsonObject) = RawVideoConfig(
        cameraId = json.string("cameraId"),
        sensorWidth = json.int("sensorWidth"),
        sensorHeight = json.int("sensorHeight"),
        cropWidth = json.int("cropWidth"),
        cropHeight = json.int("cropHeight"),
        fps = json.int("fps"),
        encoding = json.string("encoding"),
    )

    private fun encodeQualification(q: RawVideoQualification) = buildJsonObject {
        put("status", q.status.name)
        put("reasonCode", q.reasonCode)
        put("reasonCodes", JsonArray(q.reasonCodes.map(::JsonPrimitive)))
        put("streamBytesPerSecond", q.streamBytesPerSecond)
        put("requiredMedianBytesPerSecond", q.requiredMedianBytesPerSecond)
        put("sweepWindows", q.sweepWindows)
        put("sweepMedianBytesPerSecond", q.sweepMedianBytesPerSecond)
        put("sweepP01BytesPerSecond", q.sweepP01BytesPerSecond)
        put("sweepMinBytesPerSecond", q.sweepMinBytesPerSecond)
        put("medianMarginRatio", q.medianMarginRatio)
        put("p01MarginRatio", q.p01MarginRatio)
        put("ringBytes", q.ringBytes)
        put("backlogLimitBytes", q.backlogLimitBytes)
        q.backlog?.let { backlog ->
            put(
                "backlog",
                buildJsonObject {
                    put("ringBytes", backlog.ringBytes)
                    put("maxBacklogBytes", backlog.maxBacklogBytes)
                    put("maxBacklogFraction", backlog.maxBacklogFraction)
                    put("overflowed", backlog.overflowed)
                    put("worstCaseMaxBacklogBytes", backlog.worstCaseMaxBacklogBytes)
                    put("worstCaseMaxBacklogFraction", backlog.worstCaseMaxBacklogFraction)
                    put("worstCaseOverflowed", backlog.worstCaseOverflowed)
                },
            )
        }
        q.live?.let { live ->
            put(
                "live",
                buildJsonObject {
                    put("config", encodeConfig(live.config))
                    put("durationMs", live.durationMs)
                    put("completedCaptures", live.completedCaptures)
                    put("framesWritten", live.framesWritten)
                    put("ringOverflow", live.ringOverflow)
                    put("peakRingBytes", live.peakRingBytes)
                    put("ringBytes", live.ringBytes)
                    put("maxWriteMs", live.maxWriteMs)
                },
            )
        }
        put("livePeakFraction", q.livePeakFraction)
    }

    private fun decodeQualification(json: JsonObject) = RawVideoQualification(
        status = RawBenchmarkStatus.valueOf(json.string("status")),
        reasonCode = json.string("reasonCode"),
        reasonCodes = json.getValue("reasonCodes").jsonArray.map { it.jsonPrimitive.content },
        streamBytesPerSecond = json.long("streamBytesPerSecond"),
        requiredMedianBytesPerSecond = json.long("requiredMedianBytesPerSecond"),
        sweepWindows = json.int("sweepWindows"),
        sweepMedianBytesPerSecond = json.longOrNull("sweepMedianBytesPerSecond"),
        sweepP01BytesPerSecond = json.longOrNull("sweepP01BytesPerSecond"),
        sweepMinBytesPerSecond = json.longOrNull("sweepMinBytesPerSecond"),
        medianMarginRatio = json.doubleOrNull("medianMarginRatio"),
        p01MarginRatio = json.doubleOrNull("p01MarginRatio"),
        ringBytes = json.long("ringBytes"),
        backlogLimitBytes = json.long("backlogLimitBytes"),
        backlog = (json["backlog"] as? JsonObject)?.let { backlog ->
            BacklogSimulation(
                ringBytes = backlog.long("ringBytes"),
                maxBacklogBytes = backlog.long("maxBacklogBytes"),
                maxBacklogFraction = backlog.double("maxBacklogFraction"),
                overflowed = backlog.boolean("overflowed"),
                worstCaseMaxBacklogBytes = backlog.long("worstCaseMaxBacklogBytes"),
                worstCaseMaxBacklogFraction = backlog.double("worstCaseMaxBacklogFraction"),
                worstCaseOverflowed = backlog.boolean("worstCaseOverflowed"),
            )
        },
        live = (json["live"] as? JsonObject)?.let { live ->
            RawLiveRun(
                config = decodeConfig(live.obj("config")),
                durationMs = live.long("durationMs"),
                completedCaptures = live.long("completedCaptures"),
                framesWritten = live.long("framesWritten"),
                ringOverflow = live.boolean("ringOverflow"),
                peakRingBytes = live.long("peakRingBytes"),
                ringBytes = live.long("ringBytes"),
                maxWriteMs = live.doubleOrNull("maxWriteMs"),
            )
        },
        livePeakFraction = json.doubleOrNull("livePeakFraction"),
    )

    private fun JsonObject.primitive(key: String): JsonPrimitive = getValue(key).jsonPrimitive
    private fun JsonObject.nullablePrimitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }
    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
    private fun JsonObject.string(key: String): String = primitive(key).also { require(it.isString) { "$key must be a string" } }.content
    private fun JsonObject.int(key: String): Int = primitive(key).int
    private fun JsonObject.long(key: String): Long = primitive(key).long
    private fun JsonObject.double(key: String): Double = primitive(key).double
    private fun JsonObject.boolean(key: String): Boolean = primitive(key).boolean
    private fun JsonObject.longOrNull(key: String): Long? = nullablePrimitive(key)?.long
    private fun JsonObject.stringOrNull(key: String): String? = nullablePrimitive(key)?.takeIf { it.isString }?.content
    private fun JsonObject.doubleOrNull(key: String): Double? = nullablePrimitive(key)?.double
}
