/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.util.Collections

/** Separate exposures, not a merged/HDR image. EV spacing is rational, never rounded. */
enum class BracketStep(val numerator: Int, val denominator: Int) {
    THIRD_EV(1, 3), HALF_EV(1, 2), ONE_EV(1, 1), TWO_EV(2, 1);
    val ev: Double get() = numerator.toDouble() / denominator
}

data class BracketSelection(val count: Int = 3, val step: BracketStep = BracketStep.TWO_EV) {
    init { require(count in setOf(3, 5, 7, 9)) }

    fun resolve(minIndex: Int?, maxIndex: Int?, stepNumerator: Int, stepDenominator: Int,
        aeAvailable: Boolean = true): BracketResolution {
        if (!aeAvailable) return BracketResolution.Rejected(BracketRejection.AE_UNAVAILABLE)
        if (minIndex == null || maxIndex == null || minIndex > maxIndex || stepNumerator <= 0 || stepDenominator <= 0)
            return BracketResolution.Rejected(BracketRejection.COMPENSATION_UNAVAILABLE)
        val numerator = step.numerator.toLong() * stepDenominator
        val denominator = step.denominator.toLong() * stepNumerator
        if (numerator % denominator != 0L) return BracketResolution.Rejected(BracketRejection.INEXACT_STEP)
        val increment = numerator / denominator
        val half = count / 2
        val exposures = (-half..half).map { offset ->
            val index = offset * increment
            if (index < minIndex.toLong() || index > maxIndex.toLong())
                return BracketResolution.Rejected(BracketRejection.OUT_OF_RANGE)
            BracketExposure(index.toInt(), offset * step.ev)
        }
        return BracketResolution.Plan(Collections.unmodifiableList(exposures))
    }
}

enum class BracketRejection { AE_UNAVAILABLE, COMPENSATION_UNAVAILABLE, INEXACT_STEP, OUT_OF_RANGE }
data class BracketExposure(val compensationIndex: Int, val ev: Double)
sealed class BracketResolution {
    data class Plan(val exposures: List<BracketExposure>) : BracketResolution()
    data class Rejected(val reason: BracketRejection) : BracketResolution()
}

data class BracketFrame(
    val index: Int,
    val requestedEv: Double,
    val submittedCompensation: Int,
    val reportedCompensation: Int?,
    val exposureTimeNs: Long?,
    val sensitivityIso: Int?,
    val capture: CapturedStill,
) {
    init {
        require(index in 0..8 && requestedEv.isFinite())
        require(exposureTimeNs == null || exposureTimeNs > 0)
        require(sensitivityIso == null || sensitivityIso > 0)
        require(capture.images.size == 1 && capture.images.single().kind == StillImageKind.JPEG)
    }
}

class CapturedBracket(val id: Long, val selection: BracketSelection, frames: List<BracketFrame>) {
    val frames: List<BracketFrame> = Collections.unmodifiableList(frames.toList())
    init {
        require(id > 0 && this.frames.size == selection.count)
        require(this.frames.map { it.index } == (0 until selection.count).toList())
        require(this.frames.map { it.capture.captureId }.distinct().size == selection.count)
        require(this.frames.zipWithNext().all { (a, b) -> a.capture.sensorTimestampNs < b.capture.sensorTimestampNs })
        require(this.frames.map { it.submittedCompensation }.zipWithNext().all { (a, b) -> a < b })
        require(this.frames.all { it.requestedEv == (it.index - selection.count / 2) * selection.step.ev })
        require(this.frames.sumOf { it.capture.images.single().byteCount.toLong() } <= MAX_ENCODED_BYTES)
    }
    companion object { const val MAX_ENCODED_BYTES = 32 * 1024 * 1024 }
}

/** A tagged repeat must report the requested compensation and settled AE before one still. */
internal class BracketMeteringGate(private val compensation: Int) {
    private var submitted = false
    fun observe(frameNumber: Long, reportedCompensation: Int?, aeState: Int?): Boolean {
        if (submitted || frameNumber < 0 || reportedCompensation != compensation || aeState !in setOf(2, 4)) return false
        // Camera2 AE_STATE_CONVERGED=2, FLASH_REQUIRED=4; neither claims actual brightness.
        submitted = true
        return true
    }
}

/** A failed burst frame does not retire the other requests sharing its admission. */
internal enum class LegacyStillSequenceEvent { FRAME_FAILED, COMPLETED, ABORTED }
internal fun retireLegacyStillSequence(admission: java.util.concurrent.atomic.AtomicReference<Any?>,
    owner: Any, event: LegacyStillSequenceEvent): Boolean =
    event != LegacyStillSequenceEvent.FRAME_FAILED && admission.compareAndSet(owner, null)
