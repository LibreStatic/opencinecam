/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.net.Uri

/**
 * PLAN-072: where takes are written. [Phone] is the app's usual MediaStore output; [UsbFolder] is a
 * folder on a USB drive the operator picked through SAF, written through `DocumentsContract`.
 * Kept out of camera presets, which never hold paths.
 */
internal sealed interface RecordingDestination {
    data object Phone : RecordingDestination

    /** [volumeId] is the drive's volume UUID in upper case, the tree document id's part before `:`. */
    data class UsbFolder(val treeUri: Uri, val volumeId: String) : RecordingDestination
}

/** What a take does when its drive leaves mid-take. */
internal enum class DriveDisconnectPolicy {
    STOP_AND_SAVE,
    CONTINUE_ON_PHONE,
    ;

    companion object {
        val DEFAULT = STOP_AND_SAVE
    }
}

/**
 * Why a configured USB folder cannot be used right now. Public: [com.librestatic.opencinecam.RecordingDestinationStatus] carries it.
 * [DRIVE_STARTING] is an absent drive while a USB mass-storage device attached recently (see [DriveArrivalTracker]): a disk
 * spinning up before Android mounts its volume. [DRIVE_RENAMED] is the drive mounted under another volume name with the same
 * [driveIdentityOf] (see there): the stored tree no longer matches it, and the operator must pick the folder again. Takes go
 * to the phone either way; only the wording differs.
 */
enum class DestinationFallback { DRIVE_NOT_CONNECTED, DRIVE_STARTING, DRIVE_RENAMED, PERMISSION_LOST }

/**
 * The drive behind volume id [volumeId], ignoring the name vold gave it: upper case, with one trailing `-NN` (a dash and
 * exactly two decimal digits) removed. On the Razr, a mount cut short (a USB hub dropping while vold mounts) leaves vold a
 * stale record of the volume, and from then on it mounts the same drive under a "stable name" with a suffix: `FFF1-7571`
 * becomes `FFF1-7571-01` in `StorageVolume.uuid`, the mount path and the provider's root and document ids. Per-drive
 * records (its speed, its RAW qualification) are keyed by this, so they survive the rename; anything that addresses the
 * mounted volume keeps the actual id. FAT and exFAT serials (`XXXX-XXXX`), ext4 UUIDs and NTFS ids never end that way.
 */
internal fun driveIdentityOf(volumeId: String): String = volumeId.uppercase().replace(STABLE_NAME_SUFFIX, "")

/** Whether volume ids [a] and [b] name the same drive ([driveIdentityOf]), whatever suffix vold gave either. */
internal fun sameDrive(a: String, b: String): Boolean = driveIdentityOf(a) == driveIdentityOf(b)

private val STABLE_NAME_SUFFIX = Regex("-[0-9]{2}$")

/**
 * [destination] is what a take must use now ([RecordingDestination.Phone] when falling back),
 * [configured] what the operator chose, [fallback] why they differ (null when they do not). [volumeEvents] is the count of
 * volume events emitted when the mounted volumes it was resolved from were read ([RecordingVolumeMonitor.snapshot]).
 */
internal data class ResolvedRecordingDestination(
    val destination: RecordingDestination,
    val configured: RecordingDestination,
    val fallback: DestinationFallback?,
    val volumeEvents: Long = 0,
)

/**
 * The destination a take uses given the drives mounted now ([mountedVolumeIds], matched ignoring
 * case) and whether the app still holds read and write on the tree. A lost permission wins over an
 * absent drive, since plugging the drive back in does not fix it. An absent drive is [DestinationFallback.DRIVE_RENAMED]
 * when a mounted volume is the same drive under another name ([sameDrive]), else [DestinationFallback.DRIVE_STARTING] while
 * [driveArriving] (a USB storage device attached recently), otherwise [DestinationFallback.DRIVE_NOT_CONNECTED].
 */
internal fun resolveRecordingDestination(
    configured: RecordingDestination,
    mountedVolumeIds: Set<String>,
    hasPermission: Boolean,
    driveArriving: Boolean = false,
): ResolvedRecordingDestination = when (configured) {
    RecordingDestination.Phone -> ResolvedRecordingDestination(configured, configured, null)
    is RecordingDestination.UsbFolder -> {
        val fallback = when {
            !hasPermission -> DestinationFallback.PERMISSION_LOST
            mountedVolumeIds.none { it.equals(configured.volumeId, ignoreCase = true) } -> when {
                mountedVolumeIds.any { sameDrive(it, configured.volumeId) } -> DestinationFallback.DRIVE_RENAMED
                driveArriving -> DestinationFallback.DRIVE_STARTING
                else -> DestinationFallback.DRIVE_NOT_CONNECTED
            }
            else -> null
        }
        ResolvedRecordingDestination(if (fallback == null) configured else RecordingDestination.Phone, configured, fallback)
    }
}

/** Why a picked folder cannot become the recording destination. */
internal enum class RecordingFolderRejection {
    /** Not a folder of the external storage provider, or its id has no volume. */
    NOT_A_DRIVE,
    /** A folder in the phone's own storage. */
    PHONE_STORAGE,
    /** The provider refused a persistable permission. */
    PERMISSION_DENIED,
    /** The probe file could not be created, opened, written or read back. */
    NOT_WRITABLE,
    /** The file system cannot hold a file over 4 GiB (FAT32): the drive needs exFAT. */
    FILE_SIZE_LIMIT,
    /** The drive has no room for the probe's 4 GiB file. */
    NOT_ENOUGH_SPACE,
}

internal sealed interface TreeValidation {
    data class Ok(val volumeId: String) : TreeValidation
    data class Rejected(val reason: RecordingFolderRejection) : TreeValidation
}

/** The authority of the external storage provider, the only one whose trees can hold takes. */
internal const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * Checks a picked tree before anything touches it: it must come from the external storage provider
 * and name a removable volume, not `primary` (internal shared storage) or `home` (its Documents).
 */
internal fun validateRecordingTree(authority: String?, treeDocumentId: String?): TreeValidation {
    if (authority != EXTERNAL_STORAGE_AUTHORITY) return TreeValidation.Rejected(RecordingFolderRejection.NOT_A_DRIVE)
    val volume = volumeIdOfDocumentId(treeDocumentId) ?: return TreeValidation.Rejected(RecordingFolderRejection.NOT_A_DRIVE)
    if (volume.equals("primary", ignoreCase = true) || volume.equals("home", ignoreCase = true)) {
        return TreeValidation.Rejected(RecordingFolderRejection.PHONE_STORAGE)
    }
    return TreeValidation.Ok(volume)
}
