/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import com.librestatic.opencinecam.transfers.WebDavSqliteOutbox
import com.librestatic.opencinecam.transfers.WebDavTransferRuntime

/** Called under idle media ownership after rename preflight. No database is created for a
 * camera that never enrolled transfers; remote names, hashes and evidence are never rewritten. */
internal object MediaRenameOutboxGuard {
    fun prepare(context: Context, uris: Set<String>) {
        require(uris.isNotEmpty() && uris.size <= 36 && uris.all { mediaDeleteIdentity(it) != null })
        val database = context.getDatabasePath("webdav-outbox.db")
        if (!database.exists()) {
            check(listOf("-wal", "-shm", "-journal").none { java.io.File(database.path + it).exists() }) {
                "Outbox companion files exist without the database"
            }
            return
        }
        check(database.isFile && !java.nio.file.Files.isSymbolicLink(database.toPath())) { "Uncertain outbox identity" }
        val changed = WebDavSqliteOutbox(context).use { it.holdSourcesForLocalRename(uris) }
        WebDavTransferRuntime.get(context).reflectLocalMutation(changed)
    }
}
