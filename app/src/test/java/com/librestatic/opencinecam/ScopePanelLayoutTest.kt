/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopePanelLayoutTest {
    @Test fun tabsFollowTheEnabledScopesInOrder() {
        assertEquals(listOf(ScopeTab.WAVEFORM, ScopeTab.FALSE_COLOR), enabledScopeTabs(true, false, true, false))
        assertEquals(ScopeTab.entries.toList(), enabledScopeTabs(true, true, true, true))
        assertTrue(enabledScopeTabs(false, false, false, false).isEmpty())
    }

    @Test fun aDisabledRememberedTabFallsBackToTheFirstEnabledOne() {
        val tabs = listOf(ScopeTab.WAVEFORM, ScopeTab.VECTORSCOPE)
        assertEquals(ScopeTab.VECTORSCOPE, resolveScopeTab(ScopeTab.VECTORSCOPE, tabs))
        assertEquals(ScopeTab.WAVEFORM, resolveScopeTab(ScopeTab.HISTOGRAM, tabs))
        assertEquals(ScopeTab.WAVEFORM, resolveScopeTab(null, tabs))
        assertNull(resolveScopeTab(ScopeTab.WAVEFORM, emptyList()))
    }

    @Test fun theCycleKeyWraps() {
        val tabs = listOf(ScopeTab.WAVEFORM, ScopeTab.VECTORSCOPE, ScopeTab.FALSE_COLOR)
        assertEquals(ScopeTab.VECTORSCOPE, nextScopeTab(ScopeTab.WAVEFORM, tabs))
        assertEquals(ScopeTab.WAVEFORM, nextScopeTab(ScopeTab.FALSE_COLOR, tabs))
        assertEquals(ScopeTab.WAVEFORM, nextScopeTab(ScopeTab.HISTOGRAM, tabs))
        assertNull(nextScopeTab(ScopeTab.WAVEFORM, emptyList()))
    }

    @Test fun theHeaderDegradesFromFullNamesToShortCodesToOneKey() {
        val full = listOf(64f, 80f, 76f)
        val short = listOf(30f, 26f, 18f)
        // Wide inspector: names and chip side by side with the enlarge key.
        assertEquals(ScopeHeaderPlan(ScopeTabStyle.FULL, true), scopeHeaderPlan(412f, full, short, 70f, 1))
        // Phone tray: short codes keep the chip on the row.
        assertEquals(ScopeHeaderPlan(ScopeTabStyle.SHORT, true), scopeHeaderPlan(280f, full, short, 70f, 1))
        // Narrow: short codes, the chip moves to its own line.
        assertEquals(ScopeHeaderPlan(ScopeTabStyle.SHORT, false), scopeHeaderPlan(200f, full, short, 70f, 1))
        // Narrowest: one key steps through the scopes.
        assertEquals(ScopeHeaderPlan(ScopeTabStyle.CYCLE, true), scopeHeaderPlan(190f, full, short, 70f, 1))
        assertEquals(ScopeHeaderPlan(ScopeTabStyle.CYCLE, false), scopeHeaderPlan(100f, full, short, 70f, 1))
    }

    @Test fun everyTabKeepsATouchTargetWidth() {
        // Three 10 dp codes still need three 48 dp keys.
        val plan = scopeHeaderPlan(3 * 48f + 48f - 1f, listOf(200f, 200f, 200f), listOf(10f, 10f, 10f), 0f, 1)
        assertEquals(ScopeTabStyle.CYCLE, plan.tabStyle)
        assertEquals(ScopeTabStyle.SHORT, scopeHeaderPlan(3 * 48f + 48f, listOf(200f, 200f, 200f), listOf(10f, 10f, 10f), 0f, 1).tabStyle)
    }

    @Test fun onlyATallPaneStacksTheScopes() {
        val three = listOf(ScopeTab.WAVEFORM, ScopeTab.VECTORSCOPE, ScopeTab.FALSE_COLOR)
        assertTrue(scopePanelStacks(372f, 704f, three))
        assertFalse("phone tray", scopePanelStacks(403f, 150f, three))
        assertFalse("phone landscape side pane", scopePanelStacks(312f, 300f, three))
        assertFalse("one scope never stacks", scopePanelStacks(400f, 2000f, listOf(ScopeTab.VECTORSCOPE)))
    }

    @Test fun theVectorscopeIsTheLargestCentredSquare() {
        assertEquals(Rect(100f, 0f, 300f, 200f), scopeSquareFit(400f, 200f))
        assertEquals(Rect(0f, 150f, 160f, 310f), scopeSquareFit(160f, 460f))
        assertEquals(Rect(0f, 0f, 160f, 160f), scopeSquareFit(160f, 160f))
    }

    @Test fun waveformScaleLabelsAndLevels() {
        assertEquals(listOf(100, 50, 0), waveformScaleLabels(120f))
        assertEquals(listOf(100, 0), waveformScaleLabels(48f))
        assertEquals(10f, waveformLevelY(100, 10f, 200f), 0f)
        assertEquals(110f, waveformLevelY(50, 10f, 200f), 0f)
        assertEquals(210f, waveformLevelY(0, 10f, 200f), 0f)
    }

    @Test fun vectorscopeTargetsMatchTheAnalysisAxes() {
        val square = Rect(0f, 0f, 64f, 64f)
        assertEquals(Offset(32f, 32f), vectorscopePoint(0f, 0f, square))
        // +Cb to the right, +Cr up, ±0.5 on the edge, as MonitoringAnalysis bins it.
        assertEquals(Offset(64f, 32f), vectorscopePoint(.5f, 0f, square))
        assertEquals(Offset(32f, 0f), vectorscopePoint(0f, .5f, square))
        val red = VECTORSCOPE_BARS[0]
        val p = vectorscopePoint(red.first * VECTORSCOPE_TARGET_LEVEL, red.second * VECTORSCOPE_TARGET_LEVEL, square)
        assertTrue("red sits up and slightly left", p.y < 32f && p.x < 32f)
    }

    @Test fun falseColorRampCoversZeroToHundred() {
        val stops = falseColorRampStops(5, 25, 75, 95)
        assertEquals(5, stops.size)
        assertEquals(0f, stops.first().start, 0f)
        assertEquals(1f, stops.last().endInclusive, 0f)
        assertEquals(.25f..0.75f, stops[2])
    }

    @Test fun theImageRectFollowsTheLetterboxAndTheWindow() {
        assertEquals(Rect(0f, 100f, 400f, 300f), monitoringImageRect(400f, 400f, 1f, .5f))
        // A FILL overlay 1200 wide centred in an 800 wide pane: only its middle shows.
        val visible = overlayVisibleRect(-200f, 0f, 1200f, 600f, 800f, 600f)
        assertEquals(Rect(200f, 0f, 1000f, 600f), visible)
        assertEquals(visible, visibleImageRect(Rect(0f, 0f, 1200f, 600f), visible))
        assertEquals(Rect(0f, 0f, 10f, 10f), visibleImageRect(Rect(0f, 0f, 10f, 10f), Rect(20f, 20f, 30f, 30f)))
    }

    @Test fun theOverlayHistogramSitsInsideThePicture() {
        val image = Rect(0f, 400f, 1080f, 1100f)
        val rect = overlayHistogramRect(image, density = 2.625f)
        assertTrue(rect.left >= image.left && rect.top >= image.top && rect.right <= image.right && rect.bottom <= image.bottom)
        assertEquals(image.width * .28f, rect.width, .5f)
        // Small pictures keep a readable minimum; large ones stop growing.
        assertEquals(96f * 2f, overlayHistogramRect(Rect(0f, 0f, 500f, 500f), 2f).width, .01f)
        assertEquals(240f, overlayHistogramRect(Rect(0f, 0f, 3000f, 2000f), 1f).width, .01f)
    }

    @Test fun theFloatingPanelFitsThePicture() {
        assertEquals(320f to 440f, overlayScopesPanelSizeDp(1200f, 800f, 12f))
        val (w, h) = overlayScopesPanelSizeDp(360f, 300f, 12f)
        assertTrue(w in 160f..336f && h <= 276f && h >= 160f)
    }
}
