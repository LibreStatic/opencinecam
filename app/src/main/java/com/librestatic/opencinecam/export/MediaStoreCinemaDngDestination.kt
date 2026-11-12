/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.export

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import com.librestatic.opencinecam.storage.CINEMADNG_EXPORT_PATH
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.WritableByteChannel

/**
 * `Documents/OpenCineCam/CinemaDNG/<take>/` through `MediaStore.Files` (OCC-PLAN-071 §8). Every
 * file is inserted pending, so no other app sees it, and [CinemaDngTarget.publish] clears
 * `IS_PENDING` on all of them at the end. Discarding deletes the rows, then the empty folder.
 *
 * `.nomedia`: the row is inserted like any other file, and its display name read back. If
 * MediaStore refused or renamed it, the row is deleted and the export goes on without one; the
 * outcome reports it. Whether MediaProvider accepts a dot-file name on API 30+ needs a device.
 */
internal class MediaStoreCinemaDngDestination(context: Context) : CinemaDngDestination {
    private val resolver = context.applicationContext.contentResolver
    private val packageName = context.applicationContext.packageName

    override fun freeBytes(): Long? = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes }.getOrNull()

    override fun open(takeName: String): CinemaDngTarget {
        val folder = CinemaDngExporter.folderName(takeName) { name -> folderTaken("$CINEMADNG_EXPORT_PATH$name/") }
        return Target("$CINEMADNG_EXPORT_PATH$folder/")
    }

    /** A folder is taken when it exists on disk or holds a row we can see, pending ones included. */
    private fun folderTaken(relativePath: String): Boolean {
        if (folderOnDisk(relativePath).exists()) return true
        return rowsIn(relativePath, ownOnly = false).isNotEmpty()
    }

    private fun rowsIn(relativePath: String, ownOnly: Boolean): List<Uri> {
        val selection = buildString {
            append("${MediaStore.MediaColumns.RELATIVE_PATH} = ?")
            if (ownOnly) append(" AND owner_package_name = ?")
        }
        val args = if (ownOnly) arrayOf(relativePath, packageName) else arrayOf(relativePath)
        val collection = collection()
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }, null)
        } else {
            @Suppress("DEPRECATION")
            resolver.query(MediaStore.setIncludePending(collection), arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
        }
        return cursor?.use { buildList { while (it.moveToNext()) add(android.content.ContentUris.withAppendedId(collection, it.getLong(0))) } }
            ?: emptyList()
    }

    private inner class Target(private val relativePath: String) : CinemaDngTarget {
        private val rows = ArrayList<Uri>()
        override val location: String = relativePath.removeSuffix("/")

        override fun writeNoMedia(): Boolean = try {
            write(CinemaDngExporter.NOMEDIA, CinemaDngExporter.NOMEDIA_MIME_TYPE) { }
            true
        } catch (failure: Exception) {
            Log.i(LOG_TAG, "MediaStore refused a .nomedia in $relativePath", failure)
            false
        }

        override fun write(name: String, mimeType: String, body: (WritableByteChannel) -> Unit) {
            val uri = resolver.insert(collection(), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: throw IOException("MediaStore did not create $name")
            rows += uri
            try {
                // MediaStore renames a clash ("name (1).dng"), which would break the frame sequence.
                val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (actual != name) throw IOException("MediaStore named $name \"$actual\"")
                val descriptor = resolver.openFileDescriptor(uri, "w") ?: throw IOException("$name could not be opened")
                descriptor.use { FileOutputStream(it.fileDescriptor).channel.use(body) }
            } catch (failure: Throwable) {
                try { resolver.delete(uri, null, null) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                rows -= uri
                throw failure
            }
        }

        override fun publish() {
            val authority = MediaStore.AUTHORITY
            for (batch in rows.chunked(BATCH)) {
                val operations = batch.map { uri ->
                    ContentProviderOperation.newUpdate(uri).withValue(MediaStore.MediaColumns.IS_PENDING, 0).build()
                }
                val results = resolver.applyBatch(authority, ArrayList(operations))
                if (results.any { (it.count ?: 0) != 1 }) throw IOException("MediaStore did not publish every file")
            }
        }

        override fun discard(): Boolean {
            var clean = true
            for (batch in rows.chunked(BATCH)) {
                try {
                    resolver.applyBatch(MediaStore.AUTHORITY, ArrayList(batch.map { ContentProviderOperation.newDelete(it).build() }))
                } catch (failure: Exception) {
                    Log.w(LOG_TAG, "Deleting the partial CinemaDNG export failed", failure)
                    clean = false
                }
            }
            rows.clear()
            // Rows that slipped past the list (a crash between insert and add) are pending and ours.
            clean = runCatching { rowsIn(relativePath, ownOnly = true).forEach { resolver.delete(it, null, null) } }.isSuccess && clean
            // MediaStore leaves the emptied folder behind; removing it is best effort.
            runCatching { folderOnDisk(relativePath).delete() }
            return clean
        }
    }

    /** Deletes this app's pending rows a killed export left under the CinemaDNG folder. */
    fun deleteLeftovers() {
        runCatching {
            val collection = collection()
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND owner_package_name = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 1"
            val args = arrayOf("$CINEMADNG_EXPORT_PATH%", packageName)
            val deleted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                resolver.delete(collection, Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                })
            } else {
                @Suppress("DEPRECATION")
                resolver.delete(MediaStore.setIncludePending(collection), selection, args)
            }
            if (deleted > 0) Log.i(LOG_TAG, "Deleted $deleted files a stopped CinemaDNG export left pending")
        }.onFailure { Log.w(LOG_TAG, "Leftover CinemaDNG files could not be listed", it) }
    }

    private fun folderOnDisk(relativePath: String): File = File(Environment.getExternalStorageDirectory(), relativePath)

    companion object {
        private const val LOG_TAG = "CinemaDngExport"
        private const val BATCH = 200

        fun collection(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
