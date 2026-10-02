/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * OCC-PLAN-068 U6 sync marker. At the service-confirmed REC start the operator side may flash the
 * cover white and play a short beep through this phone's speaker. The trigger is observed from
 * service state on the Activity side, never from a subject touch, and the evidence reaches the
 * take sidecar through one optional binder call. Neither part can stop, delay or fail a take.
 */

internal const val SUBJECT_SYNC_MARKER_KEY = "subjectSyncMarker"
internal const val SUBJECT_SYNC_MARKER_SCHEMA_VERSION = 1
internal const val SUBJECT_SYNC_FLASH_MS = 100L
internal const val SUBJECT_SYNC_BEEP_HZ = 1000
internal const val SUBJECT_SYNC_BEEP_MS = 100
internal const val SUBJECT_SYNC_BEEP_SAMPLE_RATE = 48_000

/**
 * The service resets elapsed time to zero at REC start and the subject observes the RECORDING
 * phase change at once. An observation this late is a reconnection mid-take, not a start.
 */
internal const val SUBJECT_SYNC_MAX_START_ELAPSED_MS = 1_500L
private const val FAILURE_TEXT_LIMIT = 200

enum class SubjectSyncMarkerOutcome {
    /** The option was off for this take. */
    NOT_REQUESTED,

    /** Issued: the flash frame was requested on a visible cover, or the beep's playback started. */
    FIRED,

    /** Flash only: the cover window was not visible, so no white frame was requested. */
    NOT_SHOWN,

    /** The attempt failed and was logged; the take continued unaffected. */
    FAILED,
}

/** What the operator side issued for one take. Times are SystemClock.elapsedRealtimeNanos. */
data class SubjectSyncMarkerReport(
    val triggerElapsedRealtimeNanos: Long,
    val flash: SubjectSyncMarkerOutcome,
    val beep: SubjectSyncMarkerOutcome,
    val beepStartElapsedRealtimeNanos: Long? = null,
    val failure: String? = null,
) {
    init {
        require(triggerElapsedRealtimeNanos > 0)
        require(beep != SubjectSyncMarkerOutcome.NOT_SHOWN) { "NOT_SHOWN applies to the flash only" }
        require((beep == SubjectSyncMarkerOutcome.FIRED) == (beepStartElapsedRealtimeNanos != null))
        require(beepStartElapsedRealtimeNanos == null || beepStartElapsedRealtimeNanos >= triggerElapsedRealtimeNanos)
        require(failure == null || failure.length <= FAILURE_TEXT_LIMIT)
    }

    val fired: Boolean get() = flash == SubjectSyncMarkerOutcome.FIRED || beep == SubjectSyncMarkerOutcome.FIRED
}

/** A report admitted for a take, with the service's own REC-start time as the reference. */
data class SubjectSyncMarkerRecord(val report: SubjectSyncMarkerReport, val takeStartElapsedRealtimeNanos: Long) {
    init { require(takeStartElapsedRealtimeNanos in 1..report.triggerElapsedRealtimeNanos) }

    val offsetFromTakeStartUs: Long get() = (report.triggerElapsedRealtimeNanos - takeStartElapsedRealtimeNanos) / 1_000
    val beepOffsetFromTakeStartUs: Long? get() = report.beepStartElapsedRealtimeNanos?.let { (it - takeStartElapsedRealtimeNanos) / 1_000 }
}

internal fun boundedSyncFailure(text: String?): String? = text?.replace(Regex("\\p{Cntrl}"), " ")?.take(FAILURE_TEXT_LIMIT)

/**
 * Additive, optional sidecar node (docs/schemas/subject-sync-marker.schema.json). An absent node
 * means the marker was not armed for that take; older sidecars therefore parse unchanged.
 */
internal fun subjectSyncMarkerJson(record: SubjectSyncMarkerRecord): JsonObject = buildJsonObject {
    val report = record.report
    put("schemaVersion", SUBJECT_SYNC_MARKER_SCHEMA_VERSION)
    put("clock", "ELAPSED_REALTIME_NANOS")
    put("takeStartReference", "SERVICE_RECORDING_STARTED")
    put("takeStartElapsedRealtimeNanos", record.takeStartElapsedRealtimeNanos)
    put("triggerElapsedRealtimeNanos", report.triggerElapsedRealtimeNanos)
    put("offsetFromTakeStartUs", record.offsetFromTakeStartUs)
    put("markerFired", report.fired)
    put("flash", report.flash.name)
    put("flashDurationMs", SUBJECT_SYNC_FLASH_MS)
    put("beep", report.beep.name)
    put("beepStartElapsedRealtimeNanos", report.beepStartElapsedRealtimeNanos?.let { JsonPrimitive(it) } ?: JsonNull)
    put("beepOffsetFromTakeStartUs", record.beepOffsetFromTakeStartUs?.let { JsonPrimitive(it) } ?: JsonNull)
    put("beepFrequencyHz", SUBJECT_SYNC_BEEP_HZ)
    put("beepDurationMs", SUBJECT_SYNC_BEEP_MS)
    put("failure", report.failure?.let { JsonPrimitive(it) } ?: JsonNull)
    // Issue times on the operator side, not panel scan-out or speaker output; the decoded audio is authoritative.
    put("outputTimeVerified", false)
}

/** Reads the node; unknown additive keys are ignored, a different major version is rejected. */
internal fun parseSubjectSyncMarkerJson(value: JsonObject): SubjectSyncMarkerRecord? = try {
    fun scalar(key: String): JsonPrimitive = value[key] as? JsonPrimitive ?: error("Expected scalar sync field")
    fun long(key: String): Long = scalar(key).also { require(!it.isString) }.longOrNull ?: error("Expected integer sync field")
    fun optionalLong(key: String): Long? = scalar(key).let { if (it is JsonNull) null else long(key) }
    fun outcome(key: String): SubjectSyncMarkerOutcome = scalar(key).also { require(it.isString) }.content.let(SubjectSyncMarkerOutcome::valueOf)
    require(long("schemaVersion") == SUBJECT_SYNC_MARKER_SCHEMA_VERSION.toLong())
    require(scalar("clock").content == "ELAPSED_REALTIME_NANOS")
    val failure = scalar("failure").let { if (it is JsonNull) null else it.also { text -> require(text.isString) }.content }
    val record = SubjectSyncMarkerRecord(
        SubjectSyncMarkerReport(long("triggerElapsedRealtimeNanos"), outcome("flash"), outcome("beep"),
            optionalLong("beepStartElapsedRealtimeNanos"), failure),
        long("takeStartElapsedRealtimeNanos"),
    )
    require(scalar("markerFired").booleanOrNull == record.report.fired)
    record
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}

/** The marker of a whole take sidecar, or null when the take predates U6 or was not armed. */
internal fun subjectSyncMarkerFromSidecar(sidecar: JsonObject): SubjectSyncMarkerRecord? =
    (sidecar[SUBJECT_SYNC_MARKER_KEY] as? JsonObject)?.let(::parseSubjectSyncMarkerJson)

/**
 * Service-side holder for the active take. The first plausible report wins; a report from an
 * earlier take (issued before this take started) or one arriving after finalization is dropped.
 */
internal class SubjectSyncMarkerSlot {
    private var takeStartNs: Long? = null
    private var record: SubjectSyncMarkerRecord? = null

    @Synchronized fun begin(takeStartElapsedRealtimeNanos: Long) {
        require(takeStartElapsedRealtimeNanos > 0)
        takeStartNs = takeStartElapsedRealtimeNanos
        record = null
    }

    @Synchronized fun offer(report: SubjectSyncMarkerReport, nowElapsedRealtimeNanos: Long): Boolean {
        val start = takeStartNs ?: return false
        if (record != null) return false
        if (report.triggerElapsedRealtimeNanos !in start..nowElapsedRealtimeNanos) return false
        record = SubjectSyncMarkerRecord(report, start)
        return true
    }

    /** Ends the take: later reports are rejected until the next [begin]. */
    @Synchronized fun consume(): SubjectSyncMarkerRecord? = record.also {
        record = null
        takeStartNs = null
    }
}

/** Options and visibility at the moment REC is confirmed. */
internal data class SubjectSyncArming(
    val flash: Boolean,
    val beep: Boolean,
    /** SLATE is the subject mode of an active presentation session. */
    val slatePresented: Boolean,
    val coverVisible: Boolean,
)

internal data class SubjectSyncDecision(val flash: SubjectSyncMarkerOutcome, val beep: Boolean)

/**
 * Fires at most once per take, only on an observed transition into a service-confirmed
 * RECORDING phase (not finalizing, not a countdown, not a reconnection in the middle of a take).
 */
internal class SubjectSyncMarkerPolicy {
    private var previousRecording: Boolean? = null

    fun observe(phase: CameraUiPhase, finalizing: Boolean, elapsedMs: Long, arming: SubjectSyncArming): SubjectSyncDecision? {
        val recording = phase == CameraUiPhase.RECORDING
        val previous = previousRecording
        previousRecording = recording
        if (!recording || previous != false) return null
        if (finalizing || elapsedMs > SUBJECT_SYNC_MAX_START_ELAPSED_MS) return null
        if (!arming.slatePresented || (!arming.flash && !arming.beep)) return null
        val flash = when {
            !arming.flash -> SubjectSyncMarkerOutcome.NOT_REQUESTED
            arming.coverVisible -> SubjectSyncMarkerOutcome.FIRED
            else -> SubjectSyncMarkerOutcome.NOT_SHOWN
        }
        return SubjectSyncDecision(flash, arming.beep)
    }
}

/** Mono PCM16 sine with 1 ms linear edges: a sharp, click-free onset for audio alignment. */
internal fun subjectSyncBeepPcm(
    sampleRate: Int = SUBJECT_SYNC_BEEP_SAMPLE_RATE,
    frequencyHz: Int = SUBJECT_SYNC_BEEP_HZ,
    durationMs: Int = SUBJECT_SYNC_BEEP_MS,
    amplitude: Float = 0.5f,
): ShortArray {
    require(sampleRate > 0 && frequencyHz in 1 until sampleRate / 2 && durationMs > 0 && amplitude in 0f..1f)
    val count = sampleRate * durationMs / 1000
    val edge = (sampleRate / 1000).coerceAtMost(count / 2).coerceAtLeast(1)
    return ShortArray(count) { index ->
        val envelope = minOf(1f, index.toFloat() / edge, (count - 1 - index).toFloat() / edge)
        (sin(2.0 * PI * frequencyHz * index / sampleRate) * amplitude * envelope * Short.MAX_VALUE).roundToInt().toShort()
    }
}
