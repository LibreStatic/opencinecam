/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.storage

import com.librestatic.opencinecam.core.model.Knowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePolicyTest {
    @Test
    fun reserveIsOneGiBWhateverTheDataRate() {
        assertEquals(1L shl 30, calculateStorageReserve(1).requiredBytes)
        assertEquals(1L shl 30, calculateStorageReserve(2_000).requiredBytes)
        assertEquals(1L shl 30, calculateStorageReserve(600_000_000L).requiredBytes)
        // 240 fps slow motion at the 200 Mbps clamp: 1.5 GB a minute no longer asks for 3 GB.
        assertEquals(1L shl 30, calculateStorageReserve(1_500_000_000L).requiredBytes)
    }

    @Test
    fun stopHeadroomIsAMinuteOfDataCappedAtHalfAGibibyte() {
        assertEquals(0L, storageStopHeadroom(0))
        assertEquals(90_000_000L, storageStopHeadroom(90_000_000L))
        assertEquals(512L shl 20, storageStopHeadroom(512L shl 20))
        assertEquals(512L shl 20, storageStopHeadroom(1_500_000_000L))
        // An admitted take always has at least its stop headroom left to record into.
        for (perMinute in listOf(1L, 90_000_000L, 600_000_000L, 1_500_000_000L)) {
            assertTrue(calculateStorageReserve(perMinute).requiredBytes - storageStopHeadroom(perMinute) >= storageStopHeadroom(perMinute))
        }
    }

    @Test
    fun boundedTakeNeedsTheHeadroomPlusTheTakeWithAQuarterToSpare() {
        // 10 s of 240 fps slow motion at 1.2 GB a minute: 200 MB of take.
        assertEquals(786_870_912L, calculateStorageReserve(1_200_000_000L, boundedTakeBytes = 200_000_000L).requiredBytes)
        assertEquals((512L shl 20) + 250_000_000L, calculateStorageReserve(1_200_000_000L, boundedTakeBytes = 200_000_000L).requiredBytes)
        // A bounded take of nothing needs only the headroom.
        assertEquals(512L shl 20, calculateStorageReserve(1_500_000_000L, boundedTakeBytes = 0L).requiredBytes)
        assertEquals(90_000_000L, calculateStorageReserve(90_000_000L, boundedTakeBytes = 0L).requiredBytes)
    }

    @Test
    fun boundedReserveIsNeverAboveTheUnboundedOne() {
        // 30 s at 1.5 GB a minute is 750 MB; with its margin it would exceed 1 GiB.
        assertEquals(1L shl 30, calculateStorageReserve(1_500_000_000L, boundedTakeBytes = 750_000_000L).requiredBytes)
        assertEquals(1L shl 30, calculateStorageReserve(1_500_000_000L, boundedTakeBytes = Long.MAX_VALUE).requiredBytes)
        for (perMinute in listOf(1L, 90_000_000L, 600_000_000L, 1_500_000_000L)) {
            for (bounded in listOf(0L, 1_000L, 100_000_000L, 1L shl 30, 10L shl 30)) {
                assertTrue(calculateStorageReserve(perMinute, bounded).requiredBytes <= calculateStorageReserve(perMinute).requiredBytes)
            }
        }
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

    @Test
    fun negativeOrUnknownByteCountsNeverPublish() {
        assertEquals(ClipRecoveryDecision.DELETE, decideClipRecovery(true, 0))
        assertEquals(ClipRecoveryDecision.DELETE, decideClipRecovery(true, -1))
        assertEquals(ClipRecoveryDecision.PUBLISH_INCOMPLETE, decideClipRecovery(false, Long.MAX_VALUE))
    }
}
