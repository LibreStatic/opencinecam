/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.storage

import com.librestatic.opencinecam.core.model.Knowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePolicyTest {
    @Test
    fun reserveUsesGreaterOfOneGiBAndTwoMinutesProjection() {
        assertEquals(1L shl 30, calculateStorageReserve(1).requiredBytes)
        assertEquals(1L shl 30, calculateStorageReserve(2_000).requiredBytes)
        assertEquals(1_200_000_000L, calculateStorageReserve(600_000_000L).requiredBytes)
    }

    @Test
    fun unknownSpaceDoesNotBecomeSufficient() {
        val reserve = calculateStorageReserve(1_000)
        assertTrue(checkStorageSpace(Knowledge.Unknown, reserve) is StorageSpaceCheck.Unknown)
        assertTrue(checkStorageSpace(Knowledge.Known(0L), reserve) is StorageSpaceCheck.Insufficient)
        assertTrue(checkStorageSpace(Knowledge.Known(1L shl 30), reserve) is StorageSpaceCheck.Sufficient)
    }

    @Test
    fun stopRecoveryPublishesIncompleteOnlyWhenBytesExist() {
        assertEquals(ClipRecoveryDecision.DELETE, decideClipRecovery(false, 0))
        assertEquals(ClipRecoveryDecision.PUBLISH_INCOMPLETE, decideClipRecovery(false, 1))
        assertEquals(ClipRecoveryDecision.PUBLISH_COMPLETE, decideClipRecovery(true, 1))
    }
}
