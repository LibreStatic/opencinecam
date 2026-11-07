/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.rawvideo.RawQualificationFrameRate
import com.librestatic.opencinecam.rawvideo.RawQualificationPhase
import com.librestatic.opencinecam.rawvideo.RawQualificationProgress
import com.librestatic.opencinecam.rawvideo.RawQualificationRisk
import com.librestatic.opencinecam.rawvideo.RawQualificationSpaceEstimate
import com.librestatic.opencinecam.rawvideo.RawQualificationStaging
import com.librestatic.opencinecam.rawvideo.RawQualificationStatus
import com.librestatic.opencinecam.rawvideo.RawVideoQualifier
import kotlin.math.roundToLong

/*
 * OCC-PLAN-071: how the app leads an operator into the RAW video qualification. Choosing RAW video
 * on a camera that is not qualified yet (gate CANDIDATE) asks first; accepting opens the
 * qualification step, which checks the battery and the temperature before it starts.
 * The onboarding wizard asks the same question as its last step. Everything here is pure.
 */

/** What choosing a mode in a selector does. */
internal enum class ModeChoiceAction { SELECT, QUALIFY_RAW_VIDEO, REJECT }

/**
 * A selectable mode is selected; RAW video on a CANDIDATE camera asks whether to qualify it and
 * leaves the mode as it was; anything else is refused. [CameraUiState.isModeSelectable] stays the
 * rule for selecting.
 */
internal fun modeChoiceAction(mode: CaptureMode, gate: ModeGateState?): ModeChoiceAction = when {
    CameraUiState.isModeSelectable(gate) -> ModeChoiceAction.SELECT
    mode == CaptureMode.RAW_VIDEO && gate == ModeGateState.CANDIDATE -> ModeChoiceAction.QUALIFY_RAW_VIDEO
    else -> ModeChoiceAction.REJECT
}

/** Whether a selector offers [mode] as a live choice (selectable, or one question away from it). */
internal fun isModeChoosable(mode: CaptureMode, gate: ModeGateState?): Boolean = modeChoiceAction(mode, gate) != ModeChoiceAction.REJECT

/**
 * Battery at least this to start: below it most phones cut the maximum current they deliver, which
 * would skew a qualification that runs the camera and storage hard for minutes.
 */
internal const val RAW_QUALIFICATION_MIN_BATTERY_PERCENT = 5

/**
 * Below this (and at or above [RAW_QUALIFICATION_MIN_BATTERY_PERCENT]) the step warns that the
 * qualification may drain the battery, without blocking the start.
 */
internal const val RAW_QUALIFICATION_WARN_BATTERY_PERCENT = 50

/** A running qualification stops below this. */
internal const val RAW_QUALIFICATION_ABORT_BATTERY_PERCENT = 5

/** [PowerManager.THERMAL_STATUS_LIGHT]: none or light is cool enough to start. */
internal const val RAW_QUALIFICATION_MAX_THERMAL_STATUS = 1

/** Why the qualification cannot start now. */
internal enum class RawQualificationBlocker { BATTERY_LOW, TOO_WARM }

/** The battery and the temperature the qualification step checks; a null battery level is unknown (no battery). */
internal data class RawQualificationConditions(val batteryPercent: Int?, val thermalStatus: Int) {
    val blockers: List<RawQualificationBlocker> get() = rawQualificationReadiness(batteryPercent, thermalStatus)

    /**
     * A known battery level that may start but is under [RAW_QUALIFICATION_WARN_BATTERY_PERCENT]:
     * a warning, never a blocker.
     */
    val batteryWarning: Boolean
        get() = batteryPercent != null &&
            batteryPercent >= RAW_QUALIFICATION_MIN_BATTERY_PERCENT &&
            batteryPercent < RAW_QUALIFICATION_WARN_BATTERY_PERCENT
}

/**
 * The reasons the qualification cannot start: the battery below [RAW_QUALIFICATION_MIN_BATTERY_PERCENT]
 * (an unknown level does not block; a low level above it is only [RawQualificationConditions.batteryWarning])
 * or a thermal status above [RAW_QUALIFICATION_MAX_THERMAL_STATUS].
 * A connected charger does not block: heat is judged by the thermal status, before and during the
 * runs. Empty when it may start.
 */
internal fun rawQualificationReadiness(batteryPercent: Int?, thermalStatus: Int): List<RawQualificationBlocker> = buildList {
    if (batteryPercent != null && batteryPercent < RAW_QUALIFICATION_MIN_BATTERY_PERCENT) add(RawQualificationBlocker.BATTERY_LOW)
    if (thermalStatus > RAW_QUALIFICATION_MAX_THERMAL_STATUS) add(RawQualificationBlocker.TOO_WARM)
}

/** Why a running qualification was stopped by the app rather than by the operator. */
internal object RawQualificationStopReasons {
    const val USER = "raw-qualification-cancelled"
    const val BATTERY = "raw-qualification-battery"
    const val APP_LEFT = "raw-qualification-app-left"
    const val PREVIEW_LOST = "raw-qualification-preview-lost"
    const val SERVICE_CLOSED = "raw-qualification-service-closed"
}

/** The battery below [RAW_QUALIFICATION_ABORT_BATTERY_PERCENT] stops a run; null keeps it going. */
internal fun rawQualificationRunAbort(batteryPercent: Int?): String? =
    if (batteryPercent != null && batteryPercent < RAW_QUALIFICATION_ABORT_BATTERY_PERCENT) RawQualificationStopReasons.BATTERY else null

/** Why the service refused to start a qualification. */
internal object RawQualificationRefusals {
    const val RUNNING = "raw-qualification-running"
    const val BUSY = "raw-qualification-busy"
    const val NOT_READY = "raw-qualification-not-ready"
    const val UNSUPPORTED = "raw-qualification-unsupported"
}

/**
 * Null when a qualification may start, otherwise why not: one already runs; a take, a still, a
 * transfer or frozen structural settings own the camera; the conditions block it; or no camera
 * can be qualified ([rawQualificationGateQualifiable]). Starting works with or without a viewfinder
 * (the onboarding wizard has none).
 */
internal fun rawQualificationStartRefusal(
    running: Boolean,
    phase: CameraUiPhase,
    structuralSettingsFrozen: Boolean,
    takeActive: Boolean,
    transferring: Boolean,
    blockers: List<RawQualificationBlocker>,
    hasQualifiableCamera: Boolean,
): String? = when {
    running -> RawQualificationRefusals.RUNNING
    takeActive || transferring || structuralSettingsFrozen || phase == CameraUiPhase.RECORDING || phase == CameraUiPhase.CAPTURING ->
        RawQualificationRefusals.BUSY
    blockers.isNotEmpty() -> RawQualificationRefusals.NOT_READY
    !hasQualifiableCamera -> RawQualificationRefusals.UNSUPPORTED
    else -> null
}

/**
 * Whether a camera with RAW video [gate] may be qualified: one with RAW10 output, never judged
 * (CANDIDATE) or judged before (AVAILABLE, FAILED), which a requalification measures again.
 */
internal fun rawQualificationGateQualifiable(gate: ModeGateState?): Boolean =
    gate == ModeGateState.CANDIDATE || gate == ModeGateState.AVAILABLE || gate == ModeGateState.FAILED

/** Settings offers "Qualify again" on a camera judged before; a CANDIDATE is qualified from the mode selector. */
internal fun rawVideoRequalifyOffered(gate: ModeGateState?): Boolean = gate == ModeGateState.AVAILABLE || gate == ModeGateState.FAILED

/** How a qualification ended. */
enum class RawQualificationOutcomeKind { PASSED, NO_CONFIG, CANCELLED, FAILED_TO_RUN }

/**
 * [usableCount] is the camera's usable configurations once the records are reloaded, risky ones
 * left out: a camera with only risky configurations ends as NO_CONFIG (nothing is selected for it),
 * though its risky configurations stay choosable by hand. A run stopped because the storage was not
 * rested ([reasonCode] [RawVideoQualifier.REASON_STORAGE_NOT_RESTED]) could not run, whatever the
 * records kept from before allow.
 */
internal fun rawQualificationOutcome(status: RawQualificationStatus, usableCount: Int, reasonCode: String? = null): RawQualificationOutcomeKind = when {
    status == RawQualificationStatus.CANCELLED -> RawQualificationOutcomeKind.CANCELLED
    rawQualificationStorageBusy(reasonCode) -> RawQualificationOutcomeKind.FAILED_TO_RUN
    usableCount > 0 -> RawQualificationOutcomeKind.PASSED
    status == RawQualificationStatus.UNKNOWN -> RawQualificationOutcomeKind.FAILED_TO_RUN
    else -> RawQualificationOutcomeKind.NO_CONFIG
}

/** The run stopped after a sweep that found the write cache still flushing: the phone should rest first. */
internal fun rawQualificationStorageBusy(reason: String?): Boolean = reason == RawVideoQualifier.REASON_STORAGE_NOT_RESTED

/**
 * The qualification as the UI shows it, on [CameraUiState.rawVideoQualification]: while [outcome] is
 * null it runs. [specLabel] reads like a [RawVideoConfigOption] label; [reason] says why it was
 * cancelled ([RawQualificationStopReasons]) or could not run.
 */
data class RawVideoQualificationUi(
    val phase: RawQualificationPhase = RawQualificationPhase.SWEEP,
    val fraction: Float = 0f,
    val runIndex: Int = 0,
    val runCount: Int = 0,
    val specLabel: String? = null,
    val remainingMs: Long? = null,
    val outcome: RawQualificationOutcomeKind? = null,
    val passedLabels: List<String> = emptyList(),
    val reason: String? = null,
    /** Every configuration of the run with its status, in run order; compressed follow-ups join as they start. */
    val items: List<RawQualificationItem> = emptyList(),
    /** A run that stored nothing ([rawQualificationStorageBusy]) left an earlier qualification of the camera in force. */
    val previousKept: Boolean = false,
) {
    val running: Boolean get() = outcome == null
}

/**
 * Where one configuration of a qualification stands. RISKY failed only because the storage is too
 * slow for its rate ([RawQualificationRisk]): it can still be chosen by hand, with a warning.
 * MEASURED ran (a camera check, or a session that ended) and waits for its verdict, which the
 * qualifier writes at the end.
 */
enum class RawQualificationItemStatus { PENDING, TESTING, MEASURED, PASSED, RISKY, FAILED, CAMERA_LIMIT, NOT_QUALIFIED, SKIPPED_SPACE, SKIPPED_HEAT }

/**
 * One configuration of a qualification: [label] reads like a [RawVideoConfigOption] label (compressed
 * variants end in [RAW_LABEL_COMPRESSED_SUFFIX]); [neededBytes] is the free space its run needs, the figure a skip for
 * space is decided on; [burstSeconds], on a RISKY one, how long a take lasts before the storage's
 * write cache fills and frames may drop (null when unknown); [achievedFps], on a PASSED or RISKY one,
 * the rate the camera delivered, to a tenth (null when unknown); [liveFps], on a TESTING one, the
 * rate its camera session delivers now ([RawQualificationProgress.liveFps]), null when not known yet.
 * [benchmarking] marks an already judged item whose configuration an encode benchmark streams now,
 * at [benchLiveFps]; its status stays as it is.
 */
data class RawQualificationItem(
    val spec: String,
    val label: String,
    val neededBytes: Long,
    val status: RawQualificationItemStatus,
    val burstSeconds: Long? = null,
    val achievedFps: Double? = null,
    val liveFps: Double? = null,
    val benchmarking: Boolean = false,
    val benchLiveFps: Double? = null,
) {
    /** The rate of the spec this item tests, or null when the spec names none. */
    val targetFps: Int? get() = rawQualificationSpecFps(spec)

    /** It qualified at a reduced rate: under [RawQualificationFrameRate.FPS_TOLERANCE] of its target (PLAN-071). */
    val reducedRate: Boolean get() = targetFps?.let { RawQualificationFrameRate.isReducedRate(achievedFps, it) } == true

    /** The rate its camera delivers now: an encode benchmark's on a [benchmarking] item, else [liveFps]. */
    val currentFps: Double? get() = if (benchmarking) benchLiveFps else liveFps

    /** Its [currentFps] is under [RawQualificationFrameRate.FPS_TOLERANCE] of its target. */
    val liveFpsShort: Boolean get() {
        val live = currentFps ?: return false
        val target = targetFps ?: return false
        return RawQualificationFrameRate.fpsNotReached(live, target)
    }
}

/** The configurations a qualification would run with [space] free: PENDING, or SKIPPED_SPACE when they do not fit. */
internal fun rawQualificationPlanItems(
    space: RawQualificationSpaceEstimate?,
    label: (RawVideoConfig) -> String = { RawVideoConfigOption.of(it).label },
): List<RawQualificationItem> = space?.configs.orEmpty().map { planned ->
    RawQualificationItem(planned.spec, label(planned.config), planned.neededBytes,
        if (planned.fits) RawQualificationItemStatus.PENDING else RawQualificationItemStatus.SKIPPED_SPACE)
}

/** The skip reason the qualifier writes when the volume lacks a run's [RawQualificationTiming.neededBytes]. */
internal const val RAW_QUALIFICATION_SPACE_SKIP = "insufficient-space"

/** The skip reason the qualifier writes when the phone stays at SEVERE or hotter before a run. */
internal const val RAW_QUALIFICATION_THERMAL_SKIP = "thermal-skip"

/**
 * A run's status from its result row: PASS passed, a failure the camera's declared frame rate
 * explains CAMERA_LIMIT, a failure the heat caused ([RawQualificationStaging.REASON_THERMAL_LIMIT])
 * SKIPPED_HEAT, a FAIL whose [reasonCodes] are all the storage's RISKY, any other FAIL failed, a
 * skip for space SKIPPED_SPACE, a skip for heat SKIPPED_HEAT (never judged, so not "not
 * qualified"), any other skip NOT_QUALIFIED. [reasonCodes] defaults to [reasonCode] alone.
 */
internal fun rawQualificationRunStatus(
    status: String?,
    reasonCode: String?,
    reasonCodes: List<String> = listOfNotNull(reasonCode),
): RawQualificationItemStatus = when {
    status == "PASS" -> RawQualificationItemStatus.PASSED
    reasonCode == RAW_QUALIFICATION_SPACE_SKIP -> RawQualificationItemStatus.SKIPPED_SPACE
    reasonCode == RAW_QUALIFICATION_THERMAL_SKIP -> RawQualificationItemStatus.SKIPPED_HEAT
    reasonCode == RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT -> RawQualificationItemStatus.CAMERA_LIMIT
    RawQualificationStaging.REASON_THERMAL_LIMIT in reasonCodes -> RawQualificationItemStatus.SKIPPED_HEAT
    status == "FAIL" && RawQualificationRisk.isStorageOnly(reasonCodes) -> RawQualificationItemStatus.RISKY
    status == "FAIL" -> RawQualificationItemStatus.FAILED
    else -> RawQualificationItemStatus.NOT_QUALIFIED
}

/**
 * [items] with [spec] set to [status], or with a new item for it (a compressed follow-up) at the end.
 * [burstSeconds] is kept only on a RISKY item, [achievedFps] only on a PASSED, RISKY or MEASURED one,
 * [liveFps] only on a TESTING one.
 */
internal fun rawQualificationItemsWith(
    items: List<RawQualificationItem>,
    spec: String,
    status: RawQualificationItemStatus,
    label: String,
    neededBytes: Long,
    burstSeconds: Long? = null,
    achievedFps: Double? = null,
    liveFps: Double? = null,
): List<RawQualificationItem> {
    val burst = burstSeconds.takeIf { status == RawQualificationItemStatus.RISKY }
    val rate = achievedFps.takeIf {
        status == RawQualificationItemStatus.PASSED || status == RawQualificationItemStatus.RISKY || status == RawQualificationItemStatus.MEASURED
    }
    val live = liveFps.takeIf { status == RawQualificationItemStatus.TESTING }
    val index = items.indexOfFirst { it.spec == spec }
    if (index < 0) return items + RawQualificationItem(spec, label, neededBytes, status, burst, rate, live)
    return items.toMutableList().also { it[index] = it[index].copy(status = status, burstSeconds = burst, achievedFps = rate, liveFps = live) }
}

/**
 * [items] once [progress] arrives: the configuration a long run or a camera check names becomes
 * TESTING unless it already has a verdict, and takes the report's [RawQualificationProgress.liveFps].
 * A TESTING item whose session the report shows ended (another configuration, a cool-down, a
 * benchmark, saving) becomes MEASURED: camera checks get their verdicts only at the end.
 * The encode benchmark names the configuration it streams, already judged: that item becomes
 * [RawQualificationItem.benchmarking] at the report's rate, keeping its status. Any other report
 * ends a benchmark's marking; nothing else changes.
 */
internal fun rawQualificationItemsOnProgress(
    items: List<RawQualificationItem>,
    progress: RawQualificationProgress,
    label: String?,
    neededBytes: Long,
): List<RawQualificationItem> {
    val benchSpec = progress.spec.takeIf { progress.phase == RawQualificationPhase.ENCODE_BENCH }
    val marked = rawQualificationItemsBenchmarking(rawQualificationItemsSessionEnded(items, progress), benchSpec, progress.liveFps)
    if (benchSpec != null) return marked
    return rawQualificationItemsOnRunProgress(marked, progress, label, neededBytes)
}

/** [items] with only [spec]'s item (none when null) marked as benchmarked at [fps]; unchanged items keep the list. */
private fun rawQualificationItemsBenchmarking(items: List<RawQualificationItem>, spec: String?, fps: Double?): List<RawQualificationItem> {
    fun wanted(item: RawQualificationItem) =
        if (item.spec == spec) item.copy(benchmarking = true, benchLiveFps = fps) else item.copy(benchmarking = false, benchLiveFps = null)
    return if (items.all { wanted(it) == it }) items else items.map(::wanted)
}

/** The phases a TESTING item's own session reports while it lasts (a heat wait comes before its stream). */
private val RAW_QUALIFICATION_SESSION_PHASES = setOf(
    RawQualificationPhase.RUN, RawQualificationPhase.CAMERA_CHECK, RawQualificationPhase.THERMAL_WAIT,
)

/** [items] with every TESTING item [progress] is not about any more MEASURED; unchanged items keep the list. */
private fun rawQualificationItemsSessionEnded(items: List<RawQualificationItem>, progress: RawQualificationProgress): List<RawQualificationItem> {
    fun ended(item: RawQualificationItem) = item.status == RawQualificationItemStatus.TESTING &&
        !(item.spec == progress.spec && progress.phase in RAW_QUALIFICATION_SESSION_PHASES)
    if (items.none(::ended)) return items
    return items.map { if (ended(it)) it.copy(status = RawQualificationItemStatus.MEASURED, liveFps = null) else it }
}

/**
 * [items] once a camera check's result row for [spec] arrives: its item becomes MEASURED with the
 * [achievedFps] the check saw (null when unknown), unless it already has a verdict. An unknown spec joins.
 */
internal fun rawQualificationItemsCameraChecked(
    items: List<RawQualificationItem>,
    spec: String,
    label: String,
    neededBytes: Long,
    achievedFps: Double?,
): List<RawQualificationItem> {
    val current = items.firstOrNull { it.spec == spec }
    if (current != null && current.status !in RAW_QUALIFICATION_UNJUDGED) return items
    return rawQualificationItemsWith(items, spec, RawQualificationItemStatus.MEASURED, label, current?.neededBytes ?: neededBytes, achievedFps = achievedFps)
}

/** The statuses that carry no verdict yet. */
private val RAW_QUALIFICATION_UNJUDGED = setOf(
    RawQualificationItemStatus.PENDING, RawQualificationItemStatus.TESTING, RawQualificationItemStatus.MEASURED,
)

private fun rawQualificationItemsOnRunProgress(
    items: List<RawQualificationItem>,
    progress: RawQualificationProgress,
    label: String?,
    neededBytes: Long,
): List<RawQualificationItem> {
    val spec = progress.spec ?: return items
    if (progress.phase != RawQualificationPhase.RUN && progress.phase != RawQualificationPhase.CAMERA_CHECK) return items
    val current = items.firstOrNull { it.spec == spec }
    if (current != null && current.status == RawQualificationItemStatus.TESTING) {
        return if (current.liveFps == progress.liveFps) items
        else items.map { if (it.spec == spec) it.copy(liveFps = progress.liveFps) else it }
    }
    // A measured item measured again (a retry) tests anew; one with a verdict keeps it.
    if (current != null && current.status != RawQualificationItemStatus.PENDING && current.status != RawQualificationItemStatus.MEASURED) return items
    return rawQualificationItemsWith(items, spec, RawQualificationItemStatus.TESTING, label ?: spec, neededBytes, liveFps = progress.liveFps)
}

/** The items of a run that ended: one still TESTING or MEASURED (no verdict: a cancel cut it short) goes back to PENDING. */
internal fun rawQualificationItemsFinished(items: List<RawQualificationItem>): List<RawQualificationItem> =
    items.map {
        if (it.status == RawQualificationItemStatus.TESTING || it.status == RawQualificationItemStatus.MEASURED)
            it.copy(status = RawQualificationItemStatus.PENDING, liveFps = null, achievedFps = null, benchmarking = false, benchLiveFps = null)
        else if (it.benchmarking) it.copy(benchmarking = false, benchLiveFps = null)
        else it
    }

/** The size and rate a [RawVideoConfigOption.label] starts with ("4080×3060 · 30 fps"). */
private val RAW_LABEL_SIZE_RATE = Regex("""^(\d+)×(\d+) · (\d+) fps""")

/**
 * Heaviest stream first, by label: the pixel rate (width × height × fps, which the RAW10 stream
 * follows), then the higher frame rate, then the uncompressed configuration before its compressed
 * twin, so a twin comes right after it. A label without a size and rate goes last. Equal keys keep
 * their order (the sorts using it are stable).
 */
internal val rawQualificationHeaviestFirst: Comparator<String> = run {
    fun parts(label: String) = RAW_LABEL_SIZE_RATE.find(label)?.groupValues
    compareByDescending<String> { label -> parts(label)?.let { it[1].toLong() * it[2].toLong() * it[3].toLong() } ?: -1L }
        .thenByDescending { label -> parts(label)?.get(3)?.toInt() ?: -1 }
        .thenBy { label -> if (parts(label) == null) "" else rawConfigLabelParts(label).first }
        .thenBy { label -> parts(label) != null && rawConfigLabelParts(label).second != null }
}

/** A row the qualification is working on: a session testing it, or the encode benchmark streaming it. */
internal val RawQualificationItem.inProgress: Boolean
    get() = status == RawQualificationItemStatus.TESTING || benchmarking

/** [items] heaviest first ([rawQualificationHeaviestFirst]), as each section of the result lists them. */
internal fun rawQualificationItemsHeaviestFirst(items: List<RawQualificationItem>): List<RawQualificationItem> =
    items.sortedWith(compareBy(rawQualificationHeaviestFirst) { it.label })

/**
 * [items] in the order a running qualification lists them: the row in progress
 * ([RawQualificationItem.inProgress]) first, then every other heaviest first, each compressed twin
 * right after its uncompressed twin.
 */
internal fun rawQualificationItemsRunningOrder(items: List<RawQualificationItem>): List<RawQualificationItem> {
    val (current, others) = items.partition { it.inProgress }
    return current + rawQualificationItemsHeaviestFirst(others)
}

/** The frame rate of a qualification spec ("full@60+bp" is 60), or null when it has none. */
internal fun rawQualificationSpecFps(spec: String): Int? =
    spec.substringAfter('@', "").takeWhile(Char::isDigit).toIntOrNull()?.takeIf { it > 0 }

/**
 * The canonical word a compressed configuration's [RawVideoConfigOption.label] ends in. Labels are
 * built away from any `Context` (in the service, from records), so they carry this English word
 * and the UI swaps it for the localized `raw_video_label_compressed` when it shows them
 * ([rawConfigLabelLocalized]).
 */
internal const val RAW_LABEL_COMPRESSED = "Compressed"
internal const val RAW_LABEL_COMPRESSED_SUFFIX = " · $RAW_LABEL_COMPRESSED"
private const val NBSP = '\u00A0'

/**
 * A [RawVideoConfigOption.label] split into the size and rate ("4080×3060 · 30 fps") and its
 * compressed suffix ("· Compressed", with [compressedWord] in its place), null when uncompressed.
 */
internal fun rawConfigLabelParts(label: String, compressedWord: String = RAW_LABEL_COMPRESSED): Pair<String, String?> =
    if (label.endsWith(RAW_LABEL_COMPRESSED_SUFFIX)) label.dropLast(RAW_LABEL_COMPRESSED_SUFFIX.length) to "· $compressedWord"
    else label to null

/** [label] with its compressed suffix in [compressedWord], the localized word. */
internal fun rawConfigLabelLocalized(label: String, compressedWord: String): String {
    val (base, suffix) = rawConfigLabelParts(label, compressedWord)
    return if (suffix == null) base else "$base $suffix"
}

/**
 * [label] with no-break spaces inside the size and rate, so a wrapped line never leaves "fps" alone;
 * the only break left is before the compressed suffix (in [compressedWord]).
 */
internal fun rawConfigLabelNoBreak(label: String, compressedWord: String = RAW_LABEL_COMPRESSED): String {
    val (base, suffix) = rawConfigLabelParts(label, compressedWord)
    val glued = base.replace(' ', NBSP)
    return if (suffix == null) glued else "$glued ${suffix.replace(' ', NBSP)}"
}

/** The line a running qualification shows for its current step. */
internal enum class RawQualificationStepText { PREPARING, STORAGE, RESTING, CONFIG, CAMERA, COMPRESSION, COOLING, WAITING_TO_COOL, SAVING }

internal fun rawQualificationStepText(phase: RawQualificationPhase): RawQualificationStepText = when (phase) {
    RawQualificationPhase.SWEEP -> RawQualificationStepText.STORAGE
    RawQualificationPhase.RING -> RawQualificationStepText.PREPARING
    RawQualificationPhase.RUN -> RawQualificationStepText.CONFIG
    RawQualificationPhase.CAMERA_CHECK -> RawQualificationStepText.CAMERA
    RawQualificationPhase.ENCODE_BENCH -> RawQualificationStepText.COMPRESSION
    RawQualificationPhase.COOLING -> RawQualificationStepText.COOLING
    RawQualificationPhase.STORAGE_REST -> RawQualificationStepText.RESTING
    RawQualificationPhase.THERMAL_WAIT -> RawQualificationStepText.WAITING_TO_COOL
    RawQualificationPhase.SAVING, RawQualificationPhase.DONE -> RawQualificationStepText.SAVING
}

/** The phases that name the configuration they work on. */
private val RAW_QUALIFICATION_SPEC_PHASES = setOf(
    RawQualificationPhase.RUN, RawQualificationPhase.CAMERA_CHECK, RawQualificationPhase.ENCODE_BENCH,
    RawQualificationPhase.THERMAL_WAIT, RawQualificationPhase.COOLING,
)

/** The time left of a run planned at [estimatedTotalMs], [fraction] done. */
internal fun rawQualificationRemainingMs(estimatedTotalMs: Long, fraction: Double): Long =
    (estimatedTotalMs.coerceAtLeast(0) * (1.0 - fraction.coerceIn(0.0, 1.0))).roundToLong()

/** [progress] as the UI shows it; [specLabel] is the label of `progress.spec`, when it names one. */
internal fun rawQualificationUiOf(progress: RawQualificationProgress, estimatedTotalMs: Long, specLabel: String?): RawVideoQualificationUi =
    RawVideoQualificationUi(
        phase = progress.phase,
        fraction = progress.fraction.coerceIn(0.0, 1.0).toFloat(),
        runIndex = progress.runIndex,
        runCount = progress.runCount,
        specLabel = specLabel.takeIf { progress.phase in RAW_QUALIFICATION_SPEC_PHASES },
        remainingMs = rawQualificationRemainingMs(estimatedTotalMs, progress.fraction),
    )

/** True when [last] and [next] differ enough to post: a new phase or step, or [intervalMs] since the last post. */
internal fun rawQualificationShouldPost(last: RawVideoQualificationUi?, next: RawVideoQualificationUi, sinceLastMs: Long, intervalMs: Long = 250L): Boolean =
    last == null || last.phase != next.phase || last.runIndex != next.runIndex || last.runCount != next.runCount ||
        last.items != next.items || sinceLastMs >= intervalMs

/**
 * What the qualification prompt and step know before a run: the camera it would qualify, its RAW
 * video gate, the duration and space for the free space now ([space], null when unknown) and
 * whether this build's last qualification found nothing ([previousFailure]).
 */
internal data class RawVideoQualificationInfo(
    val cameraId: String?,
    val gate: ModeGateState?,
    val space: RawQualificationSpaceEstimate?,
    val previousFailure: Boolean = false,
)

/** Where the qualification step was opened from. */
internal enum class RawQualificationEntry { ONBOARDING, SELECTOR, SETTINGS }

/** Whether a pass from [entry] selects RAW video: a requalification from Settings never changes the mode by itself. */
internal fun rawQualificationSelectsOnPass(entry: RawQualificationEntry): Boolean = entry != RawQualificationEntry.SETTINGS

/** Where leaving the step goes: [finishOnboarding] ends the wizard; [mode] is the mode to be in, null for "as the app starts" (or, from Settings, as it is). */
internal data class RawQualificationReturn(val finishOnboarding: Boolean, val mode: CaptureMode?)

/**
 * Leaving the step (Later, Done, or after a cancel): from the onboarding it finishes the wizard, in
 * RAW video if something passed, otherwise in the normal start mode; from the selector it returns
 * to capture, in RAW video if something passed, otherwise in [previousMode]; from Settings it
 * returns there and leaves the mode to the service (see [rawVideoModeFallback]).
 */
internal fun rawQualificationReturn(entry: RawQualificationEntry, passed: Boolean, previousMode: CaptureMode?): RawQualificationReturn = when (entry) {
    RawQualificationEntry.ONBOARDING -> RawQualificationReturn(finishOnboarding = true, mode = if (passed) CaptureMode.RAW_VIDEO else null)
    RawQualificationEntry.SELECTOR -> RawQualificationReturn(finishOnboarding = false, mode = if (passed) CaptureMode.RAW_VIDEO else previousMode)
    RawQualificationEntry.SETTINGS -> RawQualificationReturn(finishOnboarding = false, mode = null)
}

/**
 * The mode to leave RAW video for once a qualification left [selectedMode] RAW_VIDEO with a gate
 * that no longer selects it (every configuration that had passed now failed): [previous] when it is
 * selectable, otherwise VIDEO. Null to stay, outside RAW video or while RAW video stays selectable;
 * a configuration that stopped qualifying while others still do falls back through the service's
 * automatic choice instead. [hasConfig] false (only risky configurations are left and none was
 * chosen by hand, so Automatic resolves to nothing) leaves RAW video too.
 */
internal fun rawVideoModeFallback(
    selectedMode: CaptureMode,
    gates: Map<CaptureMode, ModeGateState>,
    previous: CaptureMode?,
    hasConfig: Boolean = true,
): CaptureMode? = when {
    selectedMode != CaptureMode.RAW_VIDEO || (hasConfig && CameraUiState.isModeSelectable(gates[CaptureMode.RAW_VIDEO])) -> null
    previous != null && previous != CaptureMode.RAW_VIDEO && CameraUiState.isModeSelectable(gates[previous]) -> previous
    else -> CaptureMode.VIDEO
}

/** The onboarding stages after the pages. */
internal enum class OnboardingRawStage { QUESTION, STEP }

/**
 * Where the wizard goes once its pages are done: the RAW question when the camera may be used and
 * its RAW video gate is CANDIDATE, otherwise nowhere (the app opens).
 */
internal fun onboardingRawStageAfterPages(cameraGranted: Boolean, rawGate: ModeGateState?): OnboardingRawStage? =
    if (cameraGranted && rawGate == ModeGateState.CANDIDATE) OnboardingRawStage.QUESTION else null

/** "Now" goes to the qualification step; "Later" ends the wizard. */
internal fun onboardingRawStageAfterAnswer(now: Boolean): OnboardingRawStage? = if (now) OnboardingRawStage.STEP else null

/** The battery level from an ACTION_BATTERY_CHANGED intent; null without one or without a battery. */
internal fun batteryPercentOf(intent: Intent?): Int? {
    if (intent == null) return null
    val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (present && level >= 0 && scale > 0) (level * 100 / scale) else null
}

/** Reads the conditions now: the sticky battery broadcast and the thermal status. */
internal fun readRawQualificationConditions(context: Context): RawQualificationConditions {
    val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
    val thermal = runCatching { context.getSystemService(PowerManager::class.java)?.currentThermalStatus }.getOrNull() ?: PowerManager.THERMAL_STATUS_NONE
    return RawQualificationConditions(batteryPercentOf(battery), thermal)
}
