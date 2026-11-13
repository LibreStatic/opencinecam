/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.librestatic.opencinecam.rawvideo.RawTakeFileDecision
import com.librestatic.opencinecam.rawvideo.recoverRawTakeFile

/** RAW takes found pending after process death: [published] held frames, [deleted] held none. */
data class RawVideoRecoveryReport(val published: Int = 0, val deleted: Int = 0, val unresolved: Int = 0) {
    /** Two passes' counts together (the phone's MediaStore pass and a drive's journal pass, PLAN-072). */
    operator fun plus(other: RawVideoRecoveryReport) =
        RawVideoRecoveryReport(published + other.published, deleted + other.deleted, unresolved + other.unresolved)
}

/**
 * Process-death recovery of RAW video takes (PLAN-071). A take's `.ocraw` row stays `IS_PENDING=1`
 * until the recorder publishes it. On the next recovery pass, every pending `.ocraw` row this app
 * owns in `Documents/OpenCineCam/RAW/` that no live [RawVideoOutput] holds is repaired in place with
 * [recoverRawTakeFile] (torn tail cut, `FINAL_INDEX` and `END` appended), then published when it holds
 * at least one complete frame and deleted otherwise. Failures leave the row pending for the next pass.
 *
 * Qualification scratch rows ([RawVideoQualificationRows]) are never takes: a pass deletes the
 * orphaned ones and never repairs or publishes them.
 *
 * CaptureService runs it at service start and before every RAW take, off the main thread.
 *
 * A take on a USB drive (PLAN-072 Delivery 3, [SafRawVideoOutput]) is not a MediaStore row: it is journalled in
 * `recording-recovery.sqlite` and [RecordingCaptureRecovery.recover] repairs it with the same [recoverRawTakeFile] when
 * the drive is mounted, deferring it while the drive is absent or its grant lost. The live set below covers both.
 */
class RawVideoRecovery(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    /**
     * One pass. Passes are serialised; the live registry is only locked per row, so a take can start
     * while a large file is being repaired (a row created after the query is not in this pass).
     */
    fun recover(): RawVideoRecoveryReport = synchronized(passLock) {
        RawVideoQualificationRows.purgeOrphans(context)
        val rows = pendingRows()
        recoverPendingRawTakes(
            rows = rows,
            isLive = ::isLive,
            recoverRow = { uri ->
                requireNotNull(resolver.openFileDescriptor(uri, "rw")) { "RAW take could not be opened" }.use { descriptor ->
                    DuplexFileChannel.open(descriptor.fileDescriptor).use { channel -> recoverRawTakeFile(channel).decision }
                }
            },
            publish = { uri ->
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                    "Recovered RAW take did not publish"
                }
            },
            delete = { uri -> check(resolver.delete(uri, null, null) in 0..1) },
        ).also { report ->
            if (report != RawVideoRecoveryReport()) {
                android.util.Log.i(TAG, "Recovered interrupted RAW takes: published=${report.published} " +
                    "deleted=${report.deleted} unresolved=${report.unresolved}")
            }
        }
    }

    private fun pendingRows(): List<Uri> {
        if (MediaStore.VOLUME_EXTERNAL_PRIMARY !in MediaStore.getExternalVolumeNames(context)) return emptyList()
        val collection = RawVideoOutput.collection()
        @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
        return resolver.query(all, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE ?",
            arrayOf(RawVideoOutput.RELATIVE_PATH, context.packageName, "%" + RawVideoOutput.EXTENSION,
                RawVideoQualificationRows.NAME_PREFIX + "%"), null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        }.orEmpty()
    }

    companion object {
        private const val TAG = "RawVideoRecovery"
        private val lock = Any()
        private val passLock = Any()

        // Row URIs only; a process restart starts empty, which is what makes their rows recoverable.
        private val live = mutableSetOf<String>()

        /** Inserts a row with [insert] and registers it as live in one step, so no pass can see it unowned. */
        internal fun insertLive(insert: () -> Uri): Uri = synchronized(lock) {
            insert().also { live += it.toString() }
        }

        internal fun release(uri: Uri) = synchronized(lock) { live -= uri.toString() }

        internal fun isLive(uri: Uri): Boolean = synchronized(lock) { uri.toString() in live }

        /** True while this process writes a RAW take (the CinemaDNG export never runs then). */
        internal fun anyLive(): Boolean = synchronized(lock) { live.isNotEmpty() }
    }
}

/**
 * The decision loop of [RawVideoRecovery.recover], free of MediaStore: each row that is not [isLive]
 * is recovered; [RawTakeFileDecision.PUBLISH] publishes it and [RawTakeFileDecision.DELETE] deletes
 * it. A failing row is counted unresolved and left for the next pass.
 */
internal fun <U> recoverPendingRawTakes(
    rows: List<U>,
    isLive: (U) -> Boolean,
    recoverRow: (U) -> RawTakeFileDecision,
    publish: (U) -> Unit,
    delete: (U) -> Unit,
): RawVideoRecoveryReport {
    var published = 0
    var deleted = 0
    var unresolved = 0
    for (row in rows) {
        if (isLive(row)) continue
        try {
            when (recoverRow(row)) {
                RawTakeFileDecision.PUBLISH -> { publish(row); published++ }
                RawTakeFileDecision.DELETE -> { delete(row); deleted++ }
            }
        } catch (failure: Exception) {
            unresolved++
            android.util.Log.e("RawVideoRecovery", "Retained unresolved RAW take $row", failure)
        }
    }
    return RawVideoRecoveryReport(published, deleted, unresolved)
}
