/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.librestatic.opencinecam.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The runtime and settings the worker needs, behind a seam so host tests can fake them. */
internal interface WebDavAutoUploadHost {
    fun settings(): WebDavQueueSettingsState
    fun enrollment(bundleId: String): CaptureTransferEnrollment?
    suspend fun send(bundleId: String): AutoSendResult
    fun cancel(bundleId: String)
}

private class DefaultWebDavAutoUploadHost(private val context: Context) : WebDavAutoUploadHost {
    override fun settings() = WebDavQueueSettings.get(context).snapshotForAdmission()
    override fun enrollment(bundleId: String) = CaptureTransferEnrollmentFile(context).load(bundleId)
    override suspend fun send(bundleId: String) = WebDavTransferRuntime.get(context).sendAndAwait(bundleId)
    override fun cancel(bundleId: String) = WebDavTransferRuntime.get(context).cancel(bundleId)
}

/** Uploads one automatically enrolled take. Never decides on its own to repeat an in-flight PUT. */
class WebDavAutoUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val bundleId = inputData.getString(WebDavAutoUploadScheduler.KEY_BUNDLE_ID)
        if (bundleId == null || runCatching { requireOutboxUuid(bundleId) }.isFailure) return Result.failure()
        val host = hostFactory(applicationContext)
        val eligible = try {
            withContext(Dispatchers.IO) { isStillEligible(host, bundleId) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "Automatic upload eligibility could not be read", failure)
            false
        }
        if (!eligible) return Result.failure()
        try {
            setForeground(foregroundInfo())
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "Automatic upload continues without a foreground service", failure)
        }
        val sent = try {
            host.send(bundleId)
        } catch (failure: CancellationException) {
            host.cancel(bundleId) // Stopping the worker must only stop this bundle's own batch.
            throw failure
        }
        val outcome = autoUploadOutcome(sent.message, sent.artifacts, runAttemptCount, sent.busy)
        Log.i(LOG_TAG, "Automatic upload run $runAttemptCount ended with ${sent.message} (busy=${sent.busy}): $outcome")
        return when (outcome) {
            AutoUploadOutcome.SUCCESS -> Result.success()
            AutoUploadOutcome.RETRY -> Result.retry()
            AutoUploadOutcome.STOP -> Result.failure()
        }
    }

    private fun isStillEligible(host: WebDavAutoUploadHost, bundleId: String): Boolean {
        val state = host.settings()
        val preferences = state.preferences
        if (state.storageFailed || !preferences.enabled || !preferences.autoUpload) return false
        val enrollment = host.enrollment(bundleId) ?: return false
        return enrollment.autoUpload && enrollment.endpointId == preferences.activeEndpointId
    }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            applicationContext.getString(R.string.transfers_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(applicationContext.getString(R.string.transfers_uploading_take))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val LOG_TAG = "WebDavAutoUpload"
        const val CHANNEL_ID = "transfers"
        private const val NOTIFICATION_ID = 0x7d17
        @Volatile internal var hostFactory: (Context) -> WebDavAutoUploadHost = { DefaultWebDavAutoUploadHost(it) }
    }
}
