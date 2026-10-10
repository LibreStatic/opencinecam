package com.librestatic.opencinecam.camera

/**
 * Qualcomm `org.codeaurora.qcamera3.instant_aec` support: a short-lived request mode that makes the
 * AE converge in a few frames when a preview session starts or the lens changes.
 *
 * Evidence (Motorola Razr Fold, vendor_qti HAL, `dumpsys media.camera`): static key
 * `instant_aec_available_modes` (0x80c90001, int32[3]) = [0 1 2] on every camera id and request key
 * `instant_aec_mode` (0x80c90000, int32). The values follow the QTI `QCAMERA3_INSTANT_AEC_*` enum:
 * 0 = normal convergence, 1 = aggressive convergence, 2 = fast convergence. Other Qualcomm devices
 * may advertise a different set, so only an advertised mode is ever requested and 0 is always the
 * value that restores normal behaviour.
 */
internal object InstantAec {
    const val MODE_KEY = "org.codeaurora.qcamera3.instant_aec.instant_aec_mode"
    const val AVAILABLE_MODES_KEY = "org.codeaurora.qcamera3.instant_aec.instant_aec_available_modes"
    const val MODE_NORMAL = 0
    const val MODE_AGGRESSIVE = 1
    const val MODE_FAST = 2
    const val TIMEOUT_MS = 1_000L

    /** A boost inside a take is kept short: it only has to bridge one lens switch. */
    const val TAKE_TIMEOUT_MS = 500L

    /**
     * The boost mode to use for an advertised set, or null when none applies. Outside a take
     * aggressive is preferred, then fast. Inside a take only fast is used: aggressive convergence can
     * overshoot, which is harmless in the viewfinder but would be recorded as a brightness pump.
     */
    fun boostMode(available: IntArray?, recording: Boolean = false): Int? = when {
        available == null -> null
        !recording && MODE_AGGRESSIVE in available -> MODE_AGGRESSIVE
        MODE_FAST in available -> MODE_FAST
        else -> null
    }

    /** Only automatic, unlocked AE outside a constrained high-speed session may be boosted. */
    fun eligible(highSpeed: Boolean, manualExposure: Boolean, aeLocked: Boolean): Boolean =
        !highSpeed && !manualExposure && !aeLocked

    /**
     * True when a logical camera moved to another physical lens, e.g. zoom crossing a switchover
     * ratio. The first report of a session (no [previous]) is not a switch.
     */
    fun physicalLensSwitched(previous: String?, current: String?): Boolean =
        previous != null && current != null && previous != current
}

/**
 * Pure state machine for one boost: engage, then release on AE convergence or after the timeout.
 * Convergence only counts on a result whose request carried the boost, so a converged frame still in
 * flight from before the boost cannot end it at once.
 */
internal class InstantAecMachine(
    private val timeoutMs: Long = InstantAec.TIMEOUT_MS,
    private val takeTimeoutMs: Long = InstantAec.TAKE_TIMEOUT_MS,
) {
    enum class Release { CONVERGED, TIMEOUT, INELIGIBLE, TAKE_CHANGED }

    private var startedAtMs: Long? = null
    private var startedInTake = false

    /** The boost mode on the repeating request while [active]. */
    var mode: Int? = null
        private set
    val active: Boolean get() = startedAtMs != null

    /** True when the caller should put [boostMode] on the repeating request now. */
    fun begin(nowMs: Long, boostMode: Int?, eligible: Boolean, recording: Boolean = false): Boolean {
        if (active || boostMode == null || !eligible) return false
        startedAtMs = nowMs
        startedInTake = recording
        mode = boostMode
        return true
    }

    /**
     * Non-null when the boost must be released; the [Long] is then the time since [begin]. A take
     * starting or ending mid-boost releases it, because the mode was chosen for the other state.
     */
    fun onResult(nowMs: Long, aeState: Int?, eligible: Boolean, recording: Boolean = false, boostApplied: Boolean = true): Pair<Release, Long>? {
        val start = startedAtMs ?: return null
        val elapsed = nowMs - start
        val release = when {
            !eligible -> Release.INELIGIBLE
            recording != startedInTake -> Release.TAKE_CHANGED
            boostApplied && (aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_LOCKED) -> Release.CONVERGED
            elapsed >= if (startedInTake) takeTimeoutMs else timeoutMs -> Release.TIMEOUT
            else -> return null
        }
        reset()
        return release to elapsed
    }

    fun reset() {
        startedAtMs = null
        mode = null
    }
}
