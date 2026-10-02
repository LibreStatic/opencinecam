/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.TimecodeRate
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** OCC-PLAN-068 U6 slate and sync flash. Written and compiled in W1; run in the W2 emulator regression. */
class SubjectSlateUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val slate = ProductionSlateSettings(project = "Lumen", scene = "12A", camera = "B", reel = "R3", takeNumber = 7)

    @Test fun slateShowsSelectedFieldsAtDoubleFontScaleWithoutActions() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme {
                    SubjectDisplayScreen(
                        CameraUiState(phase = CameraUiPhase.RECORDING, timecodeDisplay = "01:00:10:00"),
                        SubjectDisplaySettings(mode = SubjectDisplayMode.SLATE, showStatus = false,
                            slateFields = setOf(SubjectSlateField.SCENE, SubjectSlateField.TAKE, SubjectSlateField.TIMECODE)),
                        productionSlate = slate, timecodeRate = TimecodeRate(25),
                    )
                }
            }
        }
        compose.onNodeWithTag("subject-slate").assertIsDisplayed()
        compose.onNodeWithText("12A").assertIsDisplayed()
        compose.onNodeWithText("7").assertIsDisplayed()
        compose.onNodeWithTag("subject-slate-timecode").assertIsDisplayed()
        compose.onAllNodesWithTag("subject-slate-project").assertCountEquals(0)
        compose.onAllNodesWithTag("subject-slate-reel").assertCountEquals(0)
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun longFieldLabelsShrinkToTheCellInsteadOfEllipsizing() {
        // Razr U8: "CÓDIGO DE TIEMPO" ellipsized on the 1080 px cover. A narrow cell forces the same case in any locale.
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(150.dp, 700.dp)) {
                    SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.PREVIEWING),
                        SubjectDisplaySettings(mode = SubjectDisplayMode.SLATE, showStatus = false), productionSlate = slate)
                }
            }
        }
        val label = context.getString(R.string.subject_slate_field_timecode).uppercase(Locale.getDefault())
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("label wrapped or ellipsized at ${layout.layoutInput.style.fontSize}", layout.hasVisualOverflow)
    }

    @Test fun idleSlateDoesNotInventATimecode() {
        compose.setContent {
            MaterialTheme {
                SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.PREVIEWING, timecodeDisplay = "01:00:10:00"),
                    SubjectDisplaySettings(mode = SubjectDisplayMode.SLATE), productionSlate = slate)
            }
        }
        compose.onNodeWithText(SLATE_TIMECODE_IDLE).assertIsDisplayed()
    }

    @Test fun syncFlashCoversTheSubjectScreen() {
        compose.setContent {
            MaterialTheme {
                SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.RECORDING),
                    SubjectDisplaySettings(mode = SubjectDisplayMode.SLATE), productionSlate = slate, syncFlash = true)
            }
        }
        compose.onNodeWithTag("subject-sync-flash").assertIsDisplayed()
    }

    @Test fun settingsCardStatesTheBeepIsRecorded() {
        compose.setContent { MaterialTheme { SubjectSlateSettings(CameraUiState(), SubjectDisplaySettings()) {} } }
        compose.onNodeWithTag("subject-slate-beep-warning").assertExists()
        compose.onNodeWithText(context.getString(R.string.subject_slate_sync_beep)).assertExists()
    }
}
