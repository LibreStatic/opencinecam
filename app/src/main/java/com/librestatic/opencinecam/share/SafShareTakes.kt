/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.librestatic.opencinecam.storage.DriveFileRole
import com.librestatic.opencinecam.storage.RecordingDestination
import com.librestatic.opencinecam.storage.RecordingDestinationStore
import com.librestatic.opencinecam.storage.SafDocuments
import com.librestatic.opencinecam.storage.SafRecordingRows
import com.librestatic.opencinecam.storage.SafStillRows
import com.librestatic.opencinecam.storage.driveFileRole
import java.util.concurrent.CancellationException

/**
 * The RAW takes on the recording drive (PLAN-072), as the gallery lists them: the first `.ocraw` (by name) of each
 * take folder `<tree>/OpenCineCam/<stem>/` that holds no in-progress marker. Only while a drive is the configured
 * destination, its tree grant is held ([readable]) and its root answers: an unplugged drive contributes nothing, and
 * a drive that fails mid-listing is logged and contributes nothing rather than failing the phone's takes. Each read
 * opens its own read-only descriptor through the provider.
 */
internal class SafShareTakes(
    private val resolver: ContentResolver,
    private val destination: () -> RecordingDestination,
    private val readable: (Uri) -> Boolean,
) : ShareTakeSource {
    constructor(context: Context) : this(context.applicationContext.contentResolver,
        { RecordingDestinationStore(context.applicationContext).configured() },
        { tree -> context.applicationContext.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission } })

    override fun takes(): List<ShareTake> {
        val drive = destination() as? RecordingDestination.UsbFolder ?: return emptyList()
        val tree = drive.treeUri.takeIf(readable) ?: return emptyList()
        return try {
            val docs = SafDocuments(resolver, tree)
            if (docs.describe(docs.root) == null) return emptyList()
            val base = docs.child(docs.root, SafRecordingRows.BASE_FOLDER)?.takeIf { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
                ?: return emptyList()
            buildList {
                for (folder in docs.children(base.uri)) {
                    if (size >= MAX_TAKES) break
                    if (folder.mime != DocumentsContract.Document.MIME_TYPE_DIR || folder.name.startsWith('.') ||
                        folder.name == SafStillRows.STILLS_FOLDER) continue
                    val files = docs.children(folder.uri)
                    if (files.any { it.name == SafRecordingRows.MARKER }) continue
                    val raw = files.filter { it.mime != DocumentsContract.Document.MIME_TYPE_DIR && it.size >= 0 &&
                        driveFileRole(it.name, recording = true) == DriveFileRole.RAW }.minByOrNull { it.name } ?: continue
                    add(ShareTake(raw.uri.toString(), raw.name, raw.size, raw.modifiedMillis) { openShareFile(resolver, raw.uri) })
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Log.w(TAG, "The recording drive could not be listed for the network drive", failure)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "SafShareTakes"
        const val MAX_TAKES = 1000
    }
}

/**
 * The takes of every [sources], newest first by [ShareTake.modifiedMs], each key once. [ShareTree] keeps the newest of
 * two takes with one stem, so a phone take and a drive take never shadow each other by order of the sources.
 */
internal class CompositeShareTakes(private val sources: List<ShareTakeSource>) : ShareTakeSource {
    override fun takes(): List<ShareTake> =
        sources.flatMap { it.takes() }.sortedByDescending { it.modifiedMs }.distinctBy { it.key }
}
