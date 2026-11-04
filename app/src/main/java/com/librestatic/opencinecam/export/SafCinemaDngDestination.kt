/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.WritableByteChannel

/**
 * A folder the operator picked with `ACTION_OPEN_DOCUMENT_TREE` (a USB SSD over OTG, an SD card,
 * any folder), writing `<take>/` inside it through [DocumentsContract]. The tree permission is
 * persisted by the picker's caller. Files are visible as they are written (SAF has no pending
 * state); discarding deletes the take folder, which the provider removes with its contents.
 *
 * Free space comes from the provider's root (`COLUMN_AVAILABLE_BYTES`), matched by the part of the
 * tree's document id before the `:` as the external storage provider builds it. Other providers may
 * not report it; the export then warns and goes on.
 */
internal class SafCinemaDngDestination(context: Context, private val tree: Uri) : CinemaDngDestination {
    private val resolver = context.applicationContext.contentResolver
    private val parent: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    override fun freeBytes(): Long? = safFreeBytes(resolver, tree)

    override fun open(takeName: String): CinemaDngTarget {
        val existing = childNames(parent)
        val folder = CinemaDngExporter.folderName(takeName) { it in existing }
        val created = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, folder)
            ?: throw IOException("the folder $folder could not be created")
        val name = displayName(created)
        if (name != folder) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw IOException("the provider named the folder \"$name\" instead of $folder")
        }
        return Target(created, "${safFolderLabel(resolver, tree) ?: tree.lastPathSegment.orEmpty()}/$folder")
    }

    private fun childNames(folder: Uri): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
        return resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            buildSet { while (it.moveToNext()) it.getString(0)?.let(::add) }
        } ?: emptySet()
    }

    private fun displayName(document: Uri): String? =
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private inner class Target(private val folder: Uri, override val location: String) : CinemaDngTarget {
        override fun writeNoMedia(): Boolean = try {
            write(CinemaDngExporter.NOMEDIA, CinemaDngExporter.NOMEDIA_MIME_TYPE) { }
            true
        } catch (failure: Exception) {
            Log.i(LOG_TAG, "The provider refused a .nomedia in $location", failure)
            false
        }

        override fun write(name: String, mimeType: String, body: (WritableByteChannel) -> Unit) {
            val document = DocumentsContract.createDocument(resolver, folder, mimeType, name)
                ?: throw IOException("$name could not be created")
            try {
                // A provider may add an extension or a " (1)", which would break the frame sequence.
                val actual = displayName(document)
                if (actual != name) throw IOException("the provider named $name \"$actual\"")
                val descriptor = resolver.openFileDescriptor(document, "w") ?: throw IOException("$name could not be opened")
                descriptor.use { FileOutputStream(it.fileDescriptor).channel.use(body) }
            } catch (failure: Throwable) {
                try { DocumentsContract.deleteDocument(resolver, document) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        override fun publish() = Unit

        override fun discard(): Boolean = try {
            DocumentsContract.deleteDocument(resolver, folder)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "Deleting the partial CinemaDNG folder failed", failure)
            false
        }
    }

    private companion object {
        const val LOG_TAG = "CinemaDngExport"
    }
}

/** The free bytes of the root holding [tree], when its provider reports them. */
internal fun safFreeBytes(resolver: ContentResolver, tree: Uri): Long? = runCatching {
    val authority = requireNotNull(tree.authority)
    val rootId = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
    resolver.query(DocumentsContract.buildRootsUri(authority),
        arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES), null, null, null)?.use { cursor ->
        var free: Long? = null
        while (cursor.moveToNext()) {
            if (cursor.getString(0) == rootId && !cursor.isNull(1)) { free = cursor.getLong(1); break }
        }
        free
    }
}.getOrNull()

/** The picked folder's name, for the dialog and the "Exported to" notice. */
internal fun safFolderLabel(resolver: ContentResolver, tree: Uri): String? = runCatching {
    val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull()
