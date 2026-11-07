/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The process-wide face of the network drive: its [state] for the screen, the take activity
 * the capture service reports ([reportTakeActive]) so the drive never runs while recording,
 * [start]/[stop], which go through [NetworkShareService], and the operator's choice to
 * [showOriginals] in the take folders.
 */
object NetworkShare {
    private val mutableState = MutableStateFlow<NetworkShareState>(NetworkShareState.Stopped())
    private val mutableTakeActive = MutableStateFlow(false)
    private val mutableShowOriginals = MutableStateFlow(false)
    @Volatile private var showOriginalsLoaded = false

    val state: StateFlow<NetworkShareState> = mutableState.asStateFlow()

    /** True while a take prepares, records or finalises. */
    val takeActive: StateFlow<Boolean> = mutableTakeActive.asStateFlow()

    /** The capture service: a take is preparing, running or finalising (or no longer). */
    fun reportTakeActive(active: Boolean) {
        mutableTakeActive.value = active
    }

    /**
     * Whether each take folder also lists the original `.ocraw`. Off by default: the DNG frames
     * and the WAV are what an editor needs, and the original, as large as the take, is beyond
     * the 4 GB Windows Explorer can copy. Persisted; read live by the running share.
     */
    fun showOriginals(context: Context): StateFlow<Boolean> {
        loadShowOriginals(context)
        return mutableShowOriginals.asStateFlow()
    }

    fun setShowOriginals(context: Context, show: Boolean) {
        loadShowOriginals(context)
        preferences(context).edit().putBoolean(KEY_SHOW_ORIGINALS, show).apply()
        mutableShowOriginals.value = show
    }

    private fun loadShowOriginals(context: Context) {
        if (showOriginalsLoaded) return
        synchronized(this) {
            if (showOriginalsLoaded) return
            mutableShowOriginals.value = preferences(context).getBoolean(KEY_SHOW_ORIGINALS, false)
            showOriginalsLoaded = true
        }
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "network-share"
    private const val KEY_SHOW_ORIGINALS = "show-originals"

    internal fun publish(next: NetworkShareState) {
        mutableState.value = next
    }

    /** Starts the drive, unless a take is recording. Off by default: only the operator calls this. */
    fun start(context: Context) {
        if (mutableTakeActive.value) {
            publish(NetworkShareState.Stopped(NetworkShareStopReason.RECORDING))
            return
        }
        if (mutableState.value !is NetworkShareState.Stopped) return
        publish(NetworkShareState.Starting)
        try {
            ContextCompat.startForegroundService(context, Intent(context, NetworkShareService::class.java).setAction(NetworkShareService.ACTION_START))
        } catch (_: RuntimeException) {
            // E.g. ForegroundServiceStartNotAllowedException when not in the foreground.
            publish(NetworkShareState.Stopped(NetworkShareStopReason.FAILED))
        }
    }

    fun stop(context: Context) {
        if (mutableState.value is NetworkShareState.Stopped) return
        runCatching { context.startService(Intent(context, NetworkShareService::class.java).setAction(NetworkShareService.ACTION_STOP)) }
            .onFailure { publish(NetworkShareState.Stopped(NetworkShareStopReason.OPERATOR)) }
    }
}
