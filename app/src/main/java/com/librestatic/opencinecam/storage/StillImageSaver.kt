/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentResolver
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

/**
 * Publishes stills on the phone (MediaStore) or, through the internal overloads that take a [RecordingDestination]
 * last, in a group folder on a drive (PLAN-072). The public overloads always use the phone.
 */
class StillImageSaver internal constructor(context: Context, private val recovery: StillCaptureRecovery) {
    constructor(context: Context) : this(context, StillCaptureRecovery(context))

    private val resolver = context.applicationContext.contentResolver
    private val ownerPackage = context.applicationContext.packageName

    fun recoverInterrupted(): StillRecoveryReport = recovery.recover()

    fun saveCapture(capture: CapturedStill, productionSlate: ProductionSlateSettings? = null, captureNames: CaptureNameSnapshot? = null): StillPublication =
        saveCapture(capture, productionSlate, captureNames, RecordingDestination.Phone)

    internal fun saveCapture(capture: CapturedStill, productionSlate: ProductionSlateSettings?, captureNames: CaptureNameSnapshot?,
        destination: RecordingDestination): StillPublication = publish(destination) { id, store ->
        publishStillCapture(capture, store, id, productionSlate, captureNames)
    }

    fun saveBurst(capture: CapturedBurst, productionSlate: ProductionSlateSettings? = null, captureNames: CaptureNameSnapshot? = null): BurstPublication =
        saveBurst(capture, productionSlate, captureNames, RecordingDestination.Phone)

    internal fun saveBurst(capture: CapturedBurst, productionSlate: ProductionSlateSettings?, captureNames: CaptureNameSnapshot?,
        destination: RecordingDestination): BurstPublication = publish(destination) { id, store ->
        publishBurstCapture(capture, store, id, productionSlate, captureNames)
    }

    fun saveBracket(capture: CapturedBracket, productionSlate: ProductionSlateSettings? = null, captureNames: CaptureNameSnapshot? = null): BracketPublication =
        saveBracket(capture, productionSlate, captureNames, RecordingDestination.Phone)

    internal fun saveBracket(capture: CapturedBracket, productionSlate: ProductionSlateSettings?, captureNames: CaptureNameSnapshot?,
        destination: RecordingDestination): BracketPublication = publish(destination) { id, store ->
        publishBracketCapture(capture, store, id, productionSlate, captureNames)
    }

    fun saveAccumulation(capture: CapturedAccumulation, productionSlate: ProductionSlateSettings? = null, captureNames: CaptureNameSnapshot? = null): AccumulationPublication =
        saveAccumulation(capture, productionSlate, captureNames, RecordingDestination.Phone)

    internal fun saveAccumulation(capture: CapturedAccumulation, productionSlate: ProductionSlateSettings?, captureNames: CaptureNameSnapshot?,
        destination: RecordingDestination): AccumulationPublication = publish(destination) { id, store ->
        publishAccumulationCapture(capture, store, id, productionSlate, captureNames)
    }

    /** One journalled group on [destination]: the drive's store from the journal, or the MediaStore one for the phone. */
    private fun <T> publish(destination: RecordingDestination, block: (String, StillPublicationStore) -> T): T =
        recovery.publish(destination) { id, drive ->
            block(id, drive ?: publicationStore(StillCaptureRecovery.relativePath(id, false), StillCaptureRecovery.relativePath(id, true)))
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
            resolver.verifyStillBytes(row, size, sha256)
            verifyIdentity()
        }
        override fun publish(uri: String): Int = resolver.update(uri.toUri(),
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        override fun delete(uri: String) { check(resolver.delete(uri.toUri(), null, null) == 1) { "Still artifact compensation did not delete its row" } }
    }

    fun saveJpeg(bytes: ByteArray, productionSlate: ProductionSlateSettings? = null, captureNames: CaptureNameSnapshot? = null): Uri {
        return save(bytes, "jpg", "image/jpeg", productionSlate, captureNames, RecordingDestination.Phone)
    }

    internal fun saveJpeg(bytes: ByteArray, productionSlate: ProductionSlateSettings?, captureNames: CaptureNameSnapshot?,
        destination: RecordingDestination): Uri = save(bytes, "jpg", "image/jpeg", productionSlate, captureNames, destination)

    fun saveDng(bytes: ByteArray, productionSlate: ProductionSlateSettings? = null, captureNames: CaptureNameSnapshot? = null): Uri =
        save(bytes, "dng", "image/x-adobe-dng", productionSlate, captureNames, RecordingDestination.Phone)

    internal fun saveDng(bytes: ByteArray, productionSlate: ProductionSlateSettings?, captureNames: CaptureNameSnapshot?,
        destination: RecordingDestination): Uri = save(bytes, "dng", "image/x-adobe-dng", productionSlate, captureNames, destination)

    private fun save(bytes: ByteArray, extension: String, mimeType: String, productionSlate: ProductionSlateSettings?,
        captureNames: CaptureNameSnapshot?, destination: RecordingDestination): Uri {
        require(bytes.isNotEmpty()) { "Image payload must not be empty" }
        return publish(destination) { id, store ->
            publishLegacyStill(bytes, extension, mimeType, store, id, productionSlate, captureNames).toUri()
        }
    }
}

/** The descriptor's size is [size] and the bytes read back hash to [sha256]: what was stored, not what was written. */
internal fun ContentResolver.verifyStillBytes(row: Uri, size: Long, sha256: String) {
    requireNotNull(openFileDescriptor(row, "r")).use { check(it.statSize == size) { "Still artifact descriptor size mismatch" } }
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    requireNotNull(openInputStream(row)) { "Still artifact reader unavailable" }.use { input ->
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
}
