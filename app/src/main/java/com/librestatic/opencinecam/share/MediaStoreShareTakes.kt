/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import com.librestatic.opencinecam.storage.CloseOnce
import com.librestatic.opencinecam.storage.DriveDescriptors
import com.librestatic.opencinecam.storage.RAW_VIDEO_EXTENSION
import com.librestatic.opencinecam.storage.RAW_VIDEO_PATH
import com.librestatic.opencinecam.storage.isRawVideoRow
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.channels.FileChannel

/**
 * The RAW takes the gallery lists (PLAN-071 decision 7): this app's committed `.ocraw` rows
 * directly in `Documents/OpenCineCam/RAW/`, newest first, read with the same selection as
 * `LocalMediaRepository`. Each read opens its own read-only descriptor through MediaStore.
 */
internal class MediaStoreShareTakes(context: Context) : ShareTakeSource {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    override fun takes(): List<ShareTake> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "owner_package_name = ? AND is_pending = 0 AND relative_path = ? AND _display_name LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(context.packageName, RAW_VIDEO_PATH, "%$RAW_VIDEO_EXTENSION"))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_modified DESC, _id ASC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_TAKES)
        }
        val projection = arrayOf("_id", "_display_name", "_size", "date_modified", "relative_path", "owner_package_name", "is_pending")
        val cursor = resolver.query(collection, projection, query, null) ?: return emptyList()
        return cursor.use {
            buildList {
                var scanned = 0
                while (scanned < MAX_TAKES && cursor.moveToNext()) {
                    scanned++
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1).orEmpty()
                    val size = cursor.getLong(2)
                    val modifiedSeconds = cursor.getLong(3)
                    val path = cursor.getString(4).orEmpty()
                    // The provider may ignore parts of the selection: check every row again.
                    if (id <= 0 || size < 0 || cursor.getString(5) != context.packageName || cursor.getInt(6) != 0 || !isRawVideoRow(path, name)) continue
                    val uri = ContentUris.withAppendedId(collection, id)
                    add(ShareTake(uri.toString(), name, size, modifiedSeconds * 1000) { openShareFile(resolver, uri) })
                }
            }
        }
    }

    private companion object {
        const val MAX_TAKES = 1000
    }
}

/**
 * A read-only descriptor on [uri] (a MediaStore row or a drive document) as a [ShareOpenFile]. On the drive it is
 * registered with [DriveDescriptors] for as long as a client keeps the file open, so a drive that leaves has it closed
 * (ADR-0037) and the client's next read fails.
 */
internal fun openShareFile(resolver: ContentResolver, uri: android.net.Uri): ShareOpenFile {
    val mark = DriveDescriptors.releaseMark(uri)
    val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("take unavailable")
    val stream = try { FileInputStream(descriptor.fileDescriptor) } catch (error: RuntimeException) { descriptor.close(); throw error }
    val closer = CloseOnce(stream, descriptor)
    val driveHandle = DriveDescriptors.registerDocument(uri, closer, openedAfter = mark)
    return object : ShareOpenFile {
        override val channel: FileChannel = stream.channel
        override fun close() {
            driveHandle.unregister()
            runCatching { closer.close() }
        }
    }
}
