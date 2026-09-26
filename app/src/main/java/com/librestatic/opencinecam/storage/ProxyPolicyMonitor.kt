/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.storage.StorageManager
import androidx.core.net.toUri
import com.librestatic.opencinecam.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Admission is re-observed off the UI thread. Existing work finishes its owned operation;
 * changed policy controls the next attempt, never abandons a publication in progress. */
internal class ProxyPolicyMonitor(context: Context) {
    private val context = context.applicationContext
    private val policies = ProxyPolicies.get(this.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    suspend fun admission(job: ProxyJob): ProxyWaitReason? = withContext(Dispatchers.IO) {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0 && level <= scale) (level.toLong() * 100 / scale).toInt() else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        val storage = context.getSystemService(StorageManager::class.java)
        val available = runCatching { requireNotNull(storage).getAllocatableBytes(storage.getUuidForPath(context.cacheDir)) }.getOrNull()
        // Probe only once battery/storage basic eligibility is known. The repository independently
        // rechecks actual working space immediately before starting its encoder.
        val conditions = ProxyConditions(percent, charging, available)
        proxyWaitReason(policies.states.value, conditions)?.let { return@withContext it }
        val reservation = try { com.librestatic.opencinecam.transfers.WebDavTransferRuntime.get(context).reserveIdleMediaMutation() }
            catch (busy: com.librestatic.opencinecam.transfers.MediaMutationBusyException) { return@withContext busy.proxyWaitReason() }
        val duration = try {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(context, job.take.primary.uri.toUri(), null)
                val videos = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    .filter { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                require(videos.size == 1)
                videos.single().getLong(android.media.MediaFormat.KEY_DURATION).also { require(it > 0) }
            } finally { extractor.release() }
        } finally { reservation.close() }
        val estimated = duration / 1_000_000.0 * job.settings.videoBitrateMbps * 250_000.0 + job.take.primary.sizeBytes.toDouble() * 2
        val working = if (!estimated.isFinite() || estimated >= Long.MAX_VALUE) Long.MAX_VALUE else kotlin.math.ceil(estimated).toLong()
        proxyWaitReason(policies.states.value, conditions, working)
    }
    fun start(changed: () -> Unit) {
        scope.launch { policies.states.collectLatest { changed() } }
        scope.launch { while (isActive) { delay(5_000); changed() } }
    }
}
