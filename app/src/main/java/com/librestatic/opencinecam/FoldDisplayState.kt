/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

enum class DisplayCapability { UNKNOWN, UNSUPPORTED, UNAVAILABLE, AVAILABLE, ACTIVE }
enum class DisplayOperation { PRESENT, TRANSFER }
enum class DisplaySessionPhase { IDLE, STARTING, ACTIVE }
enum class FoldPosture { NONE_REPORTED, FLAT, TABLETOP, BOOK, SEPARATING }
enum class SubjectDisplayMode { STATUS, TELEPROMPTER, PREVIEW }

data class SubjectDisplaySettings(
    val mode: SubjectDisplayMode = SubjectDisplayMode.STATUS,
    val brightness: Float = 0.7f,
    val touchLocked: Boolean = true,
    val showStatus: Boolean = true,
    val operatorCue: String = "",
    val prompterText: String = "",
    val prompterFontSp: Int = 32,
    val prompterSpeedDpPerSecond: Int = 24,
    val prompterPaused: Boolean = true,
    val continueRecordingOnFold: Boolean = true,
    val adaptToHinge: Boolean = true,
    val swapPanes: Boolean = false,
    val previewMirror: Boolean = true,
    val previewViewAssist: Boolean = true,
    val selfTimerSeconds: Int = 0,
    val selfMinimalControls: Boolean = true,
) {
    init {
        require(brightness.isFinite() && brightness in 0f..1f)
        require(operatorCue.length <= 200)
        require(prompterText.length <= 20_000)
        require(prompterFontSp in 16..72)
        require(prompterSpeedDpPerSecond in 5..120)
        require(selfTimerSeconds in setOf(0, 3, 5, 10))
    }
}

data class FoldHinge(val left: Int, val top: Int, val right: Int, val bottom: Int, val horizontal: Boolean)

data class FoldDisplayState(
    val presentation: DisplayCapability = DisplayCapability.UNKNOWN,
    val transfer: DisplayCapability = DisplayCapability.UNKNOWN,
    val phase: DisplaySessionPhase = DisplaySessionPhase.IDLE,
    val operation: DisplayOperation? = null,
    val visible: Boolean = false,
    val failure: String? = null,
    val posture: FoldPosture = FoldPosture.NONE_REPORTED,
    val hinge: FoldHinge? = null,
)

/** Main-thread state machine. A closed/pending session can never be revived by a late callback. */
class FoldSessionStateMachine {
    var state = FoldDisplayState()
        private set
    private var generation = 0L

    fun capabilities(presentation: DisplayCapability, transfer: DisplayCapability) {
        state = state.copy(presentation = presentation, transfer = transfer)
    }

    fun posture(posture: FoldPosture, hinge: FoldHinge?) { state = state.copy(posture = posture, hinge = hinge) }

    fun begin(operation: DisplayOperation): Long? {
        val capability = if (operation == DisplayOperation.PRESENT) state.presentation else state.transfer
        if (state.phase != DisplaySessionPhase.IDLE || capability != DisplayCapability.AVAILABLE) return null
        state = state.copy(phase = DisplaySessionPhase.STARTING, operation = operation, visible = false, failure = null)
        return ++generation
    }

    fun started(token: Long): Boolean {
        if (token != generation || state.phase != DisplaySessionPhase.STARTING) return false
        state = state.copy(phase = DisplaySessionPhase.ACTIVE)
        return true
    }

    fun visibility(token: Long, visible: Boolean) {
        if (token == generation && state.phase == DisplaySessionPhase.ACTIVE) state = state.copy(visible = visible)
    }

    fun ended(token: Long, failure: String? = null): Boolean {
        if (token != generation) return false
        close(failure)
        return true
    }

    fun close(failure: String? = null) {
        generation++
        state = state.copy(phase = DisplaySessionPhase.IDLE, operation = null, visible = false, failure = failure)
    }
}

/** Hysteresis prevents hinge noise from firing more than once per actual close edge. */
class FoldCloseDetector {
    private var openObserved = false
    fun sample(degrees: Float): Boolean {
        if (!degrees.isFinite() || degrees !in 0f..180f) return false
        if (degrees >= 15f) openObserved = true
        if (degrees <= 5f && openObserved) {
            openObserved = false
            return true
        }
        return false
    }
}
