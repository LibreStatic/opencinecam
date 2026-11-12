/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.media.ocraw.CinemaDngWriter
import com.librestatic.opencinecam.media.raw.NativeRawCodec
import com.librestatic.opencinecam.storage.CINEMADNG_EXPORT_ACTIVE_TREES_KEY
import com.librestatic.opencinecam.storage.CINEMADNG_EXPORT_LEFT_TREES_KEY
import com.librestatic.opencinecam.storage.CINEMADNG_EXPORT_PREFS
import com.librestatic.opencinecam.storage.CINEMADNG_EXPORT_TREE_KEY
import com.librestatic.opencinecam.storage.CloseOnce
import com.librestatic.opencinecam.storage.DriveDescriptors
import com.librestatic.opencinecam.storage.RawVideoRecovery
import com.librestatic.opencinecam.storage.RecordingDestinationStore
import com.librestatic.opencinecam.transfers.WebDavTransferRuntime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One export as the gallery asks for it; [treeUri] null means `Documents/OpenCineCam/CinemaDNG/`. */
internal data class CinemaDngExportRequest(val takeUri: String, val takeName: String, val treeUri: String?, val compress: Boolean)

/**
 * The single CinemaDNG export slot (OCC-PLAN-071 §8, route 2): the state the gallery observes, and
 * the WorkManager unique work that runs [CinemaDngExportWorker]. The worker runs as a visible
 * dataSync foreground service, like the WebDAV auto-upload, through WorkManager's own
 * `SystemForegroundService`, which also holds a wake lock while the work is in the foreground.
 */
internal object CinemaDngExportRuntime : CinemaDngExportControl {
    val store = CinemaDngExportStates()
    override val states get() = store.states
    /** Set by [start] and by the worker itself: WorkManager restarts an export after process death, where [start] never ran. */
    @Volatile internal var appContext: Context? = null
    @Volatile internal var workerRunning = false

    /**
     * True while a take records: an encoded take holds the transfer runtime's capture reservation, and
     * a RAW take, which takes no reservation, keeps its `.ocraw` row live. An export never starts or
     * continues then, so it never competes with a take for storage bandwidth.
     */
    fun recording(context: Context): Boolean = RawVideoRecovery.anyLive() || WebDavTransferRuntime.get(context).captureActive

    /**
     * The export left the folder [tree] for another. An export queued or running may still write to it, so its grant goes
     * only once none does ([releaseLeftFolders]).
     */
    fun leaveFolder(context: Context, tree: String) {
        val preferences = context.getSharedPreferences(CINEMADNG_EXPORT_PREFS, Context.MODE_PRIVATE)
        RecordingDestinationStore(context).withGrants {
            preferences.edit().putStringSet(CINEMADNG_EXPORT_LEFT_TREES_KEY, preferences.getStringSet(CINEMADNG_EXPORT_LEFT_TREES_KEY, null).orEmpty() + tree).apply()
        }
        releaseLeftFolders(context)
    }

    /** Releases the grants of the folders the export left, unless an export is queued or running; the worker calls it as it ends. */
    fun releaseLeftFolders(context: Context) {
        val preferences = context.getSharedPreferences(CINEMADNG_EXPORT_PREFS, Context.MODE_PRIVATE)
        val recording = RecordingDestinationStore(context)
        recording.withGrants {
            val left = preferences.getStringSet(CINEMADNG_EXPORT_LEFT_TREES_KEY, null).orEmpty()
            // The lease covers an export WorkManager kept across a process death, where the state restarts idle.
            if (left.isEmpty() || workerRunning || store.states.value is CinemaDngExportState.Running ||
                preferences.contains(CINEMADNG_EXPORT_ACTIVE_TREES_KEY)) return@withGrants
            val current = preferences.getString(CINEMADNG_EXPORT_TREE_KEY, null)
            // The recording destination may use the same tree, or an interrupted take may wait on it; its permission then stays.
            // Forgotten first: a folder still listed as left keeps its grant.
            preferences.edit().remove(CINEMADNG_EXPORT_LEFT_TREES_KEY).apply()
            left.filter { it != current }.forEach { recording.releaseUnlessNeeded(it.toUri()) }
        }
    }

    /**
     * An export is queued: its lease is stored before the work, so a folder left meanwhile keeps its grant even when the
     * process dies before the worker runs. It holds the folder the export writes to ([CinemaDngExportRequest.treeUri],
     * null for Documents, which needs no grant) and, for a take on the USB drive, the folder it is read through: the
     * recording destination may change while the export waits. The set also keeps the folders of work WorkManager kept
     * from an earlier process, which a new request does not replace. [endLease] drops it.
     */
    private fun lease(context: Context, request: CinemaDngExportRequest) {
        val preferences = context.getSharedPreferences(CINEMADNG_EXPORT_PREFS, Context.MODE_PRIVATE)
        RecordingDestinationStore(context).withGrants {
            val held = preferences.getStringSet(CINEMADNG_EXPORT_ACTIVE_TREES_KEY, null).orEmpty()
            // An empty set still marks an export as queued.
            check(preferences.edit().putStringSet(CINEMADNG_EXPORT_ACTIVE_TREES_KEY, held + listOfNotNull(request.treeUri, sourceTree(request.takeUri))).commit()) {
                "The export folder lease was not stored"
            }
        }
    }

    /** The picked folder a drive take's document is read through: its URI names that tree; null for a phone take. */
    internal fun sourceTree(takeUri: String): String? = runCatching {
        val uri = takeUri.toUri()
        if (!DocumentsContract.isTreeUri(uri)) null
        else DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri)).toString()
    }.getOrNull()

    /**
     * No export is queued or running any more: the lease goes, and with it the grants of the folders left meanwhile and
     * of a leased folder nothing else uses (the take's folder on a drive the recording destination left).
     */
    fun endLease(context: Context) {
        val preferences = context.getSharedPreferences(CINEMADNG_EXPORT_PREFS, Context.MODE_PRIVATE)
        val recording = RecordingDestinationStore(context)
        recording.withGrants {
            val leased = preferences.getStringSet(CINEMADNG_EXPORT_ACTIVE_TREES_KEY, null).orEmpty()
            preferences.edit().remove(CINEMADNG_EXPORT_ACTIVE_TREES_KEY).commit()
            val current = preferences.getString(CINEMADNG_EXPORT_TREE_KEY, null)
            leased.filter { it != current }.forEach { recording.releaseUnlessNeeded(it.toUri()) }
        }
        releaseLeftFolders(context)
    }

    /**
     * An export WorkManager kept from an earlier process and has not run yet. The state restarted idle, yet the unique
     * work would keep that export and drop a new request, whose state and lease would then describe an export that never runs.
     */
    private fun keptWork(context: Context): Boolean = runCatching {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(UNIQUE_WORK).get(KEPT_WORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .any { !it.state.isFinished }
    }.getOrDefault(false)

    /** Starts [request]; false while another export runs or waits to. */
    fun start(context: Context, request: CinemaDngExportRequest): Boolean {
        val app = context.applicationContext
        if (store.states.value !is CinemaDngExportState.Running && keptWork(app)) return false
        if (!store.begin(request.takeUri, request.takeName)) return false
        appContext = app
        try {
            lease(app, request)
            WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, workRequest(request))
        } catch (failure: Exception) {
            runCatching { endLease(app) }
            store.finish(request.takeUri, request.takeName,
                CinemaDngExportOutcome.Stopped(CinemaDngStopReason.FAILED, cleaned = true, failure.message))
        }
        return true
    }

    override fun cancel() {
        val running = store.states.value as? CinemaDngExportState.Running ?: return
        store.cancelling()
        appContext?.let { runCatching { WorkManager.getInstance(it).cancelUniqueWork(UNIQUE_WORK) } }
        // Work cancelled before it started never runs, so nothing else would end the state.
        if (!workerRunning) {
            // The lease goes first: once the state is no longer Running a new export may start and lease its own trees.
            appContext?.let { runCatching { endLease(it) } }
            store.finish(running.takeUri, running.takeName, CinemaDngExportOutcome.Stopped(CinemaDngStopReason.CANCELLED, cleaned = true))
        }
    }

    override fun acknowledge() = store.acknowledge()

    const val UNIQUE_WORK = "cinemadng-export"
    private const val KEPT_WORK_TIMEOUT_MS = 2_000L
    const val KEY_TAKE_URI = "takeUri"
    const val KEY_TAKE_NAME = "takeName"
    const val KEY_TREE_URI = "treeUri"
    const val KEY_COMPRESS = "compress"

    fun workRequest(request: CinemaDngExportRequest): OneTimeWorkRequest = OneTimeWorkRequestBuilder<CinemaDngExportWorker>()
        .setInputData(workDataOf(KEY_TAKE_URI to request.takeUri, KEY_TAKE_NAME to request.takeName,
            KEY_TREE_URI to request.treeUri, KEY_COMPRESS to request.compress))
        .addTag(UNIQUE_WORK)
        .build()
}

/** Exports one RAW take; see [CinemaDngExporter] for what it writes and [CinemaDngExportRuntime] for the state. */
class CinemaDngExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    /** The export's lease is still to be ended: [finish] ends it, and [doWork] when it leaves without one. */
    @Volatile private var leased = true

    override suspend fun doWork(): Result {
        val runtime = CinemaDngExportRuntime
        val takeUri = inputData.getString(CinemaDngExportRuntime.KEY_TAKE_URI) ?: return Result.failure()
        val takeName = inputData.getString(CinemaDngExportRuntime.KEY_TAKE_NAME) ?: return Result.failure()
        val tree = inputData.getString(CinemaDngExportRuntime.KEY_TREE_URI)
        val compress = inputData.getBoolean(CinemaDngExportRuntime.KEY_COMPRESS, false)
        runtime.appContext = applicationContext
        runtime.workerRunning = true
        try {
            if (isStopped) return finish(takeUri, takeName, CinemaDngExportOutcome.Stopped(CinemaDngStopReason.CANCELLED, cleaned = true))
            runtime.store.progress(takeUri, takeName, CinemaDngExportProgress(0, 0, 0, 0))
            try {
                setForeground(foregroundInfo(takeName, null))
            } catch (failure: CancellationException) {
                // Cancelled before anything was written: cancel() left the state to this worker, so it ends here.
                withContext(NonCancellable) {
                    finish(takeUri, takeName, CinemaDngExportOutcome.Stopped(CinemaDngStopReason.CANCELLED, cleaned = true))
                }
                throw failure
            } catch (failure: Exception) {
                Log.w(LOG_TAG, "The export continues without a foreground service", failure)
            }
            // finish runs inside the block: withContext throws on exit once the work is cancelled,
            // so a result handed back to the caller would never reach the state or the notice.
            return withContext(NonCancellable + Dispatchers.IO) { finish(takeUri, takeName, export(takeUri, takeName, tree, compress)) }
        } finally {
            runtime.workerRunning = false
            // finish ended the lease while the worker still ran, which keeps the folders left meanwhile: they go now.
            runCatching { if (leased) runtime.endLease(applicationContext) else runtime.releaseLeftFolders(applicationContext) }
        }
    }

    private fun export(takeUri: String, takeName: String, tree: String?, compress: Boolean): CinemaDngExportOutcome {
        val context = applicationContext
        val runtime = CinemaDngExportRuntime
        if (runtime.recording(context)) return CinemaDngExportOutcome.Refused(CinemaDngRefusal.RECORDING)
        val destination = if (tree == null) MediaStoreCinemaDngDestination(context).also { it.deleteLeftovers() }
            else SafCinemaDngDestination(context, tree.toUri())
        val encoder = if (compress && NativeRawCodec.available) {
            NativeRawCodec.newEncoder(NativeRawCodec.Codec.LJ92, threads = encoderThreads())
        } else null
        try {
            val descriptor = context.contentResolver.openFileDescriptor(takeUri.toUri(), "r")
                ?: return CinemaDngExportOutcome.Refused(CinemaDngRefusal.UNREADABLE)
            return descriptor.use {
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(it.fileDescriptor)).channel.use { channel ->
                    // A take on the USB drive: both descriptors close with the drive (ADR-0037) and the export fails on its next read.
                    val closer = CloseOnce(channel, it)
                    val driveHandle = DriveDescriptors.registerDocument(takeUri.toUri(), closer)
                    try {
                    val exporter = CinemaDngExporter(channel, takeName, encoder?.let(CinemaDngWriter.Lj92Encoder::native), decoderThreads = 2)
                    var lastNotified = 0L
                    var lastPercent = -1
                    exporter.run(destination, isCancelled = { isStopped }, isRecording = { runtime.recording(context) }) { progress ->
                        runtime.store.progress(takeUri, takeName, progress)
                        val percent = (progress.fraction * 100).toInt()
                        val now = System.nanoTime()
                        if (percent != lastPercent && now - lastNotified > NOTIFY_INTERVAL_NS) {
                            lastPercent = percent
                            lastNotified = now
                            notify(PROGRESS_NOTIFICATION_ID, progressNotification(takeName, progress))
                        }
                    }
                    } finally {
                        // Closed while still registered: a drive pulled now finds them closed, never open and unwatched.
                        try { closer.close() } finally { driveHandle.unregister() }
                    }
                }
            }
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "The CinemaDNG export could not read its take", failure)
            return CinemaDngExportOutcome.Stopped(CinemaDngStopReason.FAILED, cleaned = true, failure.message)
        } finally {
            encoder?.close()
        }
    }

    private fun finish(takeUri: String, takeName: String, outcome: CinemaDngExportOutcome): Result {
        // The lease goes before the state stops being Running: a new export may start then and lease its own trees.
        leased = false
        runCatching { CinemaDngExportRuntime.endLease(applicationContext) }
        CinemaDngExportRuntime.store.finish(takeUri, takeName, outcome)
        Log.i(LOG_TAG, "CinemaDNG export of $takeName ended: $outcome")
        val text = cinemaDngOutcomeText(applicationContext, outcome)
        val notification = NotificationCompat.Builder(applicationContext, channel())
            .setSmallIcon(if (outcome is CinemaDngExportOutcome.Exported) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(text.title)
            .setContentText(text.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text.title, text.detail).joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        notify(RESULT_NOTIFICATION_ID, notification)
        return if (outcome is CinemaDngExportOutcome.Exported) Result.success() else Result.failure()
    }

    private fun foregroundInfo(takeName: String, progress: CinemaDngExportProgress?): ForegroundInfo =
        ForegroundInfo(PROGRESS_NOTIFICATION_ID, progressNotification(takeName, progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun progressNotification(takeName: String, progress: CinemaDngExportProgress?) =
        NotificationCompat.Builder(applicationContext, channel())
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.cinemadng_export_notification_title, takeName))
            .apply {
                if (progress == null || progress.total == 0) setProgress(0, 0, true)
                else {
                    setProgress(progress.total, progress.done, false)
                    setContentText(applicationContext.getString(R.string.cinemadng_export_notification_progress,
                        progress.done, progress.total, (progress.fraction * 100).toInt()))
                }
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, applicationContext.getString(R.string.cinemadng_export_cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .setContentIntent(openApp())
            .build()

    private fun channel(): String {
        applicationContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            applicationContext.getString(R.string.cinemadng_export_channel), NotificationManager.IMPORTANCE_LOW))
        return CHANNEL_ID
    }

    private fun openApp(): PendingIntent? = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        ?.let { PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }

    private fun notify(id: Int, notification: android.app.Notification) {
        // Without the notification permission the export still runs; only the shade stays quiet.
        runCatching { applicationContext.getSystemService(NotificationManager::class.java)?.notify(id, notification) }
    }

    private companion object {
        const val LOG_TAG = "CinemaDngExport"
        const val CHANNEL_ID = "exports"
        const val PROGRESS_NOTIFICATION_ID = 0x7d18
        const val RESULT_NOTIFICATION_ID = 0x7d19
        const val NOTIFY_INTERVAL_NS = 500_000_000L

        fun encoderThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}

/** A finished export in words: [title] for the notice's first line, [detail] for what follows. */
internal data class CinemaDngOutcomeText(val title: String, val detail: String?)

internal fun cinemaDngOutcomeText(context: Context, outcome: CinemaDngExportOutcome): CinemaDngOutcomeText {
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    return when (outcome) {
        is CinemaDngExportOutcome.Exported -> {
            val lines = buildList {
                add(context.getString(if (outcome.audio) R.string.cinemadng_export_done_frames_audio else R.string.cinemadng_export_done_frames,
                    outcome.frames, size(outcome.bytes)))
                if (outcome.skipped > 0) add(context.getString(R.string.cinemadng_export_skipped, outcome.skipped))
                if (outcome.audioOmitted) add(context.getString(R.string.cinemadng_export_audio_omitted))
                if (!outcome.noMedia) add(context.getString(R.string.cinemadng_export_nomedia_failed))
            }
            CinemaDngOutcomeText(context.getString(R.string.cinemadng_export_done, outcome.location), lines.joinToString(" "))
        }
        is CinemaDngExportOutcome.Refused -> CinemaDngOutcomeText(when (outcome.reason) {
            CinemaDngRefusal.RECORDING -> context.getString(R.string.cinemadng_export_refused_recording)
            CinemaDngRefusal.UNREADABLE -> context.getString(R.string.cinemadng_export_unreadable)
            CinemaDngRefusal.NO_FRAMES -> context.getString(R.string.cinemadng_export_no_frames)
            CinemaDngRefusal.NO_SPACE -> context.getString(R.string.cinemadng_export_space_short, size(outcome.neededBytes),
                outcome.freeBytes?.let(::size) ?: "?")
        }, null)
        is CinemaDngExportOutcome.Stopped -> {
            val title = when (outcome.reason) {
                CinemaDngStopReason.CANCELLED -> R.string.cinemadng_export_cancelled
                CinemaDngStopReason.RECORDING -> R.string.cinemadng_export_stopped_recording
                CinemaDngStopReason.FAILED -> R.string.cinemadng_export_failed
            }
            // No folder was made when the export stopped before its first file.
            val detail = when {
                outcome.location == null -> null
                outcome.cleaned -> context.getString(R.string.cinemadng_export_partial_deleted)
                else -> context.getString(R.string.cinemadng_export_partial_left, outcome.location)
            }
            CinemaDngOutcomeText(context.getString(title), detail)
        }
    }
}
