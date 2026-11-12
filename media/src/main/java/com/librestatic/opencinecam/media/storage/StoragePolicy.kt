/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.storage

import com.librestatic.opencinecam.core.model.Knowledge

data class StorageReserve(
    val projectedBytes: Long,
    val requiredBytes: Long,
) {
    init {
        require(projectedBytes >= 0) { "projected bytes must not be negative" }
        require(requiredBytes >= 0) { "required bytes must not be negative" }
    }
}

/** The most free space a running take is stopped at, whatever its data rate. */
const val STORAGE_STOP_HEADROOM_CAP_BYTES = 512L shl 20

/**
 * Free space below which a running take is stopped so its file can still be finalized: a minute
 * of its projected data, capped at [STORAGE_STOP_HEADROOM_CAP_BYTES]. Without the cap a high-speed
 * take (up to 200 Mbps) would stop with 1.5 GB still free and need 3 GB to start, though it lasts seconds.
 */
fun storageStopHeadroom(projectedBytesPerMinute: Long): Long {
    require(projectedBytesPerMinute >= 0) { "projected bytes must not be negative" }
    return minOf(projectedBytesPerMinute, STORAGE_STOP_HEADROOM_CAP_BYTES)
}

/**
 * What a take needs free to start: 1 GiB, or twice its stop headroom if that is more, so it can
 * record at least as much as the headroom. A take bounded to [boundedTakeBytes] (a capture-time
 * limit) needs no more than the headroom plus that take with a quarter to spare, so a limit can only
 * lower the requirement and the take ends above the stop headroom.
 */
fun calculateStorageReserve(projectedBytes: Long, boundedTakeBytes: Long? = null): StorageReserve {
    val unbounded = maxOf(1L shl 30, storageStopHeadroom(projectedBytes) * 2)
    if (boundedTakeBytes == null) return StorageReserve(projectedBytes, unbounded)
    require(boundedTakeBytes >= 0) { "bounded take bytes must not be negative" }
    val withMargin = if (boundedTakeBytes > Long.MAX_VALUE / 5) Long.MAX_VALUE / 4 else boundedTakeBytes * 5 / 4
    return StorageReserve(projectedBytes, minOf(unbounded, storageStopHeadroom(projectedBytes) + withMargin))
}

sealed interface StorageSpaceCheck {
    data class Sufficient(val availableBytes: Long, val reserve: StorageReserve) : StorageSpaceCheck
    data class Insufficient(val availableBytes: Long, val reserve: StorageReserve) : StorageSpaceCheck
    data object Unknown : StorageSpaceCheck
}

fun checkStorageSpace(availableBytes: Knowledge<Long>, reserve: StorageReserve): StorageSpaceCheck = when (availableBytes) {
    Knowledge.Unknown -> StorageSpaceCheck.Unknown
    is Knowledge.Unsupported -> StorageSpaceCheck.Unknown
    is Knowledge.Known -> if (availableBytes.value >= reserve.requiredBytes) {
        StorageSpaceCheck.Sufficient(availableBytes.value, reserve)
    } else {
        StorageSpaceCheck.Insufficient(availableBytes.value, reserve)
    }
}

enum class ClipRecoveryDecision { PUBLISH_COMPLETE, PUBLISH_INCOMPLETE, DELETE }

fun decideClipRecovery(muxerFinalized: Boolean, bytesWritten: Long): ClipRecoveryDecision = when {
    bytesWritten <= 0L -> ClipRecoveryDecision.DELETE
    muxerFinalized -> ClipRecoveryDecision.PUBLISH_COMPLETE
    else -> ClipRecoveryDecision.PUBLISH_INCOMPLETE
}
