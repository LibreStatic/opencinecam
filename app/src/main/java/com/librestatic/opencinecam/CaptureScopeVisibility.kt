/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/** The scope actions H hides along with the scopes panel; their toggles follow what is on screen. */
internal val HideableScopeActions: Set<OperatorAction> = setOf(OperatorAction.WAVEFORM, OperatorAction.VECTORSCOPE, OperatorAction.FALSE_COLOR, OperatorAction.HISTOGRAM)

/** What a press on a scope toggle does, given the setting and whether the scopes are hidden. */
internal enum class ScopeTogglePress {
    /** The scopes are in view: switch this one on or off. */
    TOGGLE,

    /** Hidden but switched on: bring the scopes back; the setting stays as it is. */
    SHOW,

    /** Hidden and switched off: bring the scopes back with this one switched on. */
    SHOW_AND_ENABLE,
}

/** A scope toggle is lit only while the operator can see that scope. */
internal fun scopeToggleLit(on: Boolean, hidden: Boolean): Boolean = on && !hidden

/** A press never switches off a scope the operator cannot see; it shows the scopes instead. */
internal fun scopeTogglePress(on: Boolean, hidden: Boolean): ScopeTogglePress = when {
    !hidden -> ScopeTogglePress.TOGGLE
    on -> ScopeTogglePress.SHOW
    else -> ScopeTogglePress.SHOW_AND_ENABLE
}

/** The panel tab that shows [this] action's scope; the histogram has no tab in the capture chrome. */
internal fun OperatorAction.scopeTab(): ScopeTab? = when (this) {
    OperatorAction.WAVEFORM -> ScopeTab.WAVEFORM
    OperatorAction.VECTORSCOPE -> ScopeTab.VECTORSCOPE
    OperatorAction.FALSE_COLOR -> ScopeTab.FALSE_COLOR
    else -> null
}

/**
 * Whether the capture screen shows the scopes the settings switch on, and which tab the panel has
 * forward. H and the panel's close key hide every scope at once without touching the settings, so
 * H brings back exactly the scopes that were on. While they are hidden their toggles read off,
 * because the operator sees none of them, and pressing one shows the scopes again.
 *
 * Only a surface that can hide the scopes sets [hideable] (the full capture chrome); a hinge
 * split or the self-recording chrome always draws them, so there nothing counts as hidden.
 */
@Stable
internal class CaptureScopeVisibility(hidden: Boolean = false, tab: ScopeTab? = null) {
    /** The operator hid the scopes; the settings still say which are on. */
    var hidden by mutableStateOf(hidden)
        private set

    /** The tab the scopes panel has forward, or null for its default. */
    var tab by mutableStateOf(tab)

    /** Set by the surface that can hide the scopes while it shows them. */
    var hideable by mutableStateOf(false)

    /** Hidden here and now: the operator sees none of the scopes. */
    val concealed: Boolean get() = hidden && hideable

    fun hide() { hidden = true }
    fun show() { hidden = false }
    fun toggle() { hidden = !hidden }

    /** What a toggle for [action] shows as latched, from the setting's [on] state. */
    fun latched(action: OperatorAction, on: Boolean?): Boolean? =
        if (on == null || action !in HideableScopeActions) on else scopeToggleLit(on, concealed)

    /**
     * A press on the toggle for [action], whose setting reads [on]; [toggleSetting] flips the
     * setting. A scope switched on, or shown again, comes forward in the panel.
     */
    fun press(action: OperatorAction, on: Boolean, toggleSetting: () -> Unit) {
        if (action !in HideableScopeActions) {
            toggleSetting()
            return
        }
        val result = scopeTogglePress(on, concealed)
        if (result != ScopeTogglePress.TOGGLE) show()
        if (result != ScopeTogglePress.SHOW) toggleSetting()
        if (result != ScopeTogglePress.TOGGLE || !on) action.scopeTab()?.let { tab = it }
    }

    companion object {
        val Saver: Saver<CaptureScopeVisibility, Any> = listSaver(
            save = { listOf(it.hidden, it.tab?.name.orEmpty()) },
            restore = { saved ->
                CaptureScopeVisibility(saved[0] as Boolean, ScopeTab.entries.firstOrNull { it.name == saved[1] })
            },
        )
    }
}
