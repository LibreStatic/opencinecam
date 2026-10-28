/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import com.librestatic.opencinecam.core.model.CalibrationFlag
import com.librestatic.opencinecam.core.model.CalibrationQuality
import com.librestatic.opencinecam.core.model.CalibrationSessionKind
import com.librestatic.opencinecam.core.model.CalibrationSet
import com.librestatic.opencinecam.core.model.CalibrationSourcePath
import com.librestatic.opencinecam.core.model.IlluminantHint
import com.librestatic.opencinecam.core.model.LensCorrection
import com.librestatic.opencinecam.core.model.Similarity
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** What the runner needs from the camera: a zoom command, a stream of samples and a clock. */
interface CalibrationRig {
    fun setZoom(ratio: Float)

    /** Blocks up to [timeoutMs] for the next sample (frame plus metadata); null on timeout. */
    fun nextSample(timeoutMs: Long): CalibrationSample?

    /** Monotonic milliseconds; the runner's timeouts use it. */
    fun nowMs(): Long
}

/** One physical lens of the logical camera and where it starts on the logical zoom scale. */
data class CalibrationLens(val physicalId: String, val nominalRatio: Float)

enum class RunPhase { PREPARING, FINDING_SWITCH_POINTS, MEASURING, SOLVING }

enum class RunLensStatus { PENDING, REFERENCE, MEASURING, DONE, SKIPPED, FAILED }

enum class RunFailure { SCENE_UNSTABLE, LOW_QUALITY, CAMERA_LOST, INTERNAL }

data class RunLens(
    val physicalId: String,
    val nominalRatio: Float,
    val status: RunLensStatus,
    /** 0..1 within this lens' measurement. */
    val progress: Float = 0f,
    val colorDeltaEBefore: Float? = null,
    val colorDeltaEAfter: Float? = null,
    val alignPxBefore: Float? = null,
    val alignPxAfter: Float? = null,
    val score: Float? = null,
    val flags: Set<CalibrationFlag> = emptySet(),
)

/** Lenses are in nominal-ratio order. */
/** One alternating cycle of [targetId] against the reference: the averaged frames and what was fitted to them. */
class CalibrationCycle(
    val targetId: String,
    val cycle: Int,
    val referenceZoom: Float,
    val targetZoom: Float,
    val reference: RgbaFrame,
    val target: RgbaFrame,
    val color: ColorFit,
    val geometry: GeometryFit,
)

data class RunProgress(val phase: RunPhase, val referencePhysicalId: String?, val lenses: List<RunLens>)

sealed interface RunOutcome {
    data class Success(val set: CalibrationSet, val progress: RunProgress) : RunOutcome
    data class Failure(val failure: RunFailure, val progress: RunProgress) : RunOutcome
    data class Cancelled(val progress: RunProgress) : RunOutcome
}

/**
 * Calibrates the lenses of one logical camera against the lens active at zoom 1 (the reference),
 * through a [CalibrationRig]. Blocking: run it on a worker thread; [cancel] and [abort] may be
 * called from any thread.
 *
 * Steps. (1) The reference is the lens that settles at zoom 1. (2) For each lens next to it in
 * zoom order the switch is located: a coarse zoom scan outward until the active lens changes, a
 * bisection of that interval, always approaching from the reference side, then a scan and
 * bisection back inward from beyond the switch, which exposes the HAL's hysteresis. (3) Inside the
 * hysteresis band both lenses can be held at the very same zoom ratio (reference approached from
 * inside, target from outside); without a band the holds sit on either side of the switch and the
 * solved scale is corrected for the ratio difference. (4) A hold waits for [Config.holdConsecutive]
 * consecutive results from the expected lens at the requested zoom with AE and AWB converged or
 * locked, then averages the last [Config.framesAveraged] frames. (5) [Config.cycles] alternating
 * reference/target holds are solved separately (colour and geometry) and median-combined, then
 * validated. Lenses that are not next to the reference are skipped and get no correction.
 */
class LensCalibrationRunner(
    private val rig: CalibrationRig,
    lenses: List<CalibrationLens>,
    private val logicalId: String,
    private val fingerprint: String,
    private val sourcePath: CalibrationSourcePath,
    private val zoomRange: ClosedFloatingPointRange<Float>,
    private val onProgress: (RunProgress) -> Unit = {},
    /** Each alternating cycle's frames and fits, for diagnostics; called on the runner's thread. */
    private val onCycle: (CalibrationCycle) -> Unit = {},
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val config: Config = Config(),
) {
    data class Config(
        /** Multiplicative zoom step of the coarse scan, and the faster one far from the target lens' nominal ratio. */
        val coarseStep: Float = 1.05f,
        val fastStep: Float = 1.15f,
        /** The fast step is used while the target's nominal ratio is more than this factor away. */
        val fastUntilFactor: Float = 1.4f,
        val bisections: Int = 3,
        /** Consecutive results needed to call the active lens settled in a scan step, and elsewhere. */
        val scanSamples: Int = 2,
        val settleSamples: Int = 3,
        /** Results dropped right after a zoom command (they predate it). */
        val discardAfterZoom: Int = 2,
        val settleTimeoutMs: Long = 3_000L,
        val holdConsecutive: Int = 10,
        val framesAveraged: Int = 8,
        val holdTimeoutMs: Long = 6_000L,
        val cycles: Int = 3,
        /** Exposure x ISO spread within a hold above which the hold counts as unstable AE. */
        val aeSpreadLimit: Float = 0.10f,
        /** Relative difference between the result's zoom ratio and the requested one that still counts as arrived. */
        val zoomTolerance: Float = 0.01f,
        /** A lens whose combined score is below this gets no correction. */
        val minScore: Float = 0.4f,
        val pollMs: Long = 200L,
    )

    @Volatile private var cancelled = false
    @Volatile private var aborted: RunFailure? = null
    private val sorted = lenses.sortedBy { it.nominalRatio }
    private val rows = sorted.map { RunLens(it.physicalId, it.nominalRatio, RunLensStatus.PENDING) }.toMutableList()
    private var phase = RunPhase.PREPARING
    private var referenceId: String? = null
    private var discard = 0
    private val curve = if (sourcePath == CalibrationSourcePath.HLG) TransferCurve.HLG else TransferCurve.SDR

    fun cancel() { cancelled = true }

    /** Ends the run with [failure] at the next opportunity (the camera went away, say). */
    fun abort(failure: RunFailure) { aborted = failure }

    private class RunAbort(val outcome: RunOutcome) : RuntimeException(null, null, false, false)

    private enum class LensFail { SWITCH_NOT_FOUND, UNSTABLE, LOW_QUALITY }

    fun run(): RunOutcome = try { execute() } catch (abort: RunAbort) { abort.outcome }

    private fun execute(): RunOutcome {
        if (sorted.size < 2 || sorted.map { it.physicalId }.toSet().size != sorted.size) return failure(RunFailure.INTERNAL)
        emit()
        val refId = findReference()
        referenceId = refId
        val refIndex = sorted.indexOfFirst { it.physicalId == refId }
        if (refIndex < 0) return failure(RunFailure.INTERNAL)
        for (i in sorted.indices) {
            val status = when {
                i == refIndex -> RunLensStatus.REFERENCE
                abs(i - refIndex) == 1 -> RunLensStatus.PENDING
                else -> RunLensStatus.SKIPPED
            }
            rows[i] = rows[i].copy(status = status)
        }
        emit()

        val neighbours = listOf(refIndex - 1, refIndex + 1).filter { it in sorted.indices }
        val failures = HashMap<Int, LensFail>()
        val switches = HashMap<Int, SwitchPoints>()

        phase = RunPhase.FINDING_SWITCH_POINTS
        emit()
        for (i in neighbours) {
            val sw = findSwitch(refId, sorted[i], if (sorted[i].nominalRatio > sorted[refIndex].nominalRatio) 1 else -1)
            if (sw == null) { failures[i] = LensFail.SWITCH_NOT_FOUND; rows[i] = rows[i].copy(status = RunLensStatus.FAILED); emit() }
            else { switches[i] = sw; rows[i] = rows[i].copy(progress = 0.25f); emit() }
        }

        phase = RunPhase.MEASURING
        emit()
        val corrections = HashMap<Int, LensCorrection>()
        var illuminant: List<Float>? = null
        for (i in neighbours) {
            val sw = switches[i] ?: continue
            rows[i] = rows[i].copy(status = RunLensStatus.MEASURING)
            emit()
            when (val result = measure(i, refId, sw)) {
                is Measured.Failed -> {
                    failures[i] = result.reason
                    rows[i] = rows[i].copy(status = RunLensStatus.FAILED, flags = result.flags, score = result.score,
                        colorDeltaEBefore = result.colorBefore, colorDeltaEAfter = result.colorAfter,
                        alignPxBefore = result.alignBefore, alignPxAfter = result.alignAfter)
                }
                is Measured.Done -> {
                    corrections[i] = result.correction
                    if (illuminant == null) illuminant = result.illuminant
                    rows[i] = rows[i].copy(status = RunLensStatus.DONE, progress = 1f, score = result.correction.quality.score,
                        flags = result.correction.quality.flags,
                        colorDeltaEBefore = result.colorBefore, colorDeltaEAfter = result.correction.quality.colorResidualDeltaE,
                        alignPxBefore = result.alignBefore, alignPxAfter = result.correction.quality.alignResidualPx)
                }
            }
            emit()
        }

        phase = RunPhase.SOLVING
        emit()
        if (corrections.isEmpty()) {
            return failure(
                when {
                    failures.values.any { it == LensFail.UNSTABLE } -> RunFailure.SCENE_UNSTABLE
                    failures.values.any { it == LensFail.LOW_QUALITY } -> RunFailure.LOW_QUALITY
                    else -> RunFailure.INTERNAL
                },
            )
        }
        val ordered = sorted.indices.mapNotNull { i ->
            if (i == refIndex) LensCorrection.identity(refId) else corrections[i]
        }
        val set = CalibrationSet(
            schema = CalibrationSet.SCHEMA,
            fingerprint = fingerprint,
            logicalId = logicalId,
            sessionKind = CalibrationSessionKind.LOGICAL,
            referencePhysicalId = refId,
            sourcePath = sourcePath,
            createdAtMs = wallClockMs(),
            illuminantHint = illuminant?.let { IlluminantHint(null, it) },
            lenses = ordered,
        )
        return RunOutcome.Success(set, snapshot())
    }

    // ---- reference ----

    private fun findReference(): String {
        val z = zoomTo(1f)
        return awaitStable(z, config.settleSamples * 2, 2 * config.holdTimeoutMs) ?: throw RunAbort(failure(RunFailure.INTERNAL))
    }

    // ---- switch points ----

    /**
     * Where the active lens changes, in the outward coordinate `u = dir * ln(zoom)` (u grows toward
     * the target lens). [refEdge] is the last ratio at which the reference held when approached
     * from the inside and [targetEdge] the first ratio at which the target took over; [backTarget]
     * is the lowest-u ratio at which the target still held when approached from the outside.
     * [farOuter] is a ratio well inside the target's range.
     */
    private data class SwitchPoints(
        val dir: Int,
        val refEdge: Float,
        val targetEdge: Float,
        val backTarget: Float,
        val farOuter: Float,
    ) {
        private fun u(r: Float) = dir * ln(r)
        private fun ratio(u: Float) = exp(dir * u)

        /** True when both lenses can be held at the same zoom ratio. */
        val hasBand: Boolean get() = u(backTarget) <= u(refEdge)

        val refHold: Float get() = if (hasBand) ratio((u(backTarget) + u(refEdge)) / 2f) else refEdge
        val targetHold: Float get() = if (hasBand) refHold else ratio(maxOf(u(targetEdge), u(backTarget)))
    }

    private fun findSwitch(refId: String, target: CalibrationLens, dir: Int): SwitchPoints? {
        val limit = if (dir > 0) minOf(zoomRange.endInclusive, target.nominalRatio * 1.3f)
        else maxOf(zoomRange.start, target.nominalRatio / 1.3f)
        fun u(r: Float) = dir * ln(r)
        fun ratio(u: Float) = exp(dir * u)
        fun beyond(r: Float) = u(r) >= u(limit) - 1e-4f

        val start = zoomTo(1f)
        if (awaitStable(start, config.settleSamples, config.settleTimeoutMs) != refId) return null

        // Coarse scan outward.
        var a = start
        var b: Float? = null
        var r = start
        while (b == null) {
            if (beyond(r)) return null
            val far = u(target.nominalRatio) - u(r) > ln(config.fastUntilFactor)
            val step = ln(if (far) config.fastStep else config.coarseStep)
            r = zoomTo(ratio(minOf(u(r) + step, u(limit))))
            when (awaitStable(r, config.scanSamples, config.settleTimeoutMs) ?: return null) {
                refId -> a = r
                target.physicalId -> b = r
                else -> return null
            }
        }
        var targetEdge: Float = checkNotNull(b)
        // Bisect the switch, always approaching from the reference side.
        repeat(config.bisections) {
            val mid = sqrt(a * targetEdge).coerceIn(zoomRange.start, zoomRange.endInclusive)
            zoomTo(start)
            awaitStable(start, config.scanSamples, config.settleTimeoutMs) ?: return null
            zoomTo(mid)
            when (awaitStable(mid, config.settleSamples, config.settleTimeoutMs) ?: return null) {
                refId -> a = mid
                target.physicalId -> targetEdge = mid
                else -> return null
            }
        }

        // Back in from beyond the switch, which shows the hysteresis.
        val farOuter = zoomTo(ratio(minOf(u(targetEdge) + 2 * ln(config.coarseStep), u(limit))))
        if (awaitStable(farOuter, config.settleSamples, config.settleTimeoutMs) != target.physicalId) return null
        var backTarget = farOuter
        var backRef: Float? = null
        var cur = farOuter
        while (backRef == null) {
            if (u(cur) < u(a) - ln(config.coarseStep)) return null // target still active below the reference's own edge
            cur = zoomTo(ratio(u(cur) - ln(config.coarseStep)))
            when (awaitStable(cur, config.scanSamples, config.settleTimeoutMs) ?: return null) {
                target.physicalId -> backTarget = cur
                refId -> backRef = cur
                else -> return null
            }
        }
        var backRefEdge: Float = checkNotNull(backRef)
        repeat(config.bisections) {
            val mid = sqrt(backTarget * backRefEdge).coerceIn(zoomRange.start, zoomRange.endInclusive)
            zoomTo(farOuter)
            awaitStable(farOuter, config.scanSamples, config.settleTimeoutMs) ?: return null
            zoomTo(mid)
            when (awaitStable(mid, config.settleSamples, config.settleTimeoutMs) ?: return null) {
                target.physicalId -> backTarget = mid
                refId -> backRefEdge = mid
                else -> return null
            }
        }
        return SwitchPoints(dir, a, targetEdge, backTarget, farOuter)
    }

    // ---- measuring ----

    private sealed interface Measured {
        class Done(
            val correction: LensCorrection,
            val colorBefore: Float,
            val alignBefore: Float,
            val illuminant: List<Float>?,
        ) : Measured

        class Failed(
            val reason: LensFail,
            val flags: Set<CalibrationFlag> = emptySet(),
            val score: Float? = null,
            val colorBefore: Float? = null,
            val colorAfter: Float? = null,
            val alignBefore: Float? = null,
            val alignAfter: Float? = null,
        ) : Measured
    }

    private class Hold(val frame: RgbaFrame, val zoom: Float, val aeStable: Boolean, val awbGains: List<Float>?)

    private fun measure(index: Int, refId: String, sw: SwitchPoints): Measured {
        val target = sorted[index]
        val refFrames = ArrayList<RgbaFrame>()
        val colorFits = ArrayList<ColorFit>()
        val geometryFits = ArrayList<GeometryFit>()
        var aeStable = true
        var illuminant: List<Float>? = null
        var lastRefCells: List<CellStat> = emptyList()
        var lastTargetCells: List<CellStat> = emptyList()
        for (cycle in 0 until config.cycles) {
            val ref = hold(refId, 1f, sw.refHold) ?: return Measured.Failed(LensFail.UNSTABLE)
            rows[index] = rows[index].copy(progress = 0.25f + 0.75f * (cycle * 2 + 1) / (config.cycles * 2))
            emit()
            val tgt = hold(target.physicalId, sw.farOuter, sw.targetHold) ?: return Measured.Failed(LensFail.UNSTABLE)
            rows[index] = rows[index].copy(progress = 0.25f + 0.75f * (cycle * 2 + 2) / (config.cycles * 2))
            emit()
            aeStable = aeStable && ref.aeStable && tgt.aeStable
            if (illuminant == null) illuminant = ref.awbGains
            refFrames += ref.frame
            lastRefCells = CellStats.compute(ref.frame, curve = curve)
            lastTargetCells = CellStats.compute(tgt.frame, curve = curve)
            colorFits += ColorSolver.solve(lastRefCells, lastTargetCells)
            val geometry = GeometrySolver.solve(FrameOps.luma(ref.frame, linear = false), FrameOps.luma(tgt.frame, linear = false))
            // The logical zoom magnifies both lenses; remove the part caused by holding at different ratios.
            geometryFits += geometry.copy(scale = geometry.scale * ref.zoom / tgt.zoom)
            onCycle(CalibrationCycle(target.physicalId, cycle, ref.zoom, tgt.zoom, ref.frame, tgt.frame, colorFits.last(), geometryFits.last()))
        }
        val color = CalibrationMedian.combine(colorFits)
        val geometry = CalibrationMedian.combineGeometry(geometryFits)
        val verdict = CalibrationValidator.evaluate(color, geometry, lastRefCells, lastTargetCells, aeStable)
        val flags = verdict.issues.map { CalibrationFlag.valueOf(it.name) }.toSet()
        val alignBefore = alignmentDisplacementPx(geometry, refFrames[0].width, refFrames[0].height)
        val finite = (color.matrix.asList() + color.gains.asList() + listOf(color.evOffset, geometry.tx, geometry.ty, geometry.scale, geometry.rotationRad))
            .all { it.isFinite() }
        if (!finite || abs(color.evOffset) > MAX_EV || geometry.scale !in 0.5f..2f) {
            return Measured.Failed(LensFail.LOW_QUALITY, flags, verdict.score, color.residualDeltaEBefore, color.residualDeltaE, alignBefore, geometry.residualPx)
        }
        if (verdict.score < config.minScore) {
            return Measured.Failed(LensFail.LOW_QUALITY, flags, verdict.score, color.residualDeltaEBefore, color.residualDeltaE, alignBefore, geometry.residualPx)
        }
        val correction = LensCorrection(
            physicalId = target.physicalId,
            colorMatrix = color.matrix.toList(),
            gains = color.gains.toList(),
            evOffset = color.evOffset,
            align = Similarity(geometry.tx, geometry.ty, geometry.scale, geometry.rotationRad),
            quality = CalibrationQuality(color.residualDeltaE, geometry.residualPx, color.cellsUsed, verdict.score, flags),
        )
        return Measured.Done(correction, color.residualDeltaEBefore, alignBefore, illuminant)
    }

    /** RMS displacement, in pixels of a [width] x [height] readback, the fit applies over the frame. */
    private fun alignmentDisplacementPx(fit: GeometryFit, width: Int, height: Int): Float {
        val cos = kotlin.math.cos(fit.rotationRad); val sin = kotlin.math.sin(fit.rotationRad)
        var sum = 0.0; var n = 0
        for (gy in -2..2) for (gx in -2..2) {
            val x = gx * width / 4.0; val y = gy * height / 4.0
            val tx = fit.scale * (cos * x - sin * y) + fit.tx * width
            val ty = fit.scale * (sin * x + cos * y) + fit.ty * height
            sum += (tx - x) * (tx - x) + (ty - y) * (ty - y); n++
        }
        return sqrt(sum / n).toFloat()
    }

    /**
     * Brings the camera to [expectedId] at [ratio] (via [approachFrom], on the right side of the
     * hysteresis) and waits for [Config.holdConsecutive] consecutive qualifying results; null when
     * that takes longer than [Config.holdTimeoutMs] or the lens is not the expected one.
     */
    private fun hold(expectedId: String, approachFrom: Float, ratio: Float): Hold? {
        val from = zoomTo(approachFrom)
        if (awaitStable(from, config.scanSamples, config.settleTimeoutMs) != expectedId) return null
        val target = zoomTo(ratio)
        val deadline = rig.nowMs() + config.holdTimeoutMs
        val window = ArrayList<CalibrationSample>()
        while (window.size < config.holdConsecutive) {
            val s = awaitSample(deadline) ?: return null
            if (s.physicalId == expectedId && zoomOk(s, target) && converged(s.aeState) && converged(s.awbState)) window += s
            else window.clear()
        }
        val used = window.takeLast(config.framesAveraged)
        val exposure = window.mapNotNull { s -> if (s.exposureNs != null && s.iso != null) s.exposureNs.toDouble() * s.iso else null }
        val spread = if (exposure.size < 2) 0.0 else (exposure.max() - exposure.min()) / exposure.max().coerceAtLeast(1.0)
        val gains = used.mapNotNull { it.awbGains }.filter { it.size == 4 }
        val meanGains = if (gains.size == used.size && gains.isNotEmpty()) List(4) { c -> gains.map { it[c] }.average().toFloat() } else null
        val zooms = used.mapNotNull { it.zoomRatio }
        return Hold(
            FrameOps.average(used.map { it.frame }),
            if (zooms.isEmpty()) target else zooms.average().toFloat(),
            spread <= config.aeSpreadLimit,
            meanGains,
        )
    }

    // ---- rig plumbing ----

    /** Commands [ratio] (clamped to the zoom range) and returns the clamped value. */
    private fun zoomTo(ratio: Float): Float {
        val clamped = ratio.coerceIn(zoomRange.start, zoomRange.endInclusive)
        rig.setZoom(clamped)
        discard = config.discardAfterZoom
        return clamped
    }

    private fun zoomOk(sample: CalibrationSample, ratio: Float): Boolean =
        sample.zoomRatio == null || abs(sample.zoomRatio - ratio) <= config.zoomTolerance * ratio

    /** CONTROL_AE_STATE / CONTROL_AWB_STATE: INACTIVE (0, e.g. manual), CONVERGED (2) and LOCKED (3) are settled. */
    private fun converged(state: Int?): Boolean = state == null || state == 0 || state == 2 || state == 3

    private fun awaitSample(deadlineMs: Long): CalibrationSample? {
        while (true) {
            interruptIfRequested()
            val remaining = deadlineMs - rig.nowMs()
            if (remaining <= 0) return null
            val sample = rig.nextSample(minOf(remaining, config.pollMs)) ?: continue
            if (discard > 0) { discard--; continue }
            return sample
        }
    }

    /** The physical id of [count] consecutive results at [ratio], or null on timeout. */
    private fun awaitStable(ratio: Float, count: Int, timeoutMs: Long): String? {
        val deadline = rig.nowMs() + timeoutMs
        var id: String? = null
        var n = 0
        while (true) {
            val s = awaitSample(deadline) ?: return null
            val sid = s.physicalId
            if (sid != null && zoomOk(s, ratio)) {
                if (sid == id) n++ else { id = sid; n = 1 }
            } else { id = null; n = 0 }
            if (n >= count) return id
        }
    }

    private fun interruptIfRequested() {
        if (cancelled) throw RunAbort(RunOutcome.Cancelled(snapshot()))
        aborted?.let { throw RunAbort(failure(it)) }
    }

    private fun snapshot() = RunProgress(phase, referenceId, rows.toList())
    private fun emit() = onProgress(snapshot())
    private fun failure(reason: RunFailure) = RunOutcome.Failure(reason, snapshot())

    private companion object {
        /** The codec's bound on a stored exposure offset, in stops. */
        const val MAX_EV = 4f
    }
}
