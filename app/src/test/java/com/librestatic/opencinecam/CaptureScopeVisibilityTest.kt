/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureScopeVisibilityTest {
    /** A holder as the capture chrome leaves it, with the settings it toggles. */
    private class Fixture(hidden: Boolean = false) {
        val scopes = CaptureScopeVisibility(hidden).apply { hideable = true }
        val on = mutableMapOf(OperatorAction.WAVEFORM to false, OperatorAction.VECTORSCOPE to false,
            OperatorAction.HISTOGRAM to false, OperatorAction.ZEBRA to false)

        fun press(action: OperatorAction) = scopes.press(action, on.getValue(action)) { on[action] = !on.getValue(action) }
        fun lit(action: OperatorAction) = scopes.latched(action, on.getValue(action))
    }

    @Test fun aToggleIsLitOnlyWhileItsScopeIsInView() {
        assertTrue(scopeToggleLit(on = true, hidden = false))
        assertFalse(scopeToggleLit(on = true, hidden = true))
        assertFalse(scopeToggleLit(on = false, hidden = false))
        assertFalse(scopeToggleLit(on = false, hidden = true))
    }

    @Test fun aPressWhileHiddenShowsInsteadOfSwitchingOff() {
        assertEquals(ScopeTogglePress.TOGGLE, scopeTogglePress(on = true, hidden = false))
        assertEquals(ScopeTogglePress.TOGGLE, scopeTogglePress(on = false, hidden = false))
        assertEquals(ScopeTogglePress.SHOW, scopeTogglePress(on = true, hidden = true))
        assertEquals(ScopeTogglePress.SHOW_AND_ENABLE, scopeTogglePress(on = false, hidden = true))
    }

    @Test fun hidingDimsEveryScopeKeyAndKeepsTheSettings() {
        val f = Fixture()
        f.on[OperatorAction.WAVEFORM] = true
        f.on[OperatorAction.HISTOGRAM] = true
        assertEquals(true, f.lit(OperatorAction.WAVEFORM))
        f.scopes.hide()
        assertEquals(false, f.lit(OperatorAction.WAVEFORM))
        assertEquals(false, f.lit(OperatorAction.HISTOGRAM))
        assertEquals(false, f.lit(OperatorAction.VECTORSCOPE))
        assertTrue(f.on.getValue(OperatorAction.WAVEFORM))
        // H again brings back exactly the scopes that were on.
        f.scopes.toggle()
        assertEquals(true, f.lit(OperatorAction.WAVEFORM))
        assertEquals(true, f.lit(OperatorAction.HISTOGRAM))
        assertEquals(false, f.lit(OperatorAction.VECTORSCOPE))
    }

    @Test fun pressingAHiddenScopeThatIsOnShowsItAndKeepsItOn() {
        val f = Fixture(hidden = true)
        f.on[OperatorAction.VECTORSCOPE] = true
        f.press(OperatorAction.VECTORSCOPE)
        assertFalse(f.scopes.hidden)
        assertTrue(f.on.getValue(OperatorAction.VECTORSCOPE))
        assertEquals(true, f.lit(OperatorAction.VECTORSCOPE))
        assertEquals(ScopeTab.VECTORSCOPE, f.scopes.tab)
    }

    @Test fun pressingAHiddenScopeThatIsOffShowsItSwitchedOn() {
        val f = Fixture(hidden = true)
        f.on[OperatorAction.VECTORSCOPE] = true
        f.press(OperatorAction.WAVEFORM)
        assertFalse(f.scopes.hidden)
        assertTrue(f.on.getValue(OperatorAction.WAVEFORM))
        assertTrue(f.on.getValue(OperatorAction.VECTORSCOPE))
        assertEquals(ScopeTab.WAVEFORM, f.scopes.tab)
    }

    @Test fun pressingAVisibleScopeTogglesIt() {
        val f = Fixture()
        f.press(OperatorAction.WAVEFORM)
        assertTrue(f.on.getValue(OperatorAction.WAVEFORM))
        assertEquals(ScopeTab.WAVEFORM, f.scopes.tab)
        f.scopes.tab = ScopeTab.VECTORSCOPE
        f.press(OperatorAction.WAVEFORM)
        assertFalse(f.on.getValue(OperatorAction.WAVEFORM))
        // Switching a scope off leaves the panel's tab alone; the panel falls back by itself.
        assertEquals(ScopeTab.VECTORSCOPE, f.scopes.tab)
        assertFalse(f.scopes.hidden)
    }

    @Test fun theHistogramShowsAgainWithoutATab() {
        val f = Fixture(hidden = true)
        f.on[OperatorAction.HISTOGRAM] = true
        f.press(OperatorAction.HISTOGRAM)
        assertFalse(f.scopes.hidden)
        assertTrue(f.on.getValue(OperatorAction.HISTOGRAM))
        assertNull(f.scopes.tab)
    }

    @Test fun otherActionsIgnoreTheHiddenScopes() {
        val f = Fixture(hidden = true)
        f.press(OperatorAction.ZEBRA)
        assertTrue(f.on.getValue(OperatorAction.ZEBRA))
        assertTrue(f.scopes.hidden)
        assertEquals(true, f.lit(OperatorAction.ZEBRA))
        assertNull(f.scopes.latched(OperatorAction.CAPTURE, null))
    }

    @Test fun whereTheScopesCannotBeHiddenNothingCountsAsHidden() {
        // A hinge split or the self-recording chrome draws the scopes on the picture.
        val f = Fixture(hidden = true)
        f.scopes.hideable = false
        f.on[OperatorAction.WAVEFORM] = true
        assertEquals(true, f.lit(OperatorAction.WAVEFORM))
        f.press(OperatorAction.WAVEFORM)
        assertFalse(f.on.getValue(OperatorAction.WAVEFORM))
        // The hidden state waits for the chrome that can hide them.
        assertTrue(f.scopes.hidden)
    }

    @Test fun onlyTheWaveformAndVectorscopeHaveTabs() {
        assertEquals(ScopeTab.WAVEFORM, OperatorAction.WAVEFORM.scopeTab())
        assertEquals(ScopeTab.VECTORSCOPE, OperatorAction.VECTORSCOPE.scopeTab())
        assertNull(OperatorAction.HISTOGRAM.scopeTab())
        assertNull(OperatorAction.PEAKING.scopeTab())
    }
}
