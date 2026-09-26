/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.core.model.CaptureCommand
import com.librestatic.opencinecam.core.model.CaptureState
import com.librestatic.opencinecam.core.model.CaptureStateMachine
import com.librestatic.opencinecam.core.model.Recoverability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the real transition table with the service's real event orders. */
class CaptureStateCommandsTest {
    private val machine = CaptureStateMachine()
    private val commands = CaptureStateCommands("capture-service")
    private val seen = mutableListOf<CaptureState>()

    private fun submit(batch: List<CaptureCommand>): CaptureState {
        batch.forEach { command -> machine.dispatch(command).current.let { if (seen.lastOrNull() != it) seen += it } }
        return machine.state
    }

    private fun previewing() {
        submit(commands.previewOpening("0"))
        submit(commands.previewStarted())
        assertEquals(CaptureState.Previewing("capture-service", "0"), machine.state)
        seen.clear()
    }

    @Test
    fun operatorStoppedTakeReportsEveryRecordingStateAndReturnsToPreview() {
        previewing()
        submit(commands.takeDispatched())
        submit(commands.takeStarted())
        submit(commands.takeStopRequested())
        submit(commands.takeEnded())

        assertEquals(
            listOf(
                CaptureState.PreparingRecording("capture-service", "0", "take-1"),
                CaptureState.Recording("capture-service", "0", "take-1"),
                CaptureState.Stopping("capture-service", "0", "take-1"),
                CaptureState.Previewing("capture-service", "0"),
            ),
            seen,
        )
        // Back-to-back takes from the same preview.
        submit(commands.takeDispatched())
        assertEquals(CaptureState.PreparingRecording("capture-service", "0", "take-2"), machine.state)
    }

    @Test
    fun engineStopWithoutRequestAndLateStopRequestNeverLeavePreview() {
        previewing()
        submit(commands.takeDispatched())
        submit(commands.takeStarted())
        // Auto-stop or sidecar failure: the callback may win against a concurrent stop request.
        submit(commands.takeEnded())
        submit(commands.takeStopRequested())
        submit(commands.takeEnded())
        assertEquals(CaptureState.Previewing("capture-service", "0"), machine.state)
    }

    @Test
    fun rejectedAsyncStartFailsAndRecoversThroughTheMachineRecoveryPath() {
        previewing()
        submit(commands.takeDispatched())
        val failure = commands.engineFailed("video-gpu-prepare-failed", "No hardware video/avc\nencoder", recoverable = true)
        val failed = submit(failure) as CaptureState.Failed
        assertEquals("take-1", failed.failure.correlationId)
        assertEquals("No hardware video/avc encoder", failed.failure.userMessage)
        assertEquals(Recoverability.RETRYABLE, failed.failure.recoverability)
        assertEquals("video-gpu-prepare-failed", failed.failure.details["engineCode"])
        // A late stop callback for the failed take is ignored.
        submit(commands.takeEnded())
        assertTrue(machine.state is CaptureState.Failed)

        // recoverPreview: detach + stopPreview, then prepare/attachPreview and onPreviewStarted.
        submit(commands.takeEnded() + commands.previewStopped())
        assertTrue(machine.state is CaptureState.Failed)
        assertEquals(CaptureState.Opening("capture-service", "0"), submit(commands.previewOpening("0")))
        assertEquals(CaptureState.Previewing("capture-service", "0"), submit(commands.previewStarted()))
        assertEquals(CaptureState.PreparingRecording("capture-service", "0", "take-2"), submit(commands.takeDispatched()))
    }

    @Test
    fun offSpeedSynchronousRejectionFailsInlineAndTheFinallyEndIsANoOp() {
        previewing()
        // dispatchTransferCapture() precedes startVideo(); onFailure runs inline on main,
        // then startVideo() returns false and the finally path reports the take ended.
        submit(commands.takeDispatched())
        submit(commands.engineFailed("log-recording-prepare-failed", "No hardware video/avc", recoverable = true))
        assertEquals(emptyList<CaptureCommand>(), commands.takeEnded())
        assertTrue(machine.state is CaptureState.Failed)
        // Mode change instead of Retry reopens through attachPreview.
        submit(commands.previewOpening("0"))
        assertEquals(CaptureState.Previewing("capture-service", "0"), submit(commands.previewStarted()))
    }

    @Test
    fun rejectedStartWithoutEngineFailureReturnsToPreview() {
        previewing()
        submit(commands.takeDispatched())
        assertEquals(CaptureState.Previewing("capture-service", "0"), submit(commands.takeEnded()))
    }

    @Test
    fun previewReattachAndDetachKeepTheirExistingMeaning() {
        previewing()
        // A reopen (mode/fps change) re-attaches while previewing: nothing changes.
        submit(commands.previewOpening("0"))
        assertEquals(CaptureState.Previewing("capture-service", "0"), submit(commands.previewStarted()))
        assertEquals(CaptureState.Stopped, submit(commands.previewStopped()))
        assertEquals(CaptureState.Opening("capture-service", "0"), submit(commands.previewOpening("0")))
    }
}
