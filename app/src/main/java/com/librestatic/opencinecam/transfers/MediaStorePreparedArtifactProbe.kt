/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import android.system.StructStat
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import java.io.FileNotFoundException
import java.net.URI
import java.util.Locale

/**
 * Descriptor-backed observation after the caller has retired its writers. This reader never
 * publishes, repairs, renames or hashes a row. The before/after checks detect observed changes;
 * they do not lock the provider or promise immutability after this method returns.
 */
class MediaStorePreparedArtifactProbe(private val resolver: ContentResolver) : PreparedArtifactProbe {
    override fun inspect(artifact: PreparedCaptureArtifact, pending: Boolean): WebDavClipSnapshot {
        val identity = validateIdentity(artifact)
        val before = readMetadata(identity, artifact, pending)
        val descriptor = resolver.openFileDescriptor(identity.uri, "r")
            ?: throw FileNotFoundException("Prepared artifact descriptor is unavailable")
        return descriptor.use {
            requireReadOnlyPreparedDescriptor(it)
            val firstStat = Os.fstat(it.fileDescriptor)
            val firstSize = it.statSize
            check(OsConstants.S_ISREG(firstStat.st_mode) && firstSize > 0 && firstSize == firstStat.st_size) {
                "Prepared artifact has no complete regular-file bytes"
            }
            val after = readMetadata(identity, artifact, pending)
            val lastStat = Os.fstat(it.fileDescriptor)
            val lastSize = it.statSize
            check(before == after && sameFileState(firstStat, lastStat) && firstSize == lastSize) {
                "Prepared artifact changed during inspection"
            }
            check(pending || before.reportedSize == firstSize) { "Published artifact size differs from its descriptor" }
            WebDavClipSnapshot(
                identity = artifact.uri,
                displayName = before.displayName,
                sizeBytes = firstSize,
                modifiedSeconds = before.modifiedSeconds ?: firstStat.st_mtim.tv_sec.also { seconds -> check(pending && seconds >= 0) },
                finalized = !pending,
                mimeType = before.mimeType,
            )
        }
    }

    private data class Identity(val uri: Uri, val id: Long)
    private data class Metadata(val displayName: String, val reportedSize: Long?, val modifiedSeconds: Long?, val pending: Long, val mimeType: String)

    private fun validateIdentity(artifact: PreparedCaptureArtifact): Identity {
        val name = artifact.displayName
        require(name.isNotBlank() && name != "." && name != "..") { "Prepared artifact name is invalid" }
        require(name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) { "Prepared artifact name is invalid" }
        require(Charsets.UTF_8.newEncoder().canEncode(name) && name.toByteArray(Charsets.UTF_8).size <= 255) { "Prepared artifact name is invalid" }
        require(artifact.uri.length <= 1024) { "Prepared artifact URI is invalid" }
        val parsed = try { URI(artifact.uri) } catch (_: Exception) { throw IllegalArgumentException("Prepared artifact URI is invalid") }
        require(parsed.scheme == "content" && parsed.rawAuthority == "media" && parsed.rawQuery == null && parsed.rawFragment == null) {
            "Prepared artifact URI is not a canonical MediaStore item"
        }
        val path = requireNotNull(parsed.path)
        require(parsed.rawPath == path && path.startsWith('/') && !path.endsWith('/')) { "Prepared artifact URI path is invalid" }
        val parts = path.substring(1).split('/')
        require(parts.size in 3..4 && parts.first().matches(Regex("[A-Za-z0-9_-]+"))) { "Prepared artifact URI path is invalid" }
        val id = requireNotNull(parts.last().toLongOrNull()) { "Prepared artifact ID is invalid" }
        require(id > 0 && id.toString() == parts.last()) { "Prepared artifact ID is not canonical" }
        val expectedCollection = when (artifact.role) {
            CaptureArtifactRole.VIDEO -> listOf("video", "media")
            CaptureArtifactRole.AUDIO -> listOf("audio", "media")
            CaptureArtifactRole.VIDEO_METADATA, CaptureArtifactRole.AUDIO_METADATA -> listOf("downloads")
        }
        require(parts.subList(1, parts.lastIndex) == expectedCollection) { "Prepared artifact role does not match its collection" }
        return Identity(artifact.uri.toUri(), id)
    }

    @Suppress("DEPRECATION") // Pending observations must include pending rows on API29/30 too.
    private fun readMetadata(identity: Identity, artifact: PreparedCaptureArtifact, pending: Boolean): Metadata {
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE)
        return resolver.query(MediaStore.setIncludePending(identity.uri), columns, null, null, null)?.use { cursor ->
            if (cursor.count != 1 || !cursor.moveToFirst()) throw FileNotFoundException("Prepared artifact row is missing or ambiguous")
            check(integer(cursor, 0) == identity.id) { "Prepared artifact ID changed" }
            val name = text(cursor, 1)
            check(name == artifact.displayName) { "Prepared artifact name changed" }
            val size = if (cursor.isNull(2)) null else integer(cursor, 2).also {
                check(it >= 0) { "Prepared artifact size metadata is invalid" }
            }
            val modified = if (pending && cursor.isNull(3)) null else integer(cursor, 3).also {
                check(it >= 0) { "Prepared artifact modification time is invalid" }
            }
            val state = integer(cursor, 4)
            check(state == if (pending) 1L else 0L) { "Prepared artifact publication state changed" }
            val mime = text(cursor, 5)
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val allowedMime = when (artifact.role) {
                CaptureArtifactRole.VIDEO -> if (extension == "mp4") setOf("video/mp4") else emptySet()
                CaptureArtifactRole.AUDIO -> when (extension) {
                    "wav" -> setOf("audio/wav", "audio/x-wav", "audio/vnd.wave")
                    "flac" -> setOf("audio/flac", "audio/x-flac")
                    else -> emptySet()
                }
                CaptureArtifactRole.VIDEO_METADATA, CaptureArtifactRole.AUDIO_METADATA ->
                    if (extension == "json") setOf("application/json") else emptySet()
            }
            check(mime.lowercase(Locale.ROOT) in allowedMime) { "Prepared artifact MIME does not match its role and name" }
            Metadata(name, size, modified, state, mime)
        } ?: throw FileNotFoundException("Prepared artifact row is unavailable")
    }

    private fun integer(cursor: Cursor, column: Int): Long {
        check(cursor.getType(column) == Cursor.FIELD_TYPE_INTEGER) { "Prepared artifact ${cursor.getColumnName(column)} has non-integer type ${cursor.getType(column)}" }
        val raw = requireNotNull(cursor.getString(column))
        val value = requireNotNull(raw.toLongOrNull()) { "Prepared artifact numeric metadata exceeds 64 bits" }
        check(raw == value.toString() && cursor.getLong(column) == value) { "Prepared artifact numeric metadata is not canonical" }
        return value
    }

    private fun text(cursor: Cursor, column: Int): String {
        check(cursor.getType(column) == Cursor.FIELD_TYPE_STRING) { "Prepared artifact text metadata is invalid" }
        return requireNotNull(cursor.getString(column))
    }

    private fun sameFileState(first: StructStat, last: StructStat): Boolean =
        first.st_dev == last.st_dev && first.st_ino == last.st_ino && first.st_mode == last.st_mode && first.st_nlink == last.st_nlink &&
            first.st_size == last.st_size && first.st_mtim.tv_sec == last.st_mtim.tv_sec && first.st_mtim.tv_nsec == last.st_mtim.tv_nsec &&
            first.st_ctim.tv_sec == last.st_ctim.tv_sec && first.st_ctim.tv_nsec == last.st_ctim.tv_nsec
}
