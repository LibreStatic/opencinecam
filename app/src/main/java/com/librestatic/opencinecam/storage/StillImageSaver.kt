/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.MediaStore
import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.camera.CapturedAccumulation
import com.librestatic.opencinecam.camera.CapturedBurst
import com.librestatic.opencinecam.camera.CapturedBracket
import com.librestatic.opencinecam.camera.CapturedStill
import java.security.MessageDigest

class StillImageSaver(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val ownerPackage = context.applicationContext.packageName

    private val recovery = StillCaptureRecovery(context)

    fun recoverInterrupted(): StillRecoveryReport = recovery.recover()

    fun saveCapture(capture: CapturedStill, productionSlate: ProductionSlateSettings? = null, captureNames: CaptureNameSnapshot? = null): StillPublication = recovery.publish { id, images, metadata ->
        publishStillCapture(capture, publicationStore(images, metadata), id, productionSlate, captureNames)
    }

    fun saveBurst(capture: CapturedBurst, productionSlate: ProductionSlateSettings? = null, captureNames: CaptureNameSnapshot? = null): BurstPublication = recovery.publish { id, images, metadata ->
        publishBurstCapture(capture, publicationStore(images, metadata), id, productionSlate, captureNames)
    }

    fun saveBracket(capture: CapturedBracket, productionSlate: ProductionSlateSettings? = null, captureNames: CaptureNameSnapshot? = null): BracketPublication = recovery.publish { id, images, metadata ->
        publishBracketCapture(capture, publicationStore(images, metadata), id, productionSlate, captureNames)
    }

    fun saveAccumulation(capture: CapturedAccumulation, productionSlate: ProductionSlateSettings? = null, captureNames: CaptureNameSnapshot? = null): AccumulationPublication = recovery.publish { id, images, metadata ->
        publishAccumulationCapture(capture, publicationStore(images, metadata), id, productionSlate, captureNames)
    }

    private fun publicationStore(imagesPath: String, metadataPath: String): StillPublicationStore = object : StillPublicationStore {
        override fun insert(displayName: String, mimeType: String, metadata: Boolean): String {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (metadata)
                    metadataPath else imagesPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = StillCaptureRecovery.collection(metadata)
            return requireNotNull(resolver.insert(collection, values)) { "MediaStore did not create a still artifact" }.toString()
        }
        override fun writeClosed(uri: String, bytes: ByteArray) {
            requireNotNull(resolver.openOutputStream(uri.toUri(), "w")) { "Still artifact writer unavailable" }.use { it.write(bytes) }
        }
        override fun verify(uri: String, displayName: String, mimeType: String, size: Long, sha256: String) {
            val row = uri.toUri()
            fun verifyIdentity() {
                requireNotNull(resolver.query(row, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.IS_PENDING,
                    MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)).use {
                    check(it.count == 1 && it.moveToFirst()) { "Still artifact row is missing or ambiguous" }
                    check(it.getString(0) == displayName && it.getString(1) == mimeType && it.getInt(2) == 1 &&
                        it.getString(3) == (if (mimeType == "application/json") metadataPath else imagesPath) &&
                        it.getString(4) == ownerPackage) {
                        "Still artifact identity or pending state changed"
                    }
                }
            }
            verifyIdentity()
            requireNotNull(resolver.openFileDescriptor(row, "r")).use { check(it.statSize == size) { "Still artifact descriptor size mismatch" } }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            requireNotNull(resolver.openInputStream(row)) { "Still artifact reader unavailable" }.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count > 0)
                    total = Math.addExact(total, count.toLong())
                    check(total <= size) { "Still artifact grew during verification" }
                    digest.update(buffer, 0, count)
                }
            }
            check(total == size && digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == sha256) {
                "Still artifact read-back checksum mismatch"
            }
            verifyIdentity()
        }
        override fun publish(uri: String): Int = resolver.update(uri.toUri(),
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        override fun delete(uri: String) { check(resolver.delete(uri.toUri(), null, null) == 1) { "Still artifact compensation did not delete its row" } }
    }

    fun saveJpeg(bytes: ByteArray, productionSlate: ProductionSlateSettings? = null, captureNames: CaptureNameSnapshot? = null): Uri {
        return save(bytes, "jpg", "image/jpeg", productionSlate, captureNames)
    }

    fun saveDng(bytes: ByteArray, productionSlate: ProductionSlateSettings? = null, captureNames: CaptureNameSnapshot? = null): Uri = save(bytes, "dng", "image/x-adobe-dng", productionSlate, captureNames)

    private fun save(bytes: ByteArray, extension: String, mimeType: String, productionSlate: ProductionSlateSettings?, captureNames: CaptureNameSnapshot? = null): Uri {
        require(bytes.isNotEmpty()) { "Image payload must not be empty" }
        return recovery.publish { id, images, metadata ->
            publishLegacyStill(bytes, extension, mimeType, publicationStore(images, metadata), id, productionSlate, captureNames).toUri()
        }
    }
}
