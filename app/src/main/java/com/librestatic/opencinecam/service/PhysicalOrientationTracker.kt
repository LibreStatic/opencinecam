/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import android.content.Context
import android.view.OrientationEventListener

data class PhysicalOrientationSnapshot(
    val degrees: Int,
    val observedAtElapsedRealtimeMs: Long,
)

/** Keeps the last stable chassis orientation so foldable display coordinates cannot leak into files. */
class PhysicalOrientationTracker(
    context: Context,
    private val onChanged: (PhysicalOrientationSnapshot) -> Unit = {},
) : AutoCloseable {
    @Volatile private var latest: PhysicalOrientationSnapshot? = null
    private val listener = object : OrientationEventListener(context.applicationContext) {
        override fun onOrientationChanged(orientation: Int) {
            quantizeStableOrientation(orientation)?.let { degrees ->
                val current = latest
                if (current?.degrees == degrees) return
                PhysicalOrientationSnapshot(
                    degrees = degrees,
                    observedAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                ).also {
                    latest = it
                    onChanged(it)
                }
            }
        }
    }

    fun start(): Boolean {
        if (!listener.canDetectOrientation()) return false
        listener.enable()
        return true
    }

    fun snapshot(): PhysicalOrientationSnapshot? = latest

    override fun close() = listener.disable()

    companion object {
        /** Rejects the 15-degree boundary bands instead of oscillating between quadrants. */
        internal fun quantizeStableOrientation(rawDegrees: Int): Int? {
            if (rawDegrees == OrientationEventListener.ORIENTATION_UNKNOWN) return null
            val normalized = ((rawDegrees % 360) + 360) % 360
            val nearest = ((normalized + 45) / 90 * 90) % 360
            val distance = kotlin.math.min(
                kotlin.math.abs(normalized - nearest),
                360 - kotlin.math.abs(normalized - nearest),
            )
            return nearest.takeIf { distance <= 30 }
        }
    }
}
