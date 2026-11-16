/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.Context
import android.os.Build
import android.util.Log
import com.librestatic.opencinecam.core.model.QuirkEffect
import com.librestatic.opencinecam.core.model.QuirkResolution
import com.librestatic.opencinecam.diagnostics.DeviceQuirksProvider
import com.librestatic.opencinecam.media.audio.lowLatencyInputAvailable

/** The camera whose device profile carries the audio quirks: they describe the phone, not a camera. */
internal const val AUDIO_QUIRK_CAMERA_ID = "0"

/**
 * This phone's [AudioInputPolicy], resolved once per process. Resolving reads the bundled profiles
 * from the assets and loads the native audio library, so the first [get] must not run on the main thread.
 */
internal object AudioInputPolicies {
    private const val TAG = "AudioInputPolicies"

    @Volatile private var resolved: AudioInputPolicy? = null

    fun get(context: Context): AudioInputPolicy = resolved ?: synchronized(this) {
        resolved ?: resolve(context.applicationContext).also { resolved = it }
    }

    private fun resolve(context: Context): AudioInputPolicy {
        val quirks = runCatching { DeviceQuirksProvider.get(context).resolve(AUDIO_QUIRK_CAMERA_ID) }
            .onFailure { Log.w(TAG, "device profile could not be resolved", it) }
            .getOrDefault(QuirkResolution.NONE)
        val lowLatencyDefault = quirks.applies(QuirkEffect.AUDIO_DEFAULT_LOW_LATENCY_INPUT)
        return AudioInputPolicy(
            lowLatencyDefault = lowLatencyDefault,
            unprocessedClips = quirks.applies(QuirkEffect.AUDIO_UNPROCESSED_SOURCE_CLIPS),
            lowLatencyAvailable = runCatching { lowLatencyInputAvailable() }.getOrDefault(false),
            bundledProfile = if (lowLatencyDefault) BundledEqProfiles.get(context).forDevice(Build.DEVICE) else null,
        )
    }
}
