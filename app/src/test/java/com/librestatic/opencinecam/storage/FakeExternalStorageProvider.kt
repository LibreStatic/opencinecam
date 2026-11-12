/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import org.robolectric.Robolectric
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

/**
 * PLAN-072 host tests: `com.android.externalstorage.documents` for one removable volume, backed by a
 * temp directory. Document ids are `<VOLUME>:<path>` (the root is `<VOLUME>:`), like the real provider.
 * Like `FileSystemProvider`, a created file keeps its name when its MIME is `application/octet-stream`
 * or matches its extension, otherwise the MIME's extension is appended, and a taken name becomes
 * `name (1)`; a missing document makes a query answer null and other calls throw
 * [FileNotFoundException]. Descriptors are real files, so "rw" descriptors seek. Setting [mounted]
 * false makes the volume behave as unplugged: every document is missing.
 *
 * Tests using it need `@Config(shadows = [ShadowDocumentsProviderQuery::class])`: Robolectric's resolver
 * calls the provider's pre-O `query`, which `DocumentsProvider` refuses.
 */
class FakeExternalStorageProvider : DocumentsProvider() {
    lateinit var directory: File
    var volume: String = VOLUME
    var mounted = true
    /** When set, the drive answers this many more document queries and then goes away (it is unplugged mid-read). */
    var queriesBeforeUnplug: Int? = null
    var availableBytes: Long = 64L shl 30
    /** When set, every document query throws it (a grant revoked under the reader, say). */
    var queryFailure: RuntimeException? = null
    /** Names of documents whose own query finds nothing although their folder still lists them (a provider that gave no answer). */
    var unanswered: Set<String> = emptySet()
    /** Runs before a document is created, e.g. to have another creator take the name first. */
    var beforeCreate: (() -> Unit)? = null
    /** Runs after a document is deleted. */
    var afterDelete: (() -> Unit)? = null

    override fun onCreate(): Boolean = true

    /** The tree URI of [path] in this volume ("" for the volume root). */
    fun tree(path: String = ""): Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "$volume:$path")

    /** The file behind [path] in this volume, to inspect or plant content. */
    fun file(path: String): File = if (path.isEmpty()) directory else File(directory, path)

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        if (!mounted) return@apply
        newRow().add(Root.COLUMN_ROOT_ID, volume).add(Root.COLUMN_DOCUMENT_ID, "$volume:")
            .add(Root.COLUMN_TITLE, "Drive").add(Root.COLUMN_AVAILABLE_BYTES, availableBytes)
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        queryFailure?.let { throw it }
        queriesBeforeUnplug?.let { left -> if (left <= 0) mounted = false else queriesBeforeUnplug = left - 1 }
        if (documentId.substringAfterLast('/').substringAfterLast(':') in unanswered) throw FileNotFoundException("No answer for $documentId")
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocument(this, documentId, existing(documentId)) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        queryFailure?.let { throw it }
        queriesBeforeUnplug?.let { left -> if (left <= 0) mounted = false else queriesBeforeUnplug = left - 1 }
        val parent = existing(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("$parentDocumentId is not a folder")
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            parent.listFiles().orEmpty().sortedBy { it.name }.forEach { addDocument(this, childId(parentDocumentId, it.name), it) }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        beforeCreate?.let { beforeCreate = null; it() }
        val parent = existing(parentDocumentId)
        require(parent.isDirectory) { "Parent document isn't a directory" }
        val (base, extension) = when {
            mimeType == Document.MIME_TYPE_DIR || mimeType == OCTET_STREAM -> displayName to null
            else -> {
                val dot = displayName.lastIndexOf('.')
                val requested = if (dot >= 0) displayName.substring(dot + 1) else null
                val fromMime = EXTENSIONS.entries.firstOrNull { it.value == mimeType }?.key
                if (requested != null && (EXTENSIONS[requested.lowercase()] == mimeType || requested == fromMime)) {
                    displayName.substring(0, dot) to requested
                } else displayName to fromMime
            }
        }
        fun named(n: Int) = (if (n == 0) base else "$base ($n)") + (extension?.let { ".$it" } ?: "")
        var n = 0
        while (File(parent, named(n)).exists()) if (++n > 32) throw FileNotFoundException("Failed to create unique file")
        val created = File(parent, named(n))
        check(if (mimeType == Document.MIME_TYPE_DIR) created.mkdir() else created.createNewFile()) { "Failed to create $created" }
        return childId(parentDocumentId, created.name)
    }

    override fun deleteDocument(documentId: String) {
        val file = existing(documentId)
        check(file != directory) { "The root cannot be deleted" }
        check(file.deleteRecursively()) { "Failed to delete $file" }
        afterDelete?.invoke()
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        if (parentDocumentId.endsWith(':')) documentId.startsWith(parentDocumentId)
        else documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileOf(documentId)
        if (!mounted || (!file.exists() && !mode.contains('w'))) throw FileNotFoundException("Missing file for $documentId")
        if (file.isDirectory) throw FileNotFoundException("$documentId is a folder")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    private fun addDocument(cursor: MatrixCursor, id: String, file: File) {
        val row = cursor.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, id)
        row.add(Document.COLUMN_DISPLAY_NAME, if (file == directory) "Drive" else file.name)
        row.add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else typeForName(file.name))
        row.add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE or
            if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
    }

    private fun existing(documentId: String): File {
        val file = fileOf(documentId)
        if (!mounted || !file.exists()) throw FileNotFoundException("Missing file for $documentId at $file")
        return file
    }

    private fun fileOf(documentId: String): File {
        val colon = documentId.indexOf(':')
        if (colon <= 0 || !documentId.substring(0, colon).equals(volume, ignoreCase = true)) throw FileNotFoundException("No root for $documentId")
        val path = documentId.substring(colon + 1)
        require(path.split('/').none { it == ".." }) { "Path escapes the volume" }
        return if (path.isEmpty()) directory else File(directory, path)
    }

    private fun childId(parentId: String, name: String) = if (parentId.endsWith(':')) parentId + name else "$parentId/$name"

    companion object {
        const val AUTHORITY = EXTERNAL_STORAGE_AUTHORITY
        const val VOLUME = "FFF1-7571"
        const val OCTET_STREAM = "application/octet-stream"
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private val EXTENSIONS = mapOf("mp4" to "video/mp4", "json" to "application/json", "wav" to "audio/x-wav",
            "flac" to "audio/flac", "csv" to "text/csv", "txt" to "text/plain", "dng" to "image/x-adobe-dng")
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_FLAGS)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

        fun typeForName(name: String): String =
            name.substringAfterLast('.', "").lowercase().let { EXTENSIONS[it] } ?: OCTET_STREAM

        /** Registers the provider over [directory] (created if missing) and returns it. */
        fun install(directory: File, volume: String = VOLUME): FakeExternalStorageProvider {
            check(directory.isDirectory || directory.mkdirs())
            val info = ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            }
            return Robolectric.buildContentProvider(FakeExternalStorageProvider::class.java).create(info).get().also {
                it.directory = directory
                it.volume = volume
            }
        }

        /** Takes the persistable read and write grant a picked tree comes with. */
        fun grant(context: Context, tree: Uri) = context.contentResolver.takePersistableUriPermission(tree, READ_WRITE)
    }
}

/**
 * The platform resolver turns a query into the `Bundle` form `DocumentsProvider` serves; Robolectric's
 * calls the pre-O form, which `DocumentsProvider` refuses. This forwards it the way the platform does.
 */
@Implements(DocumentsProvider::class)
class ShadowDocumentsProviderQuery {
    @RealObject private lateinit var real: DocumentsProvider

    @Implementation
    protected fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? =
        real.query(uri, projection, null as Bundle?, null)
}
