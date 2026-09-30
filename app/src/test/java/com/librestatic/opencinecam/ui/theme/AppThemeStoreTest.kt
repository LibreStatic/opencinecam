/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeStoreTest {
    @Test
    fun defaultsToCineAndRemembersTheChoice() {
        val store = AppThemeStore(FakePreferences())
        assertEquals(AppTheme.CINE, store.load())
        store.save(AppTheme.YOU)
        assertEquals(AppTheme.YOU, store.load())
    }

    @Test
    fun unknownStoredValueFallsBackToCine() {
        val prefs = FakePreferences().apply { edit().putString(AppThemeStore.KEY, "NEON").apply() }
        assertEquals(AppTheme.CINE, AppThemeStore(prefs).load())
    }
}
