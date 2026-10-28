/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Software ISO control for the emulated FPS LOCK hold: shutter fixed at 1/fps with AE off, so the
 * ISO has to follow the scene by itself. It keeps the mean luma of the analysis frames at the value
 * learned from the first settled held frames.
 *
 * Measure, then plan: on a settled analysis frame ([update]) the ratio target / measured gives a
 * desired ISO of current x ratio^[gamma] (luma is gamma-encoded, so the linear error is about the
 * ratio squared), limited to [minPlan]..[maxPlan] times the current ISO, with a [deadband] around
 * the target. The plan is then walked by [tick], called from every capture result: each issue moves
 * the ISO by at most [maxRampStep] and is at least [minTickNs] after the previous one, and the last
 * one lands exactly on the plan once within [finishWithin]. Analysis frames are ignored while ramping
 * and for [rampSettleNs] after it, and for [settleNs] after engaging. With no learned target, or no
 * frames, the ISO stays where it is. Pure; the caller supplies the clock and the mean luma (0..1).
 *
 * A physical lens switch of the logical camera ([lensChanged]) is different: with AE off the new lens
 * gets the same manual ISO, and the lenses differ in sensitivity by over 10x on the Razr Fold. The
 * loop jumps straight to the ISO a remembered ratio predicts, then corrects once, in one jump within
 * [lensJumpMin]..[lensJumpMax], from the first analysis frame of the new lens. Frames of the
 * [lensContentSettleNs] after the switch frame are not measured: the HAL still matches the new lens'
 * brightness to the old one's then, so they do not follow the ISO. A jump reaches the sensor only
 * after the HAL's in-flight requests (8 on the Razr Fold, over 130 ms at 60 fps), so after one the
 * loop waits for the capture result that reports it ([noteResult]) and measures only frames exposed
 * from then on; frames without a sensor timestamp fall back to [lensSettleNs].
 *
 * The ratio the caller remembers (see [LensIsoRatios]) is only learned between two lenses that each
 * held the target: the ISO of the old lens' last frame on target against the new lens' first one.
 * A first frame far off target says little about the ratio (the luma model is coarse there, and a
 * jump may hit [lensJumpMax] or the ISO range), so it only steers the jump.
 */
class CadenceIsoLoop(
    private val deadband: Double = 0.06,
    private val gamma: Double = 2.0,
    private val minPlan: Double = 0.5,
    private val maxPlan: Double = 2.0,
    private val maxRampStep: Double = 0.04,
    private val finishWithin: Double = 0.01,
    private val minTickNs: Long = 66_000_000L,
    private val rampSettleNs: Long = 250_000_000L,
    private val settleNs: Long = 200_000_000L,
    private val lensSettleNs: Long = 150_000_000L,
    private val lensContentSettleNs: Long = 250_000_000L,
    private val lensJumpMin: Double = 0.25,
    private val lensJumpMax: Double = 4.0,
) {
    private var settleUntilNs = 0L
    private var targetLuma: Double? = null
    private val learned = ArrayList<Double>()
    private var iso = 0.0
    private var issuedIso = 0
    private var desiredIso: Double? = null
    /** Where the target came from: "learned" (the first settled held frames) or null. */
    var targetSource: String? = null
        @Synchronized get
        private set
    val target: Double? @Synchronized get() = targetLuma
    private var lastIssueNs: Long? = null
    private var isoRange: IntRange = 1..Int.MAX_VALUE
    /** An ISO to issue at the next [tick] at once, past the ramp's pacing (a lens switch). */
    private var jumpIso: Int? = null
    /** The ISO that held the target on the lens before the switch, while the new lens is still unmeasured. */
    private var lensBaseIso: Double? = null
    /** ISO x (target / luma)^gamma of the last settled frame: what would have held the target then. */
    private var isoAtTarget: Double? = null
    /** The same, from the current lens' latest frame if it was within the deadband; null otherwise. */
    private var convergedIso: Double? = null
    /** The old lens' [convergedIso] at the last switch, until the new lens holds the target too. */
    private var ratioBaseIso: Double? = null
    /** A jumped ISO not yet reported by a capture result, and when it was issued. */
    private var awaitingIso: Int? = null
    private var awaitingSinceNs = 0L
    /** Frames exposed before this sensor timestamp are ignored: the old lens', or the ISO before a jump. */
    private var validFromFrameNs = Long.MIN_VALUE
    /** The same for frames without a timestamp, by the clock. */
    private var lensSettleUntilNs = 0L

    /**
     * Starts holding at [initialIso], which already keeps the automatic exposure's ISO x time, so the
     * first settled held frames have its brightness. The target is learned from them (see [update]),
     * never from frames taken before the hold, which may still show the automatic exposure converging.
     */
    @Synchronized
    fun engage(initialIso: Int, range: IntRange, nowNs: Long = 0L) {
        isoRange = range
        iso = initialIso.coerceIn(range).toDouble()
        issuedIso = iso.toInt()
        desiredIso = null
        learned.clear()
        lastIssueNs = null
        settleUntilNs = nowNs + settleNs
        targetLuma = null
        targetSource = null
        jumpIso = null
        lensBaseIso = null
        awaitingIso = null
        validFromFrameNs = Long.MIN_VALUE
        lensSettleUntilNs = 0L
        isoAtTarget = null
        convergedIso = null
        ratioBaseIso = null
    }

    /**
     * The exposure ramp landed at [newIso]: continue from there with a fresh [settleNs] before the
     * luma may plan again. A target learned meanwhile is kept.
     */
    @Synchronized
    fun resume(newIso: Int, nowNs: Long) {
        iso = newIso.coerceIn(isoRange).toDouble()
        issuedIso = iso.toInt()
        desiredIso = null
        lastIssueNs = null
        settleUntilNs = nowNs + settleNs
        jumpIso = null
        lensBaseIso = null
        awaitingIso = null
        validFromFrameNs = Long.MIN_VALUE
        lensSettleUntilNs = 0L
    }

    /** Sets the luma to hold from outside (measured on AE's last frames), instead of learning it. */
    @Synchronized
    fun setTarget(luma: Double, source: String = "ae") {
        if (!luma.isFinite() || luma < MIN_LUMA || luma > 1.0) return
        targetLuma = luma
        targetSource = source
        learned.clear()
    }

    /** True once the settle window after engaging (or after a ramp) has passed. */
    @Synchronized
    fun isSettled(nowNs: Long): Boolean = nowNs >= settleUntilNs

    @Synchronized
    fun release() {
        targetLuma = null
        targetSource = null
        desiredIso = null
        learned.clear()
        lastIssueNs = null
        jumpIso = null
        lensBaseIso = null
        awaitingIso = null
        validFromFrameNs = Long.MIN_VALUE
        lensSettleUntilNs = 0L
        isoAtTarget = null
        convergedIso = null
        ratioBaseIso = null
    }

    @Synchronized
    fun reset() { release() }

    /**
     * The logical camera switched physical lens. Any ramp is dropped and frames are ignored for
     * [lensSettleNs] and those exposed before [lensContentSettleNs] past [switchFrameNs], the switch's
     * own frame (those still in flight are the old lens', and the HAL's transition follows).
     * [expectedRatio] (ISO on the new lens / ISO on the old one for the same brightness), when known,
     * is applied at once from the ISO that held the target on the old lens. Without a target the
     * lens' frames only restart the learning.
     */
    @Synchronized
    fun lensChanged(nowNs: Long, expectedRatio: Double? = null, switchFrameNs: Long? = null) {
        desiredIso = null
        lastIssueNs = null
        lensSettleUntilNs = nowNs + lensSettleNs
        awaitingIso = null
        validFromFrameNs = switchFrameNs?.plus(lensContentSettleNs) ?: Long.MIN_VALUE
        // A second switch before the new lens held the target leaves no base: nothing is learned.
        ratioBaseIso = convergedIso
        convergedIso = null
        if (targetLuma == null) { learned.clear(); return }
        val base = lensBaseIso ?: isoAtTarget ?: iso
        lensBaseIso = base
        isoAtTarget = null
        if (expectedRatio != null && expectedRatio.isFinite() && expectedRatio > 0.0) jump(base * expectedRatio)
    }

    /**
     * A capture result: once one exposed at [frameNs] reports the jumped ISO (the effective one, sensor
     * x post-raw boost, within [EFFECT_TOLERANCE]), frames from [frameNs] on show it. True at that one.
     */
    @Synchronized
    fun noteResult(frameNs: Long, effectiveIso: Int): Boolean {
        val awaited = awaitingIso ?: return false
        if (abs(effectiveIso.toDouble() / awaited - 1.0) > EFFECT_TOLERANCE) return false
        awaitingIso = null
        validFromFrameNs = if (validFromFrameNs == Long.MAX_VALUE) frameNs else maxOf(validFromFrameNs, frameNs)
        return true
    }

    /** True while a jump has been issued but no capture result has reported it yet. */
    val awaitingEffect: Boolean @Synchronized get() = awaitingIso != null

    /** True while the lens just switched to has not been measured yet. */
    val lensPending: Boolean @Synchronized get() = lensBaseIso != null

    /**
     * Gain that makes a frame shot at [effectiveIso] look shot at the ISO a jump is bringing in,
     * while the jump is on its way (1 otherwise): the frames a lens switch hands over still carry
     * the old lens' ISO for the HAL's pipeline depth. Call it after [noteResult] for the same result.
     */
    @Synchronized fun frameGain(effectiveIso: Int): Double {
        val wanted = jumpIso ?: awaitingIso ?: return 1.0
        if (effectiveIso <= 0) return 1.0
        return (wanted.toDouble() / effectiveIso).coerceIn(MIN_FRAME_GAIN, MAX_FRAME_GAIN)
    }

    private fun jump(to: Double) {
        iso = to.coerceIn(isoRange.first.toDouble(), isoRange.last.toDouble())
        val next = iso.toInt().coerceIn(isoRange)
        if (next != issuedIso) jumpIso = next
    }

    val hasTarget: Boolean @Synchronized get() = targetLuma != null

    /**
     * Feeds one analysis frame. After the settle window the first [LEARN_FRAMES] valid frames only
     * teach the target (their median, so one outlier is rejected); then a frame off target plans a
     * ramp for [tick]. [targetScale] scales the target (exposure compensation: 2^EV). Returns the ISO
     * ratio from the lens before the last switch, once, at the new lens' first frame on target.
     */
    @Synchronized
    fun update(nowNs: Long, luma: Double, targetScale: Double = 1.0, frameNs: Long? = null): Double? {
        if (desiredIso != null || jumpIso != null) return null
        if (awaitingIso != null) {
            // Never reported (a HAL that rounds or caps the ISO): after a while, go by the clock alone.
            if (nowNs - awaitingSinceNs < EFFECT_TIMEOUT_NS) return null
            awaitingIso = null
            validFromFrameNs = Long.MIN_VALUE
            lensSettleUntilNs = 0L
        }
        if (nowNs < settleUntilNs) return null
        if (frameNs != null) { if (frameNs < validFromFrameNs) return null } else if (nowNs < lensSettleUntilNs) return null
        if (!luma.isFinite() || luma < MIN_LUMA || luma > 1.0) return null
        val learnedTarget = targetLuma
        if (learnedTarget == null) {
            learned.add(luma)
            if (learned.size >= LEARN_FRAMES) {
                targetLuma = learned.sorted()[learned.size / 2]
                targetSource = "learned"
                learned.clear()
            }
            return null
        }
        val ratio = learnedTarget.times(targetScale).coerceIn(MIN_LUMA, 1.0) / luma
        if (lensBaseIso != null) {
            // First frame of the new lens: one jump to the ISO that holds the target.
            lensBaseIso = null
            if (abs(ratio - 1.0) > deadband) {
                val needed = iso * ratio.pow(gamma).coerceIn(lensJumpMin, lensJumpMax)
                isoAtTarget = needed
                jump(needed)
                lensSettleUntilNs = nowNs + lensSettleNs
                // Until the jump is reported, nothing newer than this frame is known to show it.
                validFromFrameNs = Long.MAX_VALUE
                return null
            }
        }
        isoAtTarget = iso * ratio.pow(gamma)
        if (abs(ratio - 1.0) <= deadband) {
            convergedIso = isoAtTarget
            val base = ratioBaseIso ?: return null
            ratioBaseIso = null
            return isoAtTarget!! / base
        }
        // Off target: the ISO that held it no longer does (the scene changed, or a ramp is under way).
        convergedIso = null
        val planned = (iso * ratio.pow(gamma).coerceIn(minPlan, maxPlan)).coerceIn(isoRange.first.toDouble(), isoRange.last.toDouble())
        if (abs(planned / iso - 1.0) > finishWithin) {
            desiredIso = planned
        }
        return null
    }

    /** The next ISO of the ramp to re-issue, or null when there is nothing to send now. Call per capture result. */
    @Synchronized
    fun tick(nowNs: Long): Int? {
        jumpIso?.let { next ->
            jumpIso = null
            issuedIso = next
            lastIssueNs = nowNs
            awaitingIso = next
            awaitingSinceNs = nowNs
            return next
        }
        val goal = desiredIso ?: return null
        val last = lastIssueNs
        if (last != null && nowNs - last < minTickNs) return null
        val step = maxRampStep
        val done = abs(goal / iso - 1.0) <= finishWithin
        iso = if (done) goal else iso * (goal / iso).coerceIn(1.0 - step, 1.0 + step)
        iso = iso.coerceIn(isoRange.first.toDouble(), isoRange.last.toDouble())
        lastIssueNs = nowNs
        if (done) {
            desiredIso = null
            settleUntilNs = nowNs + rampSettleNs
        }
        val next = iso.toInt().coerceIn(isoRange)
        if (next == issuedIso) return null
        issuedIso = next
        return next
    }

    /** True while a planned ramp has not landed yet. */
    val ramping: Boolean @Synchronized get() = desiredIso != null

    companion object {
        const val BURST_PERIOD_MS = 33L
        const val BURST_AFTER_ENGAGE_NS = 1_500_000_000L
        const val MIN_LUMA = 0.002
        const val LEARN_FRAMES = 3
        /** A capture result "reports" a jumped ISO within this fraction (the sensor and boost split rounds). */
        const val EFFECT_TOLERANCE = 0.03
        const val MIN_FRAME_GAIN = 0.25
        const val MAX_FRAME_GAIN = 4.0
        /** How long a jump may go unreported before the loop measures by the clock alone. */
        const val EFFECT_TIMEOUT_NS = 500_000_000L

        /** Sensitivity range extended by the post-raw boost, never beyond 8x the sensor maximum. */
        fun extendedRange(isoRange: IntRange, boostRange: IntRange?): IntRange {
            if (boostRange == null || boostRange.last <= 100) return isoRange
            val top = (isoRange.last.toLong() * boostRange.last / 100).coerceAtMost(8L * isoRange.last).toInt()
            return isoRange.first..top
        }

        /** The highest sensor ISO every lens reaches: [logicalTop], lowered to the lowest of [physicalTops] at or above [floor]. */
        fun sharedSensorTop(logicalTop: Int, physicalTops: List<Int>, floor: Int = 800): Int =
            physicalTops.filter { it >= floor }.minOrNull()?.coerceAtMost(logicalTop) ?: logicalTop

        /** Splits an effective ISO into SENSOR_SENSITIVITY and CONTROL_POST_RAW_SENSITIVITY_BOOST (null: no boost key). */
        fun split(iso: Int, isoRange: IntRange, boostRange: IntRange?): Pair<Int, Int?> {
            if (boostRange == null) return iso.coerceIn(isoRange) to null
            if (iso <= isoRange.last) return iso.coerceIn(isoRange) to 100.coerceIn(boostRange)
            return isoRange.last to Math.round(100.0 * iso / isoRange.last).toInt().coerceIn(boostRange)
        }

        /** ISO that keeps the exposure the auto frame had, now that the exposure time is [heldExposureNs]. */
        fun initialIso(autoIso: Int, autoExposureNs: Long?, heldExposureNs: Long, range: IntRange, autoBoost: Int = 100): Int {
            // The automatic exposure's total gain is the sensor ISO times its post-raw (ISP digital) boost.
            val gain = autoIso.toDouble() * autoBoost.coerceAtLeast(100) / 100.0
            val scaled = if (autoExposureNs != null && autoExposureNs > 0 && heldExposureNs > 0)
                gain * autoExposureNs / heldExposureNs else gain
            return scaled.toInt().coerceIn(range)
        }

        /** Mean of a luma histogram, bin-index weighted and normalised to 0..1; null when empty. */
        fun meanLuma(histogram: List<Float>): Double? {
            if (histogram.size < 2) return null
            var total = 0.0
            var weighted = 0.0
            histogram.forEachIndexed { i, v -> if (v > 0f) { total += v; weighted += v * i } }
            return if (total > 0.0) weighted / total / (histogram.size - 1) else null
        }

        fun evScale(step: Float, indexDelta: Int): Double = 2.0.pow((step * indexDelta).toDouble())
    }
}

/**
 * Whether analysis should run at the burst rate: only for the emulated hold on a live pipeline, from
 * the moment the guard is timing a stretch until the loop has learned its target and landed its ramp
 * (at most [CadenceIsoLoop.BURST_AFTER_ENGAGE_NS] after engaging), and while a lens just switched to
 * is still unmeasured.
 */
internal fun cadenceBurstWanted(
    emulatedEligible: Boolean,
    suspended: Boolean,
    engaged: Boolean,
    guardPending: Boolean,
    sinceEngageNs: Long,
    loopLearning: Boolean,
    loopRamping: Boolean,
    lensPending: Boolean = false,
): Boolean = emulatedEligible && !suspended &&
    if (engaged) lensPending || sinceEngageNs < CadenceIsoLoop.BURST_AFTER_ENGAGE_NS && (loopLearning || loopRamping) else guardPending

/** Analysis period: the burst period when bursting, never slower than [normalMs]. */
internal fun analysisPeriodMs(normalMs: Long, burst: Boolean): Long =
    if (burst) minOf(normalMs, CadenceIsoLoop.BURST_PERIOD_MS) else normalMs

/**
 * Walks the exposure of the emulated hold from the automatic exposure's own value down to the held
 * 1/fps shutter, so the handoff changes nothing at its first frame. Geometric steps, at least
 * [stepNs] apart, over about [durationNs]; the ISO follows inversely (exposure x ISO constant,
 * clamped to the ISO range) and the frame duration follows the exposure (see [frameDurationNs]).
 * Pure; the caller supplies the clock and calls [tick] per capture result.
 */
class CadenceExposureRamp(
    private val durationNs: Long = 400_000_000L,
    private val stepNs: Long = 33_000_000L,
) {
    data class Step(val exposureNs: Long, val iso: Int, val done: Boolean)

    private var startExposure = 0.0
    private var startIso = 0.0
    private var heldExposure = 0L
    private var isoRange: IntRange = 1..Int.MAX_VALUE
    private var steps = 0
    private var index = 0
    private var lastNs = 0L
    private var running = false
    val active: Boolean @Synchronized get() = running
    var exposureNs: Long = 0L
        @Synchronized get
        private set
    var iso: Int = 0
        @Synchronized get
        private set
    val totalSteps: Int @Synchronized get() = steps

    /** Begins at the automatic values; false (nothing to ramp) when the automatic exposure is not longer than [heldExposureNs]. */
    @Synchronized
    fun start(startExposureNs: Long, startIso: Int, heldExposureNs: Long, range: IntRange, nowNs: Long): Boolean {
        running = false
        if (startExposureNs <= heldExposureNs || heldExposureNs <= 0L || startIso <= 0) return false
        isoRange = range
        this.startExposure = startExposureNs.toDouble()
        this.startIso = startIso.coerceIn(range).toDouble()
        heldExposure = heldExposureNs
        steps = ((durationNs + stepNs - 1) / stepNs).toInt().coerceAtLeast(1)
        index = 0
        lastNs = nowNs
        exposureNs = startExposureNs
        iso = this.startIso.toInt()
        running = true
        return true
    }

    /** The next step when one is due, else null. [Step.done] marks the landing on the held exposure. */
    @Synchronized
    fun tick(nowNs: Long): Step? {
        if (!running || nowNs - lastNs < stepNs) return null
        lastNs = nowNs
        index++
        val done = index >= steps
        val exposure = if (done) heldExposure.toDouble() else startExposure * (heldExposure / startExposure).pow(index.toDouble() / steps)
        exposureNs = exposure.toLong()
        iso = (startIso * startExposure / exposure).toInt().coerceIn(isoRange)
        if (done) running = false
        return Step(exposureNs, iso, done)
    }

    @Synchronized
    fun stop() { running = false }

    companion object {
        /** The frame lasts as long as the exposure plus 100 us, never shorter than the nominal frame. */
        fun frameDurationNs(exposureNs: Long, fps: Int): Long =
            maxOf(exposureNs + 100_000L, 1_000_000_000L / fps.coerceAtLeast(1))
    }
}

/** Only one analysis producer may drive the cadence loop, so its luma is in one domain: the GPU readback when there is a GPU pipeline, else the YUV reader. */
internal fun cadenceLumaProducerAccepted(hasGpuPipeline: Boolean, producer: String): Boolean =
    producer == if (hasGpuPipeline) "gpu" else "yuv"

/**
 * The ISP's gain that AE ON applies and AE OFF loses (about 2.2 to 2.4x on the Razr Fold, measured
 * as the same exposure and ISO giving a luma 2.4x darker with AE OFF; it is in no metadata, the
 * post-raw boost reads 100). Kept per camera id in memory, learned from each emulated handoff.
 */
class HiddenAeGain {
    private val gains = java.util.concurrent.ConcurrentHashMap<String, Double>()

    fun gain(cameraId: String): Double = gains[cameraId] ?: HIDDEN_AE_GAIN_PRIOR

    /** Folds one measurement in (see [next]); returns the new gain. */
    fun update(cameraId: String, targetLuma: Double, measuredLuma: Double): Double =
        next(gain(cameraId), targetLuma, measuredLuma).also { gains[cameraId] = it }

    companion object {
        /** Razr Fold measurement: AE ON luma 0.3468 vs AE OFF luma 0.1448 at the same exposure and about the same ISO (~2.4x; 2.2 is conservative). */
        const val HIDDEN_AE_GAIN_PRIOR = 2.2
        const val MIN_GAIN = 1.0
        const val MAX_GAIN = 4.0

        /** G x (target / luma)^2, each update within 0.5x..2x of G, the result within [MIN_GAIN]..[MAX_GAIN]. */
        fun next(old: Double, targetLuma: Double, measuredLuma: Double): Double {
            if (!targetLuma.isFinite() || !measuredLuma.isFinite() || targetLuma <= 0.0 || measuredLuma <= 0.0) return old
            val factor = (targetLuma / measuredLuma).pow(2.0).coerceIn(0.5, 2.0)
            return (old * factor).coerceIn(MIN_GAIN, MAX_GAIN)
        }

        /** The hold's starting ISO: AE's effective ISO times the hidden gain, inside [range]. */
        fun startIso(aeEffectiveIso: Int, gain: Double, range: IntRange): Int =
            (aeEffectiveIso * gain).toInt().coerceIn(range)
    }
}

internal val processHiddenAeGain = HiddenAeGain()

/**
 * ISO ratios between the physical lenses of a logical camera under the emulated hold: the ISO on lens
 * `to` over the ISO on lens `from` for the same brightness, as [CadenceIsoLoop.update] measures it at
 * each switch. Kept per logical camera id in memory, folded in log space with weight [WEIGHT], each
 * measurement within [MIN_RATIO]..[MAX_RATIO]; the reverse direction is the inverse.
 */
class LensIsoRatios {
    private val logRatios = java.util.concurrent.ConcurrentHashMap<String, Double>()

    fun ratio(cameraId: String, from: String, to: String): Double? = logRatios[key(cameraId, from, to)]?.let { exp(it) }

    /** Folds one measurement in; returns the remembered ratio. */
    fun update(cameraId: String, from: String, to: String, measured: Double): Double? {
        if (from == to || !measured.isFinite() || measured < MIN_RATIO || measured > MAX_RATIO) return ratio(cameraId, from, to)
        val next = logRatios[key(cameraId, from, to)]?.let { it + (ln(measured) - it) * WEIGHT } ?: ln(measured)
        logRatios[key(cameraId, from, to)] = next
        logRatios[key(cameraId, to, from)] = -next
        return exp(next)
    }

    private fun key(cameraId: String, from: String, to: String) = "$cameraId|$from|$to"

    companion object {
        const val WEIGHT = 0.5
        const val MIN_RATIO = 0.125
        const val MAX_RATIO = 8.0
    }
}

internal val processLensIsoRatios = LensIsoRatios()
