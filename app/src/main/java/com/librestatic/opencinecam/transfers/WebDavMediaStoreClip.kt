/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Adapter for app-published MediaStore video, photo or sidecar rows. Construct/schedule only
 * after the complete paired take has finalized, not merely after its first row is published.
 * IS_PENDING is checked again before reading and after streaming by the transport.
 */
class WebDavMediaStoreClip(private val resolver: ContentResolver, private val uri: Uri) : WebDavClipSource {
    init {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == MediaStore.AUTHORITY)
        require(uri.encodedQuery == null && uri.encodedFragment == null)
        val segments = uri.pathSegments
        require(segments.size in 3..4 && uri.path?.endsWith('/') == false)
        require(segments.first().matches(Regex("[A-Za-z0-9_-]+")))
        require(segments.last().matches(Regex("[0-9]+")) && (segments.last().toLongOrNull() ?: 0L) > 0L)
        val collectionPath = segments.subList(1, segments.lastIndex)
        require(collectionPath in listOf(
            listOf("video", "media"), listOf("images", "media"), listOf("audio", "media"),
            listOf("downloads"), listOf("file"),
        ))
    }

    override fun snapshot(): WebDavClipSnapshot {
        val fields = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        return resolver.query(uri, fields, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.count != 1) throw FileNotFoundException("Published clip is missing")
            WebDavClipSnapshot(
                identity = uri.toString(),
                displayName = cursor.getString(0).orEmpty(),
                sizeBytes = if (cursor.isNull(1)) -1 else cursor.getLong(1),
                modifiedSeconds = cursor.getLong(2),
                finalized = !cursor.isNull(3) && cursor.getInt(3) == 0,
                mimeType = cursor.getString(4) ?: "application/octet-stream",
            )
        } ?: throw FileNotFoundException("Published clip is missing")
    }

    override fun open(): InputStream {
        if (!snapshot().finalized) throw FileNotFoundException("Clip is not finalized")
        return resolver.openInputStream(uri) ?: throw FileNotFoundException("Published clip is missing")
    }
}
