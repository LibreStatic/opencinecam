/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Opt-in automatic upload scheduling (ADR-0034 amendment 2026-10-19). One unique work per bundle,
 * never replaced while it waits (KEEP), so re-registration of the same take is idempotent. The
 * network constraint mirrors the live cellular consent; the runtime still applies its own policy.
 */
class WebDavAutoUploadScheduler internal constructor(private val workManager: WorkManager) {
    /** Never throws: scheduling is optional bookkeeping and must not disturb capture publication. */
    fun enqueue(bundleId: String, allowCellular: Boolean) {
        try {
            requireOutboxUuid(bundleId)
            workManager.enqueueUniqueWork(uniqueName(bundleId), ExistingWorkPolicy.KEEP, request(bundleId, allowCellular))
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "Automatic upload was not scheduled", failure)
        }
    }

    /** Revocation: cancels every pending, blocked or running automatic upload. Bundles stay manual. */
    fun cancelAll() {
        try { workManager.cancelAllWorkByTag(TAG) } catch (failure: Exception) { Log.w(LOG_TAG, "Automatic uploads were not cancelled", failure) }
    }

    /** The cellular consent changed: re-enqueue pending automatic uploads under the new constraint. */
    fun reschedulePending(allowCellular: Boolean) {
        try {
            val pending = workManager.getWorkInfosByTag(TAG).get().filter {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED || it.state == WorkInfo.State.RUNNING
            }
            pending.mapNotNull { info -> info.tags.firstOrNull { it.startsWith(BUNDLE_TAG_PREFIX) }?.removePrefix(BUNDLE_TAG_PREFIX) }
                .distinct().forEach { bundleId ->
                    workManager.enqueueUniqueWork(uniqueName(bundleId), ExistingWorkPolicy.REPLACE, request(bundleId, allowCellular))
                }
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "Automatic uploads were not rescheduled", failure)
        }
    }

    companion object {
        const val TAG = "webdav-auto"
        const val KEY_BUNDLE_ID = "bundleId"
        internal const val BUNDLE_TAG_PREFIX = "bundle:"
        internal const val BACKOFF_INITIAL_MINUTES = 1L
        private const val LOG_TAG = "WebDavAutoUpload"

        fun get(context: Context) = WebDavAutoUploadScheduler(WorkManager.getInstance(context.applicationContext))

        internal fun uniqueName(bundleId: String) = "webdav-auto-$bundleId"

        internal fun request(bundleId: String, allowCellular: Boolean): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<WebDavAutoUploadWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (allowCellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_INITIAL_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_BUNDLE_ID to bundleId))
                .addTag(TAG)
                .addTag(BUNDLE_TAG_PREFIX + bundleId)
                .build()
    }
}

/** Settings save hook: decides which scheduled uploads survive a change of the device-local settings. */
internal fun applyAutoUploadSettingsChange(old: WebDavQueuePreferences, new: WebDavQueuePreferences, scheduler: WebDavAutoUploadScheduler) {
    when {
        !new.enabled || !new.autoUpload || new.activeEndpointId != old.activeEndpointId -> scheduler.cancelAll()
        new.allowCellular != old.allowCellular -> scheduler.reschedulePending(new.allowCellular)
    }
}

enum class AutoUploadOutcome { SUCCESS, RETRY, STOP }

internal const val AUTO_UPLOAD_MAX_RUN_ATTEMPTS = 20

private val stopMessages = setOf(
    WebDavTransferMessage.AUTHENTICATION, WebDavTransferMessage.CONFLICT, WebDavTransferMessage.SOURCE_UNAVAILABLE,
    WebDavTransferMessage.ERROR, WebDavTransferMessage.DISABLED, WebDavTransferMessage.CANCELLED,
)
private val transientMessages = setOf(
    WebDavTransferMessage.WAITING_RECORDING, WebDavTransferMessage.WAITING_MEDIA, WebDavTransferMessage.NETWORK_UNAVAILABLE,
    WebDavTransferMessage.CELLULAR_CONSENT_REQUIRED, WebDavTransferMessage.UNCERTAIN,
)

/**
 * Pure policy for one finished automatic run. Anything that needs the user (conflicts, missing
 * sources, credentials, an attempt left in flight) stops and leaves the bundle visible for manual
 * action; only states the runtime can safely resume (queued or verify-first) are retried, bounded.
 */
internal fun autoUploadOutcome(
    message: WebDavTransferMessage,
    artifacts: List<WebDavOutboxArtifact>,
    runAttemptCount: Int,
    busy: Boolean = false,
): AutoUploadOutcome {
    if (!busy && message == WebDavTransferMessage.COMPLETE) return AutoUploadOutcome.SUCCESS
    if (artifacts.any { it.state == WebDavArtifactState.CONFLICT || it.state == WebDavArtifactState.SOURCE_UNAVAILABLE }) return AutoUploadOutcome.STOP
    if (!busy && message in stopMessages) return AutoUploadOutcome.STOP
    if (artifacts.any { it.attempt != null }) return AutoUploadOutcome.STOP // Needs reconciliation, never a blind retry.
    if (!busy && message !in transientMessages) return AutoUploadOutcome.STOP
    return if (runAttemptCount >= AUTO_UPLOAD_MAX_RUN_ATTEMPTS) AutoUploadOutcome.STOP else AutoUploadOutcome.RETRY
}
