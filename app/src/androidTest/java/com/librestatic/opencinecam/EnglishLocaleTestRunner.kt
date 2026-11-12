/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.app.LocaleManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner

/**
 * The UI tests assert the English strings, so the app under test runs in en-US whatever the
 * device's system locale is (the S24 Ultra is es-AR). The per-app locale is set before any test
 * starts and the previous per-app locales (usually empty, i.e. follow the system) are restored
 * when the run finishes. Below API 33 there is no per-app locale API; the run then keeps the
 * system locale and logs it.
 */
class EnglishLocaleTestRunner : AndroidJUnitRunner() {
    private var previousLocales: LocaleList? = null

    override fun onStart() {
        forceEnglish()
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        try { restore() } catch (failure: Throwable) { Log.w(TAG, "Could not restore the app locales", failure) }
        super.finish(resultCode, results)
    }

    private fun forceEnglish() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.w(TAG, "API ${Build.VERSION.SDK_INT} has no per-app locale; tests run in the system locale")
            return
        }
        val context = targetContext
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val previous = manager.applicationLocales
        previousLocales = previous
        if (previous.size() == 1 && previous[0].toLanguageTag() == ENGLISH.toLanguageTag()) return
        manager.applicationLocales = LocaleList(ENGLISH)
        // The configuration change reaches this process asynchronously; wait (bounded) until the
        // target's resources resolve English before the first test reads a string.
        val deadline = SystemClock.elapsedRealtime() + LOCALE_WAIT_MS
        while (context.resources.configuration.locales[0].language != ENGLISH.language &&
            SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
        }
        Log.i(TAG, "App locales ${previous.toLanguageTags().ifEmpty { "<system>" }} -> ${ENGLISH.toLanguageTag()}; " +
            "resources=${context.resources.configuration.locales.toLanguageTags()}")
    }

    private fun restore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val previous = previousLocales ?: return
        val manager = targetContext.getSystemService(LocaleManager::class.java) ?: return
        if (manager.applicationLocales != previous) manager.applicationLocales = previous
    }

    private companion object {
        const val TAG = "EnglishLocaleTestRunner"
        const val LOCALE_WAIT_MS = 5_000L
        val ENGLISH: java.util.Locale = java.util.Locale.US
    }
}
