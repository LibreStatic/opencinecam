/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.core.model.StorageBenchmarkOutcome
import com.librestatic.opencinecam.core.model.StorageBenchmarkResult
import com.librestatic.opencinecam.core.model.StorageBenchmarkWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDriveBenchmarkTest {
    private fun result(outcome: StorageBenchmarkOutcome, vararg rates: Long) = StorageBenchmarkResult(
        benchmarkId = "drive-speed",
        destinationId = "saf:FFF1-7571",
        windows = rates.mapIndexed { index, rate -> StorageBenchmarkWindow(index, index * 1_000_000_000L, 1_000, rate, rate) },
        bytesWritten = rates.sum(),
        requiredBytesPerSecond = 0,
        qualificationMarginBytesPerSecond = rates.minOrNull(),
        outcome = outcome,
    )

    @Test fun medianAndMinimumOfTheWindows() {
        assertEquals(DriveSpeed(300, 100, 42), driveSpeedOf(result(StorageBenchmarkOutcome.QUALIFIED, 500, 100, 300), 42))
        // An even count averages the middle two.
        assertEquals(DriveSpeed(350, 100, 7),
            driveSpeedOf(result(StorageBenchmarkOutcome.NOT_QUALIFIED, 400, 100, 600, 300, 900, 200).let {
                it.copy(windows = it.windows.take(4))
            }, 7))
        assertEquals(DriveSpeed(9_000_000, 9_000_000, 1), driveSpeedOf(result(StorageBenchmarkOutcome.QUALIFIED, 9_000_000), 1))
    }

    @Test fun noSpeedFromAnUnfinishedRun() {
        for (outcome in listOf(StorageBenchmarkOutcome.FAILED, StorageBenchmarkOutcome.CANCELLED, StorageBenchmarkOutcome.UNSUPPORTED)) {
            assertNull(outcome.name, driveSpeedOf(result(outcome, 500, 400), 1))
        }
        assertNull(driveSpeedOf(result(StorageBenchmarkOutcome.QUALIFIED), 1))
    }

    @Test fun encodingRoundTripsAndToleratesGarbage() {
        val speed = DriveSpeed(412_345_678, 398_000_000, 1_762_560_000_000)
        assertEquals(speed, decodeDriveSpeed(speed.encode()))
        for (bad in listOf(null, "", "garbage", "v1;1;2", "v2;1;2;3", "v1;a;2;3", "v1;1;2;3;4", "v1;-1;2;3")) {
            assertNull(bad, decodeDriveSpeed(bad))
        }
    }

    @Test fun tooSlowLeavesAQuarterOfHeadroom() {
        val speed = DriveSpeed(125, 100, 0)
        assertFalse(driveTooSlow(speed, 100))
        assertTrue(driveTooSlow(speed, 101))
        assertTrue(driveTooSlow(DriveSpeed(124, 124, 0), 100))
        assertFalse(driveTooSlow(speed, 0))
        // 9 MB/s against a 12.5 MB/s 100 Mb/s take.
        assertTrue(driveTooSlow(DriveSpeed(9_000_000, 8_000_000, 0), 12_500_000))
        assertFalse(driveTooSlow(DriveSpeed(412_000_000, 400_000_000, 0), 12_500_000))
    }

    @Test fun measuresOnlyWithFourGibibytesFreeOrUnknown() {
        assertTrue(driveSpeedTestFits(null))
        assertTrue(driveSpeedTestFits(4L * 1024 * 1024 * 1024))
        assertFalse(driveSpeedTestFits(4L * 1024 * 1024 * 1024 - 1))
        assertFalse(driveSpeedTestFits(0))
    }
}
