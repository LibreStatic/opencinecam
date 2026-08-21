/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class StillImageSaver(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun saveJpeg(bytes: ByteArray): Uri {
        return save(bytes, "jpg", "image/jpeg")
    }

    fun saveDng(bytes: ByteArray): Uri = save(bytes, "dng", "image/x-adobe-dng")

    private fun save(bytes: ByteArray, extension: String, mimeType: String): Uri {
        require(bytes.isNotEmpty()) { "Image payload must not be empty" }
        val name = "OCC_${FILE_TIME.format(Instant.now())}.$extension"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/OpenCineCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "MediaStore did not create an image entry"
        }
        try {
            resolver.openOutputStream(uri, "w")!!.use { output -> output.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    companion object {
        private val FILE_TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneId.systemDefault())
    }
}
