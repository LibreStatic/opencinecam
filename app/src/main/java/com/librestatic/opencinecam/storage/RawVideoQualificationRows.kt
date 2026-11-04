/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * OCC-PLAN-071: the scratch rows of RAW video qualification. The storage sweep and every live run
 * write a pending `occ-raw-qual-*.ocraw` row in `Documents/OpenCineCam/RAW/`, the destination real
 * takes use, so the measurement covers the same path. The rows are registered live with
 * [RawVideoRecovery] while written, never recovered as takes ([RawVideoRecovery] skips the prefix),
 * and deleted after each step; [purgeOrphans] removes what a killed process left behind.
 */
object RawVideoQualificationRows {
    const val NAME_PREFIX = "occ-raw-qual-"

    /** Inserts a pending row labelled [label] and registers it live. */
    fun insert(context: Context, label: String): Uri = RawVideoRecovery.insertLive {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$NAME_PREFIX$label-${System.currentTimeMillis()}${RawVideoOutput.EXTENSION}")
            put(MediaStore.MediaColumns.MIME_TYPE, RawVideoOutput.MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RawVideoOutput.RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        requireNotNull(context.contentResolver.insert(RawVideoOutput.collection(), values)) { "MediaStore refused the pending row" }
    }

    /** Deletes [uri] (failures ignored) and unregisters it. */
    fun delete(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
        RawVideoRecovery.release(uri)
    }

    /** Deletes this app's pending qualification rows that nothing in this process holds; returns how many went. */
    fun purgeOrphans(context: Context): Int = runCatching {
        if (MediaStore.VOLUME_EXTERNAL_PRIMARY !in MediaStore.getExternalVolumeNames(context)) return 0
        val collection = RawVideoOutput.collection()
        @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
        val rows = context.contentResolver.query(all, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(RawVideoOutput.RELATIVE_PATH, context.packageName, "$NAME_PREFIX%"), null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        }.orEmpty()
        rows.count { uri -> !RawVideoRecovery.isLive(uri) && runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false) }
    }.getOrDefault(0)
}
