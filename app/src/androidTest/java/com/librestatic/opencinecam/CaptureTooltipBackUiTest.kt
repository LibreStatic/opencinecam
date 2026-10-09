/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TooltipState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A greyed capture slot explains itself in a tooltip whose popup takes no focus. On the capture
 * screen, the root of the app, Back must put that tooltip away rather than leave the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
class CaptureTooltipBackUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var tooltip: TooltipState
    private lateinit var scope: CoroutineScope

    private fun showTooltip() {
        rule.setContent {
            MaterialTheme {
                tooltip = rememberTooltipState(isPersistent = true)
                scope = rememberCoroutineScope()
                CaptureTooltip("Shutter", "This camera does not offer manual exposure.", state = tooltip) {
                    Box(Modifier.size(48.dp))
                }
            }
        }
        rule.runOnIdle { assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
        rule.runOnIdle { scope.launch { tooltip.show() } }
        rule.waitUntil(5_000) { tooltip.isVisible }
    }

    @Test
    fun backDismissesAVisibleTooltipAndKeepsTheActivity() {
        showTooltip()
        rule.runOnIdle {
            assertTrue(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
            rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        rule.waitUntil(5_000) { !tooltip.isVisible }
        rule.runOnIdle {
            assertFalse(rule.activity.isFinishing)
            // Hidden again, the tooltip no longer holds Back.
            assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        }
    }
}
