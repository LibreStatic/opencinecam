/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.librestatic.opencinecam.media.audio.isCaptureInputType

/**
 * Hotplug for audio inputs. Plugging a USB microphone in or out fires a burst of callbacks, so
 * [onChanged] runs once after the set of inputs has been stable for [DEBOUNCE_MS].
 */
internal class AudioInputMonitor(context: Context, private val onChanged: () -> Unit) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val notify = Runnable { onChanged() }
    private var knownInputs: Set<Int> = emptySet()
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = inputsMaybeChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = inputsMaybeChanged()
    }

    fun register() {
        // Registration replays every connected device as "added"; only a real change counts.
        knownInputs = currentInputs()
        audioManager.registerAudioDeviceCallback(callback, handler)
    }

    fun unregister() {
        handler.removeCallbacks(notify)
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun inputsMaybeChanged() {
        val inputs = currentInputs()
        if (inputs == knownInputs) return
        knownInputs = inputs
        handler.removeCallbacks(notify)
        handler.postDelayed(notify, DEBOUNCE_MS)
    }

    private fun currentInputs(): Set<Int> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { isCaptureInputType(it.type) }.mapTo(mutableSetOf()) { it.id }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
