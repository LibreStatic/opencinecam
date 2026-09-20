/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface SettingsPersistence {
    fun load(): CameraSettings
    fun save(settings: CameraSettings)
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
}

object SettingsRepositories {
    @Volatile private var instance: SettingsRepository? = null

    fun get(context: Context): SettingsRepository = instance ?: synchronized(this) {
        instance ?: SettingsRepository(CameraSettingsStore(context.applicationContext)).also { instance = it }
    }
}
