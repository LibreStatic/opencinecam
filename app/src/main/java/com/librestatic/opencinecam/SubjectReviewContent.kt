/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp

/**
 * OCC-PLAN-068 U4 placeholder. The operator picks the take on the inner screen and the pick
 * arrives as [SubjectSessionCues.reviewUri]; the gallery is never exposed on the subject screen.
 */
@Composable
internal fun SubjectReviewContent(state: CameraUiState, settings: SubjectDisplaySettings, cues: SubjectSessionCues, modifier: Modifier = Modifier) {
    Box(modifier.testTag("subject-review")) {
        Text(stringResource(R.string.subject_review_waiting), color = Color.LightGray, fontSize = 16.sp)
    }
}
