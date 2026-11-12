/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.librestatic.opencinecam.core.model.StorageBenchmarkOutcome
import com.librestatic.opencinecam.core.model.StorageBenchmarkRequest
import com.librestatic.opencinecam.core.model.StorageBenchmarkResult
import com.librestatic.opencinecam.core.model.StorageBenchmarkRunner

/** PLAN-072: how fast a recording drive wrote when it was last measured, from one-second windows. */
internal data class DriveSpeed(val medianBytesPerSecond: Long, val minBytesPerSecond: Long, val measuredAtMillis: Long)

/**
 * Headroom over what the settings need before a drive counts as fast enough: a take also writes its audio and
 * sidecars, and the file system's own metadata, and a drive's speed sags as it warms or fills.
 */
internal const val DRIVE_SPEED_HEADROOM = 1.25

/** The test file in the tree's root; a leftover from an interrupted measurement is deleted first. */
internal const val DRIVE_SPEED_FILE_NAME = ".occ-benchmark"

/** Six one-second windows: long enough to get past a drive's write cache burst, short enough to wait for. */
internal const val DRIVE_SPEED_WINDOWS = 6
private const val DRIVE_SPEED_CHUNK_BYTES = 1 shl 20

/** A fast drive writes a few GB in the measurement; under this much free space it is not measured at all. */
internal const val DRIVE_SPEED_MIN_FREE_BYTES = 4L * 1024 * 1024 * 1024

private const val ENCODING_VERSION = "v1"
private const val LOG_TAG = "RecordingDriveSpeed"

/** The speed a finished run measured; null when it did not finish its windows' writing (failed, cancelled). */
internal fun driveSpeedOf(result: StorageBenchmarkResult, nowMillis: Long): DriveSpeed? {
    if (result.outcome != StorageBenchmarkOutcome.QUALIFIED && result.outcome != StorageBenchmarkOutcome.NOT_QUALIFIED) return null
    val rates = result.windows.map { it.bytesPerSecond }.sorted()
    if (rates.isEmpty()) return null
    val middle = rates.size / 2
    val median = if (rates.size % 2 == 1) rates[middle] else (rates[middle - 1] + rates[middle]) / 2
    return DriveSpeed(median, rates.first(), nowMillis)
}

/** Whether the drive's typical rate leaves less than [DRIVE_SPEED_HEADROOM] over what the settings write. */
internal fun driveTooSlow(speed: DriveSpeed, requiredBytesPerSecond: Long): Boolean =
    speed.medianBytesPerSecond < requiredBytesPerSecond * DRIVE_SPEED_HEADROOM

/** Whether a measurement may run: free space unknown (the provider gives none) or at least [DRIVE_SPEED_MIN_FREE_BYTES]. */
internal fun driveSpeedTestFits(freeBytes: Long?): Boolean = freeBytes == null || freeBytes >= DRIVE_SPEED_MIN_FREE_BYTES

/** `v1;<median>;<min>;<measuredAt>`, for the destination preferences. */
internal fun DriveSpeed.encode(): String = "$ENCODING_VERSION;$medianBytesPerSecond;$minBytesPerSecond;$measuredAtMillis"

/** The inverse of [encode]; null for anything else. */
internal fun decodeDriveSpeed(encoded: String?): DriveSpeed? {
    val parts = encoded?.split(';') ?: return null
    if (parts.size != 4 || parts[0] != ENCODING_VERSION) return null
    val median = parts[1].toLongOrNull() ?: return null
    val min = parts[2].toLongOrNull() ?: return null
    val at = parts[3].toLongOrNull() ?: return null
    if (median < 0 || min < 0 || at < 0) return null
    return DriveSpeed(median, min, at)
}

/**
 * Writes [DRIVE_SPEED_FILE_NAME] in [tree]'s root for [DRIVE_SPEED_WINDOWS] seconds and deletes it. Blocking (about
 * six seconds); null when the drive has too little free space or anything fails. Never throws.
 */
internal fun measureDriveSpeed(
    resolver: ContentResolver,
    tree: Uri,
    freeBytes: Long?,
    nowMillis: () -> Long = System::currentTimeMillis,
): DriveSpeed? {
    if (!driveSpeedTestFits(freeBytes)) {
        Log.i(LOG_TAG, "Not measured: $freeBytes bytes free")
        return null
    }
    var created: Uri? = null
    val documents = SafDocuments(resolver, tree)
    return try {
        val root = documents.root
        documents.child(root, DRIVE_SPEED_FILE_NAME)?.let { leftover -> documents.delete(leftover.uri) }
        val file = documents.newFile(root, DRIVE_SPEED_FILE_NAME).uri.also { created = it }
        val request = StorageBenchmarkRequest(
            benchmarkId = "drive-speed",
            windowCount = DRIVE_SPEED_WINDOWS,
            windowDurationMillis = 1_000,
            requiredBytesPerSecond = 0,
            chunkSizeBytes = DRIVE_SPEED_CHUNK_BYTES,
        )
        val destination = ContentResolverBenchmarkDestination(resolver, "saf:${volumeIdOfTree(tree)?.let(::driveIdentityOf) ?: "drive"}", file)
        val result = StorageBenchmarkRunner().run(request, listOf(destination)).results.single()
        driveSpeedOf(result, nowMillis()).also { speed ->
            if (speed == null) Log.w(LOG_TAG, "The drive speed test did not finish: ${result.outcome} ${result.failure}")
        }
    } catch (failure: Exception) {
        Log.w(LOG_TAG, "The drive speed test failed", failure)
        null
    } finally {
        // The runner's cleanup deletes the file; this covers a failure before or around it.
        created?.let { uri -> runCatching { documents.delete(uri) }.onFailure { Log.w(LOG_TAG, "The test file could not be deleted", it) } }
    }
}
