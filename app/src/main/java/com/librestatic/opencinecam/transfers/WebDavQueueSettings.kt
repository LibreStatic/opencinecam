/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WebDavQueueSettingsState(val preferences: WebDavQueuePreferences, val storageFailed: Boolean = false)

/** Separate device-local settings: no credential material and no portable preset merge. */
class WebDavQueueSettings internal constructor(private val file: File) {
    private val atomic = AtomicFile(file)
    private val mutable = MutableStateFlow(synchronized(processLock) { readState() })
    val states: StateFlow<WebDavQueueSettingsState> = mutable.asStateFlow()
    @Synchronized fun reload() { synchronized(processLock) { mutable.value = readState() } }
    @Synchronized fun snapshotForAdmission(): WebDavQueueSettingsState = synchronized(processLock) {
        readState().also { mutable.value = it }
    }
    @Synchronized fun save(url: String, enabled: Boolean, allowCellular: Boolean, ignoreTlsErrors: Boolean? = null): WebDavQueuePreferences = synchronized(processLock) {
        check(!mutable.value.storageFailed) { "Queue settings require recovery" }
        try {
            check((readBytes()?.let(WebDavQueuePreferencesCodec::decode) ?: WebDavQueuePreferences()) == mutable.value.preferences) { "Queue settings changed on disk" }
        } catch (failure: Exception) {
            mutable.value = mutable.value.copy(storageFailed = true)
            throw failure
        }
        val next = mutable.value.preferences.updated(url, enabled, allowCellular, ignoreTlsErrors)
        if (next == mutable.value.preferences) return next
        var stream: java.io.FileOutputStream? = null
        try {
            if (!file.parentFile!!.exists() && !file.parentFile!!.mkdirs()) throw IOException("Queue settings directory is unavailable")
            val data = WebDavQueuePreferencesCodec.encode(next)
            stream = atomic.startWrite()
            stream.write(data); atomic.finishWrite(stream)
            check(readBytes()?.let(WebDavQueuePreferencesCodec::decode) == next) { "Queue settings readback failed" }
        } catch (problem: Exception) {
            mutable.value = mutable.value.copy(storageFailed = true)
            try { stream?.let(atomic::failWrite) } catch (cleanup: Exception) { problem.addSuppressed(cleanup) }
            throw problem
        }
        mutable.value = WebDavQueueSettingsState(next)
        return next
    }
    private fun readState(): WebDavQueueSettingsState = try {
        WebDavQueueSettingsState(readBytes()?.let(WebDavQueuePreferencesCodec::decode) ?: WebDavQueuePreferences())
    } catch (_: Exception) { WebDavQueueSettingsState(WebDavQueuePreferences(), storageFailed = true) }
    private fun readBytes(): ByteArray? {
        if (!file.exists() && !File(file.path + ".bak").exists()) {
            if (File(file.path + ".new").exists()) throw IOException("Incomplete queue settings")
            return null
        }
        return atomic.openRead().use { input ->
            val buffer = ByteArray(WebDavQueuePreferencesCodec.MAX_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                check(count > 0); size += count
            }
            require(size <= WebDavQueuePreferencesCodec.MAX_BYTES)
            buffer.copyOf(size)
        }
    }
    companion object {
        private val processLock = Any()
        private var instance: WebDavQueueSettings? = null
        @Synchronized fun get(context: Context): WebDavQueueSettings = instance ?: WebDavQueueSettings(
            File(context.applicationContext.noBackupFilesDir, "webdav-queue-settings.json"),
        ).also { instance = it }
    }
}
