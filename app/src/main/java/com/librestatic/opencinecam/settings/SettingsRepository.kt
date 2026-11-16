/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.settings

import android.content.Context
import com.librestatic.opencinecam.AudioInputPolicy
import com.librestatic.opencinecam.settledAudioInput
import com.librestatic.opencinecam.StoredAudioInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface SettingsPersistence {
    fun load(): CameraSettings
    fun save(settings: CameraSettings)

    /** The stored audio keys while the PLAN-074 audio input migration has not run; null once it has. */
    fun pendingAudioInputMigration(): StoredAudioInput? = null

    /** Records that the audio input migration ran, so it never runs again. */
    fun markAudioInputMigrated() = Unit
}

/** One application-scoped source of preferences; hardware truth stays in CameraUiState. */
class SettingsRepository(private val persistence: SettingsPersistence) {
    private val mutable = MutableStateFlow(persistence.load())
    val states: StateFlow<CameraSettings> = mutable.asStateFlow()

    @Synchronized
    fun update(transform: (CameraSettings) -> CameraSettings) {
        val next = transform(mutable.value)
        if (next == mutable.value) return
        persistence.save(next)
        mutable.value = next
    }

    fun set(settings: CameraSettings) = update { settings }

    /**
     * Settles the audio input for this phone (PLAN-074 decision 1): the one-time migration while it is
     * pending, then the `UNPROCESSED` remap. It goes through [update], so every observer sees the change.
     * [policy] reads assets to resolve, so the caller resolves it off the main thread first.
     */
    @Synchronized
    fun settleAudioInput(policy: AudioInputPolicy) {
        val stored = persistence.pendingAudioInputMigration()
        update { it.settledAudioInput(stored, policy) }
        // After the save: a migration cut short runs again, on the values it already wrote, and keeps them.
        if (stored != null) persistence.markAudioInputMigrated()
    }
}

object SettingsRepositories {
    @Volatile private var instance: SettingsRepository? = null

    fun get(context: Context): SettingsRepository = instance ?: synchronized(this) {
        instance ?: SettingsRepository(CameraSettingsStore(context.applicationContext)).also { instance = it }
    }
}
