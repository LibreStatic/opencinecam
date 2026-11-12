/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * One document in a folder of a recording tree. [mime] is what the provider reports, derived from the name;
 * [modifiedMillis] its last change in milliseconds, 0 when the provider reports none.
 */
internal data class SafDocument(val uri: Uri, val name: String, val mime: String, val size: Long, val modifiedMillis: Long = 0L)

/**
 * PLAN-072: documents in a recording tree on `com.android.externalstorage.documents`, the only provider
 * [validateRecordingTree] accepts. Its document ids are `<volume>:<path>`, so a child's id is built from
 * its parent's instead of listing the parent. Files are always created as `application/octet-stream`: the
 * provider then keeps the name exactly (any other MIME whose extension differs gets that extension
 * appended, `x.gcsv` as `text/csv` becomes `x.gcsv.csv`) and reports the MIME of the extension.
 * Every call blocks on the provider.
 */
internal class SafDocuments(val resolver: ContentResolver, val tree: Uri) {
    /** The folder the tree grants. */
    val root: Uri get() = treeDocumentUri(tree)

    /** The document [name] directly inside [parent], or null when there is none. */
    fun child(parent: Uri, name: String): SafDocument? {
        require(isPlainName(name)) { "Not a plain document name: $name" }
        return describe(DocumentsContract.buildDocumentUriUsingTree(tree, childDocumentId(DocumentsContract.getDocumentId(parent), name)))
    }

    /**
     * The folder [name] inside [parent], created when missing. A file of that name is an error. When another
     * creator made [name] first, the provider names ours `name (1)`: it is deleted again (it is still empty)
     * and the other one is returned.
     */
    fun directory(parent: Uri, name: String): Uri {
        child(parent, name)?.let { existing ->
            check(existing.mime == Document.MIME_TYPE_DIR) { "$name exists and is not a folder" }
            return existing.uri
        }
        val uri = requireNotNull(DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name)) {
            "The drive did not create the folder $name"
        }
        val created = describe(uri)
        if (created != null && created.name != name) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            val existing = checkNotNull(child(parent, name)) { "The drive created ${created.name} instead of $name" }
            check(existing.mime == Document.MIME_TYPE_DIR) { "$name exists and is not a folder" }
            return existing.uri
        }
        return uri
    }

    /** A new folder inside [parent]; the provider may name it `name (1)` when [name] is taken. */
    fun newDirectory(parent: Uri, name: String): SafDocument {
        require(isPlainName(name)) { "Not a plain document name: $name" }
        val uri = requireNotNull(DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name)) {
            "The drive did not create the folder $name"
        }
        return requireNotNull(describe(uri)) { "The new folder $name disappeared" }
    }

    /**
     * A new empty file named exactly [name] inside [parent]. A provider that renames it (a name already
     * taken) has the document deleted again and the call fails, so callers can rely on the name.
     */
    fun newFile(parent: Uri, name: String): SafDocument {
        require(isPlainName(name)) { "Not a plain document name: $name" }
        val uri = requireNotNull(DocumentsContract.createDocument(resolver, parent, OCTET_STREAM, name)) {
            "The drive did not create $name"
        }
        val created = describe(uri)
        if (created == null || created.name != name) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            error("The drive created ${created?.name ?: "nothing"} instead of $name")
        }
        return created
    }

    /** The documents directly inside [parent]. */
    fun children(parent: Uri): List<SafDocument> {
        val parentId = DocumentsContract.getDocumentId(parent)
        val listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        return requireNotNull(resolver.query(listing, COLUMNS, null, null, null)) { "The drive did not list a folder" }.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(SafDocument(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                        cursor.getString(1), cursor.getString(2) ?: OCTET_STREAM, if (cursor.isNull(3)) 0L else cursor.getLong(3), modified(cursor)))
                }
            }
        }
    }

    /** The document at [uri], or null when it does not exist. */
    fun describe(uri: Uri): SafDocument? = try {
        resolver.query(uri, COLUMNS, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else SafDocument(uri, cursor.getString(1), cursor.getString(2) ?: OCTET_STREAM, if (cursor.isNull(3)) 0L else cursor.getLong(3), modified(cursor))
        }
    } catch (_: java.io.FileNotFoundException) {
        null
    } catch (_: IllegalArgumentException) {
        // ExternalStorageProvider reports a missing document id this way on some releases.
        null
    }

    /** Deletes [uri]; true when it is gone afterwards, including when it was already gone. */
    fun delete(uri: Uri): Boolean {
        if (describe(uri) == null) return true
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        return describe(uri) == null
    }

    internal companion object {
        const val OCTET_STREAM = "application/octet-stream"
        private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED)

        private fun modified(cursor: android.database.Cursor): Long = if (cursor.isNull(4)) 0L else cursor.getLong(4).coerceAtLeast(0L)

        /** `FFF1-7571:` (the volume root) + `OpenCineCam` → `FFF1-7571:OpenCineCam`; otherwise joined with `/`. */
        fun childDocumentId(parentId: String, name: String): String =
            if (parentId.endsWith(':') || parentId.endsWith('/')) parentId + name else "$parentId/$name"

        fun isPlainName(name: String): Boolean = name.isNotBlank() && '/' !in name && name != "." && name != ".." && name.length <= 200
    }
}
