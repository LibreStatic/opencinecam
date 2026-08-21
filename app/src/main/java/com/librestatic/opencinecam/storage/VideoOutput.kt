/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.librestatic.opencinecam.camera.RecordingGeometry

class VideoOutput private constructor(
    private val context: Context,
    val uri: Uri,
    val descriptor: ParcelFileDescriptor,
    val displayName: String,
) : AutoCloseable {
    private var finished = false

    fun finish(
        success: Boolean,
        sidecarJson: String? = null,
        expectedGeometry: RecordingGeometry? = null,
    ): Uri? {
        if (finished) return if (success) uri else null
        finished = true
        descriptor.close()
        return if (success) {
            try {
                expectedGeometry?.let { expected ->
                    val validation = VideoFileGeometryValidator(context).validate(uri, expected)
                    check(validation.valid) { validation.message }
                }
                sidecarJson?.let(::writeSidecar)
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                uri
            } catch (failure: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw failure
            }
        } else {
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    override fun close() { finish(false) }

    private fun writeSidecar(json: String) {
        val sidecarName = displayName.removeSuffix(".mp4") + ".oclog.json"
        val resolver = context.contentResolver
        val sidecar = requireNotNull(resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, sidecarName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/OpenCineCam")
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        ))
        try {
            requireNotNull(resolver.openOutputStream(sidecar, "w")).use { it.write(json.toByteArray()) }
            resolver.update(sidecar, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (failure: Throwable) {
            resolver.delete(sidecar, null, null)
            throw failure
        }
    }

    companion object {
        fun create(context: Context): VideoOutput {
            val name = "OCC_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.mp4"
            val uri = requireNotNull(context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/OpenCineCam")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                },
            ))
            return try {
                val pfd = requireNotNull(context.contentResolver.openFileDescriptor(uri, "rw"))
                VideoOutput(context.applicationContext, uri, pfd, name)
            } catch (failure: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw failure
            }
        }
    }
}
