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

fun calculateStorageReserve(projectedBytes: Long): StorageReserve {
    require(projectedBytes >= 0) { "projected bytes must not be negative" }
    val twoMinutes = projectedBytes * 2
    return StorageReserve(projectedBytes, maxOf(1L shl 30, twoMinutes))
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
