/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.storage.StorageManager
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.librestatic.opencinecam.export.CinemaDngExportRuntime
import com.librestatic.opencinecam.export.safFolderLabel
import com.librestatic.opencinecam.transfers.WebDavArtifactState
import java.io.File

/** The outcome of [RecordingDestinationStore.adopt]. */
internal sealed interface RecordingFolderAdoption {
    /** [label] is the drive's name as the system describes it, or the folder's, when known. */
    data class Adopted(val volumeId: String, val label: String?) : RecordingFolderAdoption
    data class Rejected(val reason: RecordingFolderRejection) : RecordingFolderAdoption
    /** A later [RecordingDestinationStore.adopt] or [RecordingDestinationStore.clear] ran meanwhile, and this one changed nothing. */
    data object Superseded : RecordingFolderAdoption
}

/**
 * PLAN-072: the recording destination and the drive disconnect policy, in their own preferences
 * (`recording_destination`) and never in camera presets. A USB folder is stored as its tree URI, its
 * volume UUID and an optional label; its persistable permission is held while it is configured. Trees the operator picked
 * on the same drive under another volume name (see [driveIdentityOf]) are kept as aliases, with their permissions, so the
 * drive works under either name without picking the folder again.
 * [probe] checks a picked tree (blocking: [adopt] must run off the main thread). [driveAttachHandler] enables or
 * disables the USB attach alias (see [syncDriveAttachHandler]).
 */
internal class RecordingDestinationStore(
    context: Context,
    private val probe: (Uri) -> DriveProbeResult = RecordingDriveProbe(context.applicationContext.contentResolver)::run,
    private val driveAttachHandler: (Boolean) -> Unit = { enabled -> setDriveAttachAliasEnabled(context.applicationContext, enabled) },
) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The destination the operator chose; [RecordingDestination.Phone] when none or unreadable. */
    fun configured(): RecordingDestination {
        val tree = preferences.getString(KEY_TREE, null) ?: return RecordingDestination.Phone
        val volume = preferences.getString(KEY_VOLUME, null) ?: return RecordingDestination.Phone
        return RecordingDestination.UsbFolder(tree.toUri(), volume)
    }

    /** The configured drive's label, when one was recorded. */
    fun label(): String? = preferences.getString(KEY_LABEL, null)

    fun policy(): DriveDisconnectPolicy =
        preferences.getString(KEY_POLICY, null)?.let { name -> DriveDisconnectPolicy.entries.firstOrNull { it.name == name } }
            ?: DriveDisconnectPolicy.DEFAULT

    fun setPolicy(policy: DriveDisconnectPolicy) = preferences.edit { putString(KEY_POLICY, policy.name) }

    /** Whether the app still holds read and write on the configured tree. */
    fun hasPermission(): Boolean = when (val configured = configured()) {
        RecordingDestination.Phone -> true
        is RecordingDestination.UsbFolder -> holdsReadWrite(configured.treeUri)
    }

    /**
     * What a take must use now, given the removable volumes mounted (see [RecordingVolumeMonitor.mounted]) and whether a USB
     * storage device attached recently (see [UsbDriveArrivalMonitor.arriving]). When the configured tree is not usable but an
     * alias is (its volume mounted, its permission held), the alias is promoted: it becomes the configured tree in the
     * preferences and the old one an alias, so [configured], [measure], [speed] and the rest see the mounted tree from then on.
     */
    fun current(mountedVolumeIds: Set<String>, driveArriving: Boolean = false): ResolvedRecordingDestination {
        val configured = configured() as? RecordingDestination.UsbFolder
            ?: return resolveRecordingDestination(RecordingDestination.Phone, mountedVolumeIds, true, driveArriving)
        val held = heldTrees()
        val usable = configured.treeUri in held && mountedVolumeIds.any { it.equals(configured.volumeId, ignoreCase = true) }
        // A tree promoteMountedAlias returns was held when it looked under the lock.
        val promoted = if (usable) null else promoteMountedAlias(mountedVolumeIds)
        val resolved = promoted ?: configured
        return resolveRecordingDestination(resolved, mountedVolumeIds, promoted != null || configured.treeUri in held, driveArriving)
    }

    /**
     * Swaps in the first held alias whose volume is mounted, keeping the configured tree as an alias while its permission is
     * held; aliases whose permission went are dropped quietly. Null when no alias is mounted.
     */
    private fun promoteMountedAlias(mountedVolumeIds: Set<String>): RecordingDestination.UsbFolder? = synchronized(ALIAS_LOCK) {
        // Re-read under the lock: another caller may have promoted, adopted or cleared meanwhile.
        val primary = configured() as? RecordingDestination.UsbFolder ?: return null
        val held = heldTrees()
        if (primary.treeUri in held && mountedVolumeIds.any { it.equals(primary.volumeId, ignoreCase = true) }) return primary
        val stored = aliases()
        if (stored.isEmpty()) return null
        val kept = stored.filterTo(LinkedHashSet()) { it.toUri() in held }
        val promoted = kept.firstOrNull { alias -> volumeIdOfTree(alias.toUri())?.let { volume -> mountedVolumeIds.any { it.equals(volume, ignoreCase = true) } } == true }
        val promotedVolume = promoted?.let { volumeIdOfTree(it.toUri()) }
        if (promoted == null || promotedVolume == null) {
            if (kept.size != stored.size) preferences.edit { putAliases(kept) }
            return null
        }
        val demoted = primary.treeUri.toString().takeIf { primary.treeUri in held }
        preferences.edit {
            putString(KEY_TREE, promoted)
            putString(KEY_VOLUME, promotedVolume)
            putAliases(kept - promoted + setOfNotNull(demoted))
        }
        Log.i(LOG_TAG, "The drive is mounted as $promotedVolume again: recording to the folder picked under that name")
        RecordingDestination.UsbFolder(promoted.toUri(), promotedVolume)
    }

    /**
     * Validates, keeps and probes a tree the picker returned, then makes it the destination. A rejected
     * tree gives its permission back; an adopted one replaces the previous tree, whose permission is
     * released unless the CinemaDNG export also uses it. A previous tree on the same drive under another volume name
     * ([sameDrive]) is kept as an alias instead; an alias under the new tree's volume name is replaced by it, and adopting
     * another drive releases every alias. When another [adopt] or a [clear] starts while this one probes, the later
     * choice stands: this tree's permission is released unless shared, or unless another adoption of the same tree is still
     * probing, and the result is [RecordingFolderAdoption.Superseded].
     */
    fun adopt(tree: Uri): RecordingFolderAdoption {
        val volume = when (val validation = validateRecordingTree(tree.authority, treeDocumentIdOf(tree))) {
            is TreeValidation.Rejected -> return RecordingFolderAdoption.Rejected(validation.reason)
            is TreeValidation.Ok -> validation.volumeId
        }
        val key = tree.toString()
        val choice = synchronized(ALIAS_LOCK) {
            pendingAdoptions.merge(key, 1, Int::plus)
            ++generation
        }
        var pending = true
        // Under ALIAS_LOCK: this adoption no longer counts as one still deciding on the tree.
        fun settle() {
            if (!pending) return
            pending = false
            pendingAdoptions.computeIfPresent(key) { _, count -> if (count > 1) count - 1 else null }
        }
        try {
            return adoptPending(tree, volume, choice, ::settle)
        } finally {
            synchronized(ALIAS_LOCK) { settle() }
        }
    }

    private fun adoptPending(tree: Uri, volume: String, choice: Long, settle: () -> Unit): RecordingFolderAdoption {
        try {
            resolver.takePersistableUriPermission(tree, READ_WRITE)
        } catch (failure: SecurityException) {
            Log.w(LOG_TAG, "The folder permission could not be kept", failure)
            return RecordingFolderAdoption.Rejected(RecordingFolderRejection.PERMISSION_DENIED)
        }
        val probed = try { probe(tree) } catch (failure: Exception) {
            // The probe itself failed (a descriptor that throws on close): the folder is not adopted, so its grant goes too.
            synchronized(ALIAS_LOCK) {
                settle()
                if (!usesTree(tree)) releaseUnlessShared(tree)
            }
            throw failure
        }
        probed.rejection()?.let { reason ->
            synchronized(ALIAS_LOCK) {
                settle()
                if (generation != choice) {
                    // A later choice was made meanwhile: this folder's refusal is not its answer.
                    if (!usesTree(tree)) releaseUnlessShared(tree)
                    return RecordingFolderAdoption.Superseded
                }
                releaseUnlessShared(tree)
            }
            return RecordingFolderAdoption.Rejected(reason)
        }
        val label = driveLabel(volume) ?: folderLabel(tree)
        synchronized(ALIAS_LOCK) {
            settle()
            if (generation != choice) {
                // The later choice may be this same folder, picked again: its permission then stays.
                if (!usesTree(tree)) releaseUnlessShared(tree)
                return RecordingFolderAdoption.Superseded
            }
            val previous = preferences.getString(KEY_TREE, null)
            val held = heldTrees()
            // The previous tree and the aliases still held; one whose permission went is dropped quietly.
            val candidates = (aliases() + setOfNotNull(previous)).filterTo(LinkedHashSet()) { it.toUri() in held } - tree.toString()
            val keptAliases = keptDriveAliases(volume, preferences.getString(KEY_VOLUME, null), candidates)
            preferences.edit {
                putString(KEY_TREE, tree.toString())
                putString(KEY_VOLUME, volume)
                if (label != null) putString(KEY_LABEL, label) else remove(KEY_LABEL)
                putAliases(keptAliases)
            }
            (candidates - keptAliases).forEach { releaseUnlessShared(it.toUri()) }
            syncDriveAttachHandler()
        }
        return RecordingFolderAdoption.Adopted(volume, label)
    }

    /**
     * The last measured write speed of drive [volume] (the configured one by default). Kept per volume across [clear]
     * and re-adoption: a drive's speed does not change when the operator switches back to the phone.
     */
    fun speed(volume: String? = (configured() as? RecordingDestination.UsbFolder)?.volumeId): DriveSpeed? =
        volume?.let { decodeDriveSpeed(preferences.getString(speedKey(it), null)) }

    fun saveSpeed(volume: String, speed: DriveSpeed) = preferences.edit { putString(speedKey(volume), speed.encode()) }

    /** Measures the configured drive (blocking, about six seconds) and keeps the result; null with none or on failure. */
    fun measure(): DriveSpeed? {
        val drive = configured() as? RecordingDestination.UsbFolder ?: return null
        val speed = measureDriveSpeed(resolver, drive.treeUri, driveFreeBytes(resolver, drive.treeUri)) ?: return null
        saveSpeed(drive.volumeId, speed)
        return speed
    }

    /** Back to the phone; the tree's permission and its aliases' go unless the CinemaDNG export also uses them. */
    fun clear() {
        synchronized(ALIAS_LOCK) {
            generation++
            val previous = setOfNotNull(preferences.getString(KEY_TREE, null)) + aliases()
            preferences.edit { remove(KEY_TREE); remove(KEY_VOLUME); remove(KEY_LABEL); remove(KEY_ALIASES) }
            val held = heldTrees()
            previous.filter { it.toUri() in held }.forEach { releaseUnlessShared(it.toUri()) }
            syncDriveAttachHandler()
        }
    }

    /** Whether [tree] is the configured recording tree or one of its aliases, whose permission the destination needs. */
    fun usesTree(tree: Uri): Boolean = tree.toString().let { it == preferences.getString(KEY_TREE, null) || it in aliases() }

    /**
     * Whether [tree]'s permission must stay held: the destination uses it ([usesTree]), an adoption is still probing it,
     * a journalled take waits on it, or a committed drive take waits in the WebDAV outbox to be uploaded through it.
     */
    fun needsTree(tree: Uri): Boolean = synchronized(ALIAS_LOCK) {
        usesTree(tree) || tree.toString() in pendingAdoptions || recoveryNeedsTree(tree)
    }

    /**
     * Runs [block] as one step against every grant this store takes or releases: for another owner of a tree's grant (the
     * CinemaDNG export) taking it and remembering it, so a recording adoption that settles meanwhile sees it remembered.
     */
    fun <T> withGrants(block: () -> T): T = synchronized(ALIAS_LOCK, block)

    /**
     * Releases [tree] for another owner of its grant (the CinemaDNG export leaving it) unless [needsTree]. The check and
     * the release are one step against [adopt], which takes its grant and registers as pending under the same lock. A
     * grant kept only for recovery or the outbox is left to [releaseDeferred].
     */
    fun releaseUnlessNeeded(tree: Uri) = synchronized(ALIAS_LOCK) {
        if (usesTree(tree) || tree.toString() in pendingAdoptions) return@synchronized
        // Kept only for recovery or the outbox: [releaseDeferred] gives it back once they are done with it.
        if (recoveryNeedsTree(tree)) return@synchronized deferRelease(tree)
        runCatching { resolver.releasePersistableUriPermission(tree, READ_WRITE) }
            .onFailure { Log.w(LOG_TAG, "The folder permission could not be released", it) }
    }

    /**
     * Releases the grants kept only because recovery or the WebDAV outbox still needed them when their owner let them go
     * ([releaseUnlessShared], [releaseUnlessNeeded]), now that nothing does. Each goes by the same rules: a tree configured
     * again, an alias, the CinemaDNG export's, one an adoption is probing, or one still needed by a journalled entry or an
     * upload (or whose journals or outbox cannot be read) keeps its grant. A tree whose grant is gone, or that another
     * owner holds now, leaves the list; one recovery still needs stays on it. Blocking (journal and outbox reads): call it
     * off the main thread, after a recovery pass or an upload. Cheap when nothing is deferred.
     */
    fun releaseDeferred() = synchronized(ALIAS_LOCK) {
        val deferred = deferredReleases()
        if (deferred.isEmpty()) return@synchronized
        val held = heldTrees()
        val remaining = deferred.filterTo(LinkedHashSet()) { tree ->
            tree.toUri() in held && releaseUnlessShared(tree.toUri()) == GrantRelease.KEPT_FOR_RECOVERY
        }
        if (remaining != deferred) preferences.edit { putDeferredReleases(remaining) }
    }

    /** Whether the app holds read and write on [tree], read from the persisted grants. */
    fun holdsGrant(tree: Uri): Boolean = holdsReadWrite(tree)

    /**
     * Makes the USB attach alias match the configured destination: a candidate for USB drives only while one is the
     * destination, so the operator can answer the system's "which app" question with OpenCineCam and Always. Off
     * otherwise, because as the only candidate the app would make Android ask on every attach.
     */
    fun syncDriveAttachHandler() = driveAttachHandler(configured() is RecordingDestination.UsbFolder)

    private fun holdsReadWrite(tree: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }

    /** Every tree the app holds read and write on, read once. */
    private fun heldTrees(): Set<Uri> =
        resolver.persistedUriPermissions.filter { it.isReadPermission && it.isWritePermission }.mapTo(HashSet()) { it.uri }

    /** The alias trees, as stored (a copy: the preferences' own set must not change). */
    private fun aliases(): Set<String> = preferences.getStringSet(KEY_ALIASES, null)?.toSet().orEmpty()

    private fun SharedPreferences.Editor.putAliases(aliases: Set<String>) {
        if (aliases.isEmpty()) remove(KEY_ALIASES) else putStringSet(KEY_ALIASES, aliases)
    }

    /** The trees whose release waits on recovery or the outbox, as stored (a copy). */
    private fun deferredReleases(): Set<String> = preferences.getStringSet(KEY_DEFERRED_RELEASES, null)?.toSet().orEmpty()

    private fun SharedPreferences.Editor.putDeferredReleases(trees: Set<String>) {
        if (trees.isEmpty()) remove(KEY_DEFERRED_RELEASES) else putStringSet(KEY_DEFERRED_RELEASES, trees)
    }

    /** Under [ALIAS_LOCK]: remembers [tree] for [releaseDeferred]. */
    private fun deferRelease(tree: Uri) {
        val deferred = deferredReleases()
        if (tree.toString() !in deferred) preferences.edit { putDeferredReleases(deferred + tree.toString()) }
    }

    private enum class GrantRelease { RELEASED, KEPT_SHARED, KEPT_FOR_RECOVERY }

    /**
     * Releases [tree] unless it is the configured recording tree, one of its aliases or the CinemaDNG export's, or recovery
     * still needs it: an interrupted take or still group journalled on it, or on the same folder of the same drive under
     * its other volume name ([sameDrive]), is deferred until its drive returns and is resolved with this grant. A drive
     * take still to upload from the WebDAV outbox keeps it too ([outboxDriveTrees]). A journal or outbox that cannot be
     * read keeps the grant. A grant kept only for recovery or the outbox is remembered, and [releaseDeferred] releases it
     * once they no longer need it. Under [ALIAS_LOCK].
     */
    private fun releaseUnlessShared(tree: Uri): GrantRelease {
        val export = context.getSharedPreferences(CINEMADNG_EXPORT_PREFS, Context.MODE_PRIVATE)
        // A folder the export left stays while an export may still write to it; the export releases it when it ends.
        val kept = setOfNotNull(preferences.getString(KEY_TREE, null), export.getString(CINEMADNG_EXPORT_TREE_KEY, null)) + aliases() +
            export.getStringSet(CINEMADNG_EXPORT_LEFT_TREES_KEY, null).orEmpty() +
            // An export queued or running reads its take, or writes, through these; it releases them when it ends.
            export.getStringSet(CINEMADNG_EXPORT_ACTIVE_TREES_KEY, null).orEmpty()
        // Another adoption of this tree is still probing: the grant is the one it took, and it decides.
        if (tree.toString() in kept || tree.toString() in pendingAdoptions) return GrantRelease.KEPT_SHARED
        if (recoveryNeedsTree(tree)) {
            deferRelease(tree)
            return GrantRelease.KEPT_FOR_RECOVERY
        }
        runCatching { resolver.releasePersistableUriPermission(tree, READ_WRITE) }
            .onFailure { Log.w(LOG_TAG, "The folder permission could not be released", it) }
        return GrantRelease.RELEASED
    }

    /**
     * Whether an interrupted take or still group is journalled on [tree], or a drive take on it waits in the WebDAV outbox
     * to be uploaded, on it or on the same folder of the same drive under its other volume name, so recovery or the upload
     * still needs the grant. True too when the journals or the outbox cannot be read.
     */
    private fun recoveryNeedsTree(tree: Uri): Boolean {
        // Lock-free reads (journalledDriveTrees, outboxDriveTrees), so this may run under ALIAS_LOCK without ordering
        // against the journals' or the outbox's locks.
        val journalled = try {
            RecordingCaptureRecovery.journalledTrees(context) + StillCaptureRecovery.journalledTrees(context)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "The recovery journals could not be read: the folder permission is kept", failure)
            return true
        }
        val uploading = try { outboxDriveTrees(context.getDatabasePath(WEBDAV_OUTBOX_DATABASE)) } catch (failure: Exception) {
            Log.w(LOG_TAG, "The WebDAV outbox could not be read: the folder permission is kept", failure)
            return true
        }
        val holds = { trees: Set<String> -> trees.any { it == tree.toString() || sameFolderOnDrive(it.toUri(), tree) } }
        return when {
            holds(journalled) -> true.also { Log.i(LOG_TAG, "The folder permission is kept for an interrupted take still to recover") }
            holds(uploading) -> true.also { Log.i(LOG_TAG, "The folder permission is kept for a take still to upload") }
            else -> false
        }
    }

    /** Whether trees [a] and [b] are the same folder on the same drive under its two volume names. */
    private fun sameFolderOnDrive(a: Uri, b: Uri): Boolean {
        val idA = treeDocumentIdOf(a) ?: return false
        val idB = treeDocumentIdOf(b) ?: return false
        val volumeA = volumeIdOfDocumentId(idA) ?: return false
        val volumeB = volumeIdOfDocumentId(idB) ?: return false
        return a.authority == b.authority && sameDrive(volumeA, volumeB) && idA.substringAfter(':') == idB.substringAfter(':')
    }

    private fun driveLabel(volume: String): String? = runCatching {
        context.getSystemService(StorageManager::class.java)?.storageVolumes
            ?.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.getDescription(context)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun folderLabel(tree: Uri): String? = safFolderLabel(resolver, tree)?.takeIf { it.isNotBlank() }

    internal companion object {
        const val PREFS = "recording_destination"
        const val KEY_TREE = "tree"
        const val KEY_VOLUME = "volume"
        const val KEY_LABEL = "label"
        const val KEY_POLICY = "disconnect_policy"
        const val KEY_SPEED_PREFIX = "drive_speed_"
        /** Other trees picked on the configured drive under another volume name (a string set; see [keptDriveAliases]). */
        const val KEY_ALIASES = "alias_trees"
        /** Trees whose grant was kept only for recovery or the outbox, released later (a string set; see [releaseDeferred]). */
        const val KEY_DEFERRED_RELEASES = "deferred_release_trees"
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private const val LOG_TAG = "RecordingDestination"
        /** Serialises changes to the tree and its aliases: [current] runs from several threads, each with its own store. */
        private val ALIAS_LOCK = Any()
        /** Bumped by every [adopt] and [clear] under [ALIAS_LOCK], so an adoption whose probe outlived a later choice yields. */
        private var generation = 0L
        /** How many [adopt] calls are still deciding on each tree, under [ALIAS_LOCK]: their grant is not released under them. */
        private val pendingAdoptions = HashMap<String, Int>()

        /** Keyed by [driveIdentityOf]: a drive vold mounts under a suffixed name keeps its measurement. */
        fun speedKey(volume: String) = KEY_SPEED_PREFIX + driveIdentityOf(volume)
    }
}

/** The WebDAV outbox's database, as [com.librestatic.opencinecam.transfers.WebDavSqliteOutbox] names it by default. */
internal const val WEBDAV_OUTBOX_DATABASE = "webdav-outbox.db"

/**
 * The picked folders (trees) that the WebDAV outbox [file] still reads drive takes through: the source of every artifact
 * not yet VERIFIED, named as [CinemaDngExportRuntime.sourceTree] names a drive take's folder. Phone sources (MediaStore)
 * give none. Takes no lock and never creates the outbox, as [journalledDriveTrees]: none reads as empty, and an outbox
 * that cannot be read fails, so the caller keeps every grant.
 */
internal fun outboxDriveTrees(file: File): Set<String> {
    if (!file.isFile) return emptySet()
    // Read-write only so SQLite can roll back a writer's hot journal; no collator table is created.
    return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        { throw IllegalStateException("WebDAV outbox is corrupt") }).use { db ->
        db.rawQuery("SELECT DISTINCT source_uri FROM artifacts WHERE state <> ?", arrayOf(WebDavArtifactState.VERIFIED.name)).use { c ->
            buildSet { while (c.moveToNext()) CinemaDngExportRuntime.sourceTree(c.getString(0))?.let(::add) }
        }
    }
}

/**
 * The aliases to keep when a tree on volume [newVolume] is adopted over a destination on [previousVolume], given
 * [candidates], the previous tree and aliases (permissions held, the new tree left out). Only trees on the same drive
 * under another volume name stay: one under [newVolume] is replaced by the new tree, and a different drive keeps none.
 */
internal fun keptDriveAliases(newVolume: String, previousVolume: String?, candidates: Set<String>): Set<String> {
    if (previousVolume == null || !sameDrive(previousVolume, newVolume)) return emptySet()
    return candidates.filterTo(LinkedHashSet()) { tree ->
        val volume = volumeIdOfTree(tree.toUri()) ?: return@filterTo false
        sameDrive(volume, newVolume) && !volume.equals(newVolume, ignoreCase = true)
    }
}

/**
 * The manifest's `.UsbDriveAttachAlias` activity. Its class is qualified by the namespace, not the application id, which
 * carries a suffix in the debug and benchmark builds.
 */
internal const val DRIVE_ATTACH_ALIAS = "com.librestatic.opencinecam.UsbDriveAttachAlias"

/**
 * Enables or disables the USB attach alias without killing the app. A no-op when it already matches; the manifest's
 * default state is disabled, so a phone-only install never writes a component setting.
 */
private fun setDriveAttachAliasEnabled(context: Context, enabled: Boolean) {
    runCatching {
        val component = ComponentName(context.packageName, DRIVE_ATTACH_ALIAS)
        val packageManager = context.packageManager
        if ((packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) == enabled) return
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }.onFailure { Log.w("RecordingDestination", "The USB attach handler could not be updated", it) }
}
