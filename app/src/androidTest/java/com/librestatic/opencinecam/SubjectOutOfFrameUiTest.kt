/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import com.librestatic.opencinecam.camera.FramingEdge
import com.librestatic.opencinecam.camera.SubjectFramingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/** OCC-PLAN-068 U7: the warning is output-only, delayed by the preference and readable at 200 % font. */
class SubjectOutOfFrameUiTest {
    @get:Rule val compose = createComposeRule()

    private fun absentFor(seconds: Long) = SubjectFramingStatus(
        supported = true, detecting = true, facePresentInFrame = false,
        lastSeenMonotonicNanos = SystemClock.elapsedRealtimeNanos() - seconds * 1_000_000_000L,
        exitEdge = FramingEdge.LEFT, evaluated = true,
    )

    @Test fun warningAppearsAfterTheDelayAtDoubleFontScaleWithoutActions() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme {
                    SubjectDisplayScreen(
                        CameraUiState(phase = CameraUiPhase.RECORDING, subjectFraming = absentFor(5)),
                        SubjectDisplaySettings(outOfFrameWarning = true, outOfFrameDelaySeconds = 2),
                    )
                }
            }
        }
        compose.onNodeWithTag("subject-out-of-frame").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun noWarningWhenDisabledOrStillWithinTheDelay() {
        compose.setContent {
            MaterialTheme {
                SubjectDisplayScreen(
                    CameraUiState(phase = CameraUiPhase.RECORDING, subjectFraming = absentFor(0)),
                    SubjectDisplaySettings(outOfFrameWarning = true, outOfFrameDelaySeconds = 10),
                )
                SubjectDisplayScreen(
                    CameraUiState(phase = CameraUiPhase.RECORDING, subjectFraming = absentFor(30)),
                    SubjectDisplaySettings(outOfFrameWarning = false),
                )
            }
        }
        compose.onAllNodesWithTag("subject-out-of-frame").assertCountEquals(0)
    }

    @Test fun settingsExplainAMissingCapability() {
        compose.setContent { MaterialTheme { SubjectOutOfFrameSettings(CameraUiState(), SubjectDisplaySettings()) {} } }
        compose.onNodeWithTag("subject-out-of-frame-unavailable").assertIsDisplayed()
    }

    @Test fun aFramingOnlyChangeReachesTheSubjectWindow() {
        // Razr U8: the operator state reached the cover only on phase/mode/countdown changes, so a
        // face entering the frame never cleared the subject banner.
        var operator by mutableStateOf(CameraUiState(phase = CameraUiPhase.PREVIEWING, subjectFraming = absentFor(5)))
        val subject = MutableStateFlow(CameraUiState())
        compose.setContent {
            SubjectStateForwarder(operator) { subject.value = it }
            val forwarded by subject.collectAsState()
            MaterialTheme { SubjectDisplayScreen(forwarded, SubjectDisplaySettings(outOfFrameWarning = true, outOfFrameDelaySeconds = 2)) }
        }
        compose.onNodeWithTag("subject-out-of-frame").assertIsDisplayed()
        operator = operator.copy(subjectFraming = operator.subjectFraming.copy(
            facePresentInFrame = true, lastSeenMonotonicNanos = SystemClock.elapsedRealtimeNanos(), exitEdge = FramingEdge.NONE))
        compose.waitForIdle()
        compose.onAllNodesWithTag("subject-out-of-frame").assertCountEquals(0)
    }
}

