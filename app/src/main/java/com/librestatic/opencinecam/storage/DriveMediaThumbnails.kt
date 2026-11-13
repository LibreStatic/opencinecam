/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.security.MessageDigest

/**
 * PLAN-072: the file name a drive thumbnail is cached under. MediaStore makes no thumbnail for a document on the drive,
 * so it is decoded from the file and kept in the app's cache; a file rewritten in place (another size or time) or a
 * different request size is a different key.
 */
internal fun driveThumbnailKey(uri: String, size: Int, sizeBytes: Long, modifiedSeconds: Long): String =
    MessageDigest.getInstance("SHA-256").digest("$uri|$size|$sizeBytes|$modifiedSeconds".toByteArray())
        .joinToString("") { "%02x".format(it) }.take(40) + ".jpg"

/**
 * A picture of a drive file at most [size] pixels on its long side: a video's first frame, a photo decoded subsampled
 * (a DNG's embedded preview first), none for audio. Blocking; null on any failure.
 */
internal fun driveThumbnail(context: Context, artifact: LocalMediaArtifact, size: Int): Bitmap? {
    val video = artifact.mimeType.startsWith("video/")
    if (!video && !artifact.mimeType.startsWith("image/")) return null
    val folder = File(context.cacheDir, THUMBNAIL_FOLDER)
    val cached = File(folder, driveThumbnailKey(artifact.uri, size, artifact.sizeBytes, artifact.modifiedSeconds))
    if (cached.isFile) BitmapFactory.decodeFile(cached.path)?.let { return it }
    val bitmap = runCatching { if (video) videoFrame(context, artifact, size) else photo(context, artifact, size) }.getOrNull() ?: return null
    runCatching {
        check(folder.isDirectory || folder.mkdirs())
        val partial = File(folder, cached.name + ".part")
        partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        check(partial.renameTo(cached))
        prune(folder)
    }
    return bitmap
}

private fun videoFrame(context: Context, artifact: LocalMediaArtifact, size: Int): Bitmap? {
    val retriever = MediaMetadataRetriever()
    val uri = artifact.uri.toUri()
    return try {
        // Opened here and held for the drive's volume monitor, not inside the retriever: a frame decode can outlast
        // the drive, and only a registered descriptor is closed on a pull (ADR-0037).
        context.contentResolver.openFileDescriptor(uri, "r")?.useOnDrive(uri) { descriptor ->
            retriever.setDataSource(descriptor.fileDescriptor)
            retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
        }
    } finally {
        retriever.release()
    }
}

private fun photo(context: Context, artifact: LocalMediaArtifact, size: Int): Bitmap? =
    // Held for the drive's volume monitor: an EXIF read or a decode can outlast the drive (ADR-0037).
    context.contentResolver.openFileDescriptor(artifact.uri.toUri(), "r")?.useOnDrive(artifact.uri.toUri()) { descriptor ->
        // Read through streams on this descriptor, never a dup of it: the volume monitor's close must reach every read.
        // The streams do not own the descriptor, which useOnDrive closes. ExifInterface is given the bare FileInputStream,
        // which it seeks through this descriptor instead of buffering a large DNG.
        fun stream() = java.io.BufferedInputStream(java.io.FileInputStream(descriptor.fileDescriptor))
        fun rewind() = android.system.Os.lseek(descriptor.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
        val exif = runCatching { ExifInterface(java.io.FileInputStream(descriptor.fileDescriptor)) }.getOrNull()
        // A DNG's full decode is a sensor-sized develop; its embedded preview is what a gallery wants.
        val preview = if (artifact.name.endsWith(".dng", ignoreCase = true)) exif?.thumbnailBitmap else null
        val decoded = preview ?: run {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            rewind()
            BitmapFactory.decodeStream(stream(), null, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@run null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
            rewind()
            BitmapFactory.decodeStream(stream(), null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: exif?.thumbnailBitmap ?: return@useOnDrive null
        val degrees = exif?.rotationDegrees ?: 0
        if (degrees == 0) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    }

/** Keeps the cache to [THUMBNAIL_LIMIT] files, dropping the least recently written. */
private fun prune(folder: File) {
    val files = folder.listFiles()?.takeIf { it.size > THUMBNAIL_LIMIT } ?: return
    files.sortedBy { it.lastModified() }.take(files.size - THUMBNAIL_LIMIT * 3 / 4).forEach { it.delete() }
}

private const val THUMBNAIL_FOLDER = "drive-thumbnails"
private const val THUMBNAIL_LIMIT = 600
