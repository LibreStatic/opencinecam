/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size

data class LocalMediaItem(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val thumbnail: Bitmap?,
)

class LocalMediaRepository(private val context: Context) {
    fun recent(limit: Int = 60): List<LocalMediaItem> = buildList {
        addAll(query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "DCIM/OpenCineCam/"))
        addAll(query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "DCIM/OpenCineCam/"))
        addAll(query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music/OpenCineCam/"))
    }.sortedByDescending { it.modifiedSeconds }.take(limit)

    private fun query(collection: Uri, relativePath: String): List<LocalMediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        return buildList {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(relativePath),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    val thumbnail = runCatching { context.contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
                    add(LocalMediaItem(uri, cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getLong(4), thumbnail))
                }
            }
        }
    }
}
