/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context

/**
 * The capture configuration that last reached a live preview. A failed preview must never trap the
 * operator in the configuration that broke it: the error sheet offers to return to this snapshot,
 * and because it is persisted the way back survives an app restart.
 */
internal data class KnownGoodCapture(
    val mode: CaptureMode,
    val cameraId: String?,
    val videoWidth: Int,
    val videoHeight: Int,
    val videoFps: Int,
    val logWidth: Int,
    val logHeight: Int,
    val logFps: Int,
    val timelapseWidth: Int,
    val timelapseHeight: Int,
) {
    fun applyTo(settings: CameraSettings): CameraSettings = settings.copy(
        videoWidth = videoWidth, videoHeight = videoHeight, videoFps = videoFps,
        logWidth = logWidth, logHeight = logHeight, logFps = logFps,
        timelapseWidth = timelapseWidth, timelapseHeight = timelapseHeight,
    )

    /** Resolution and rate the camera opens with in [mode], or null for modes that have none. */
    fun geometry(mode: CaptureMode = this.mode): Triple<Int, Int, Int?>? = when (mode) {
        CaptureMode.LOG -> Triple(logWidth, logHeight, logFps)
        CaptureMode.TIME_LAPSE -> Triple(timelapseWidth, timelapseHeight, null)
        in CameraUiState.videoProfileModes -> Triple(videoWidth, videoHeight, videoFps)
        else -> null
    }

    /** True when restoring would change what the camera is asked to open. */
    fun differsFrom(settings: CameraSettings, mode: CaptureMode): Boolean =
        this.mode != mode || applyTo(settings) != settings

    fun encode(): String = listOf(mode.name, cameraId.orEmpty(), videoWidth, videoHeight, videoFps,
        logWidth, logHeight, logFps, timelapseWidth, timelapseHeight).joinToString("|")

    companion object {
        fun of(settings: CameraSettings, mode: CaptureMode, cameraId: String?) = KnownGoodCapture(
            mode, cameraId, settings.videoWidth, settings.videoHeight, settings.videoFps,
            settings.logWidth, settings.logHeight, settings.logFps, settings.timelapseWidth, settings.timelapseHeight,
        )

        /** Fallback when nothing has previewed yet: the defaults every supported camera opens. */
        fun safeDefault(mode: CaptureMode): KnownGoodCapture {
            val defaults = CameraSettings()
            // Only integrated, non-experimental modes are a safe place to land.
            val safeMode = if (mode in setOf(CaptureMode.PHOTO, CaptureMode.VIDEO)) mode else CaptureMode.VIDEO
            return of(defaults, safeMode, null)
        }

        fun decode(value: String?): KnownGoodCapture? {
            val parts = value?.split("|") ?: return null
            if (parts.size != 10) return null
            val mode = CaptureMode.entries.firstOrNull { it.name == parts[0] } ?: return null
            val numbers = parts.drop(2).map { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: return null }
            return KnownGoodCapture(mode, parts[1].ifEmpty { null }, numbers[0], numbers[1], numbers[2],
                numbers[3], numbers[4], numbers[5], numbers[6], numbers[7])
        }
    }
}

internal class KnownGoodCaptureStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("capture-known-good", Context.MODE_PRIVATE)

    fun load(): KnownGoodCapture? = KnownGoodCapture.decode(preferences.getString(KEY, null))

    fun save(snapshot: KnownGoodCapture) {
        preferences.edit().putString(KEY, snapshot.encode()).apply()
    }

    private companion object {
        const val KEY = "last-previewing"
    }
}
