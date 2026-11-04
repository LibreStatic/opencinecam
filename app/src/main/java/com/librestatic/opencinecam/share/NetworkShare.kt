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
 * and [start]/[stop], which go through [NetworkShareService].
 */
object NetworkShare {
    private val mutableState = MutableStateFlow<NetworkShareState>(NetworkShareState.Stopped())
    private val mutableTakeActive = MutableStateFlow(false)

    val state: StateFlow<NetworkShareState> = mutableState.asStateFlow()

    /** True while a take prepares, records or finalises. */
    val takeActive: StateFlow<Boolean> = mutableTakeActive.asStateFlow()

    /** The capture service: a take is preparing, running or finalising (or no longer). */
    fun reportTakeActive(active: Boolean) {
        mutableTakeActive.value = active
    }

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
