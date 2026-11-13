/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.playback.probeAudioMetadata
import com.librestatic.opencinecam.playback.probeClipMetadata
import com.librestatic.opencinecam.storage.LocalMediaArtifact
import com.librestatic.opencinecam.storage.LocalMediaKind
import com.librestatic.opencinecam.storage.LocalMediaTake
import com.librestatic.opencinecam.storage.DriveDescriptors
import com.librestatic.opencinecam.storage.useOnDrive
import com.librestatic.opencinecam.storage.RecordingDestinationStore
import com.librestatic.opencinecam.storage.drivePath
import com.librestatic.opencinecam.storage.isDriveUri
import com.librestatic.opencinecam.storage.isClipSidecarAttachment
import com.librestatic.opencinecam.storage.parseOcLogSidecar
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Sidecars and attachments are read whole only up to this, as the catalog does. */
private const val DETAIL_JSON_BYTES = 512 * 1024

/** [DetailText] over the app's resources and the device's locale. */
internal fun detailText(context: Context): DetailText {
    val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    return DetailText({ id, args -> context.getString(id, *args) }, { Formatter.formatFileSize(context, it) },
        { synchronized(dates) { dates.format(Date(it)) } })
}

/**
 * Reads everything the inspector lists about [take]: its files, sidecars and the media itself. Blocking
 * IO; call on an IO dispatcher. Each reader that fails only drops its own rows; cancellation is checked
 * between readers.
 */
internal suspend fun readTakeDetails(context: Context, take: LocalMediaTake): TakeTechnicalDetails? {
    val resolver = context.contentResolver
    suspend fun <T> read(block: () -> T?): T? {
        currentCoroutineContext().ensureActive()
        return try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    val root = read { primaryVolumeRoot(context) }
    val driveLabel = if (take.originals.any { isDriveUri(it.uri) }) read { driveLabel(context) } else null
    val files = take.originals.map { artifact ->
        DetailFile(artifact, read {
            if (isDriveUri(artifact.uri)) driveLabel?.let { drivePath(it, artifact.uri) }
            else artifactPath(root, relativePath(resolver, artifact), artifact.name)
        })
    }
    var input = TakeDetailInputs(take.kind, files)
    when (take.kind) {
        LocalMediaKind.VIDEO -> {
            val clip = take.attachments.firstOrNull(::isClipSidecarAttachment)?.let { read { boundedText(resolver, it)?.let(::parseClipSidecarFacts) } }
            val documents = take.metadata.filter { it.mimeType == "application/json" }.mapNotNull { read { boundedText(resolver, it) } }
            val log = documents.firstNotNullOfOrNull { parseOcLogSidecar(it, take.primary.uri) }
            val hdr = documents.firstNotNullOfOrNull(::parseHdrVideoSidecar)
            val video = read { probeClipMetadata(context, take.primary.uri, log) }
            val bitrate = read { if (isDriveUri(take.primary.uri)) containerBitrate(context, take.primary) else mediaStoreBitrate(resolver, take.primary) }
            // A separate WAV or FLAC is the take's sound; otherwise the track muxed into the video.
            val sound = take.originals.firstOrNull { it.mimeType.startsWith("audio/") } ?: take.primary
            val audio = read { probeAudioMetadata(context, sound.uri) }
            input = input.copy(clip = clip, video = video, mediaStoreBitrate = bitrate, log = log, hdr = hdr, audio = audio)
        }
        LocalMediaKind.AUDIO -> input = input.copy(audio = read { probeAudioMetadata(context, take.primary.uri) })
        LocalMediaKind.PHOTO -> input = input.copy(exif = read { exifSnapshot(resolver, take.primary) }?.let { values -> values::get })
        LocalMediaKind.RAW_VIDEO -> input = input.copy(raw = read { ocrawSummary(resolver, take.primary) })
    }
    currentCoroutineContext().ensureActive()
    return buildTakeDetails(input, detailText(context))
}

private fun primaryVolumeRoot(context: Context): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.getSystemService(StorageManager::class.java)?.primaryStorageVolume?.directory?.absolutePath
    else @Suppress("DEPRECATION") Environment.getExternalStorageDirectory()?.absolutePath

private fun relativePath(resolver: ContentResolver, artifact: LocalMediaArtifact): String? =
    resolver.query(artifact.uri.toUri(), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }

private fun mediaStoreBitrate(resolver: ContentResolver, artifact: LocalMediaArtifact): Long? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return resolver.query(artifact.uri.toUri(), arrayOf(MediaStore.Video.VideoColumns.BITRATE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it > 0 } else null
    }
}

/** The recording drive's name as the system described it at adoption, else "USB drive" (PLAN-072). */
private fun driveLabel(context: Context): String =
    RecordingDestinationStore(context).label()?.takeIf { it.isNotBlank() } ?: context.getString(R.string.recording_destination_usb)

/** A drive file has no MediaStore row; its container's declared bit rate stands in. */
private fun containerBitrate(context: Context, artifact: LocalMediaArtifact): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, artifact.uri.toUri())
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0 }
    } finally {
        retriever.release()
    }
}

/** The document as UTF-8, or null past [DETAIL_JSON_BYTES] or when it is not valid UTF-8. */
private fun boundedText(resolver: ContentResolver, artifact: LocalMediaArtifact): String? {
    if (artifact.sizeBytes !in 1..DETAIL_JSON_BYTES.toLong()) return null
    return resolver.openInputStream(artifact.uri.toUri())?.use { stream ->
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (bytes.size() <= DETAIL_JSON_BYTES) {
            val count = stream.read(buffer, 0, minOf(buffer.size, DETAIL_JSON_BYTES + 1 - bytes.size()))
            if (count <= 0) break
            bytes.write(buffer, 0, count)
        }
        if (bytes.size() > DETAIL_JSON_BYTES) null
        else Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}

private fun exifSnapshot(resolver: ContentResolver, artifact: LocalMediaArtifact): Map<String, String>? =
    resolver.openFileDescriptor(artifact.uri.toUri(), "r")?.use { descriptor ->
        val exif = ExifInterface(descriptor.fileDescriptor)
        EXIF_DETAIL_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
    }

/** One channel for the whole read: the leading metadata, then the frame index (chunk and frame headers only). */
private fun ocrawSummary(resolver: ContentResolver, artifact: LocalMediaArtifact): OcrawSummary? =
    DriveDescriptors.releaseMark(artifact.uri.toUri()).let { mark ->
        resolver.openFileDescriptor(artifact.uri.toUri(), "r")?.useOnDrive(artifact.uri.toUri(), mark) { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val reader = OcrawStreamReader(channel, scratchBytes = OcrawFrameHeader.BYTES)
                val leading = reader.leadingMetadata().orEmpty()
                val index = reader.indexFrames()
                if (index.header == null) return@use null
                // The leading chunk's encoding names the take's codec; later metadata cannot change that.
                OcrawSummary.from(index.metadata, index, channel.size()).let { summary ->
                    summary.copy(leadingEncoding = leading["encoding"] ?: summary.leadingEncoding)
                }
            }
        }
    }
