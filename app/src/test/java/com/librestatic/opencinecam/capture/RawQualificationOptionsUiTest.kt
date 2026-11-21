/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.capture

import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.rawvideo.RawQualificationEstimate
import com.librestatic.opencinecam.rawvideo.RawQualificationOptions
import com.librestatic.opencinecam.rawvideo.RawQualificationReuse
import com.librestatic.opencinecam.rawvideo.RawQualificationReusedBench
import com.librestatic.opencinecam.rawvideo.RawQualificationSpaceEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure mappings behind the qualification's run options and "storage not tested" wording (PLAN-071, 2026-11-20). */
class RawQualificationOptionsUiTest {
    private val full30 = RawVideoConfig("0", 4080, 3060, 4080, 3060, 30)

    private fun info(freeBytes: Long = 26_000_000_000L, minimum: Long = 3L shl 30, reuse: RawQualificationReuse = RawQualificationReuse.NONE) =
        RawVideoQualificationInfo(
            cameraId = "0",
            gate = ModeGateState.CANDIDATE,
            space = RawQualificationSpaceEstimate(
                estimate = RawQualificationEstimate(durationMs = 60_000L, freeBytesNeeded = 1L, sweepFreeBytesNeeded = 1L, runCount = 1),
                freeBytes = freeBytes, skippedRuns = 0, minimumFreeBytes = minimum, reuse = reuse,
            ),
        )

    // region ready layout

    @Test fun defaultOptionsShowEverythingAndLetTheSpaceDecide() {
        val layout = rawQualificationReadyLayout(RawQualificationOptions(), info())
        assertTrue(layout.showTarget)
        assertTrue(layout.showSpace)
        assertFalse(layout.spaceBlocksStart)
        assertTrue(layout.predictEnabled)
        assertFalse(layout.predictChecked)
        assertFalse(layout.showReuse)
        // Not enough space for the sweep blocks Start.
        assertTrue(rawQualificationReadyLayout(RawQualificationOptions(), info(freeBytes = 1L)).spaceBlocksStart)
    }

    @Test fun cameraOnlyHidesTheStorageAndNeverBlocksForSpace() {
        val layout = rawQualificationReadyLayout(RawQualificationOptions(skipPredictedLongRuns = true, cameraOnly = true), info(freeBytes = 1L))
        assertFalse(layout.showTarget)
        assertFalse(layout.showSpace)
        assertFalse(layout.spaceBlocksStart)
        // The predict switch is disabled and reads off while camera-only is on.
        assertFalse(layout.predictEnabled)
        assertFalse(layout.predictChecked)
    }

    @Test fun predictSwitchReadsItsChoiceWhenCameraOnlyIsOff() {
        val layout = rawQualificationReadyLayout(RawQualificationOptions(skipPredictedLongRuns = true), info())
        assertTrue(layout.predictEnabled)
        assertTrue(layout.predictChecked)
        assertTrue(layout.showSpace)
    }

    @Test fun reuseLineShowsOnlyWithSomethingToReuse() {
        assertFalse(rawQualificationReadyLayout(RawQualificationOptions(), null).showReuse)
        assertFalse(rawQualificationReadyLayout(RawQualificationOptions(), info(reuse = RawQualificationReuse())).showReuse)
        assertTrue(rawQualificationReadyLayout(RawQualificationOptions(), info(reuse = RawQualificationReuse(cameraChecks = setOf(full30)))).showReuse)
        val bench = RawQualificationReusedBench(full30, "lj92", 4, "k")
        assertTrue(rawQualificationReadyLayout(RawQualificationOptions(cameraOnly = true), info(reuse = RawQualificationReuse(benches = setOf(bench)))).showReuse)
    }

    @Test fun noEstimateHidesNothingAndBlocksNothing() {
        val layout = rawQualificationReadyLayout(RawQualificationOptions(), null)
        assertTrue(layout.showSpace)
        assertFalse(layout.spaceBlocksStart)
    }

    @Test fun startDropsPredictUnderCameraOnly() {
        assertEquals(RawQualificationOptions(cameraOnly = true),
            rawQualificationStartOptions(RawQualificationOptions(skipPredictedLongRuns = true, cameraOnly = true)))
        val predict = RawQualificationOptions(skipPredictedLongRuns = true)
        assertEquals(predict, rawQualificationStartOptions(predict))
        assertEquals(RawQualificationOptions(), rawQualificationStartOptions(RawQualificationOptions()))
    }

    // endregion

    // region risk wording

    @Test fun untestedWinsOverABurst() {
        assertEquals(RawRiskWording.UNTESTED, rawRiskWording(storageUntested = true, burstSeconds = 75L))
        assertEquals(RawRiskWording.UNTESTED, rawRiskWording(storageUntested = true, burstSeconds = null))
        assertEquals(RawRiskWording.BURST, rawRiskWording(storageUntested = false, burstSeconds = 75L))
        assertEquals(RawRiskWording.GENERIC, rawRiskWording(storageUntested = false, burstSeconds = null))
    }

    @Test fun riskKindsSayWhetherTheStorageWasTested() {
        assertNull(rawRiskKinds(emptyList()))
        assertEquals(RawRiskKinds.UNTESTED, rawRiskKinds(listOf(true, true)))
        assertEquals(RawRiskKinds.TESTED, rawRiskKinds(listOf(false)))
        assertEquals(RawRiskKinds.MIXED, rawRiskKinds(listOf(false, true)))
    }

    @Test fun anOptionIsUntestedOnlyWhenRisky() {
        assertTrue(RawVideoConfigOption.of(full30, risky = true, storageUntested = true).storageUntested)
        assertFalse(RawVideoConfigOption.of(full30, risky = false, storageUntested = true).storageUntested)
        assertFalse(RawVideoConfigOption.of(full30, risky = true).storageUntested)
    }

    // endregion

    // region items

    private val items = listOf(
        RawQualificationItem("full@30", "4080×3060 · 30 fps", 0L, RawQualificationItemStatus.PENDING),
        RawQualificationItem("full@24", "4080×3060 · 24 fps", 0L, RawQualificationItemStatus.PENDING),
    )

    @Test fun itemsKeepUntestedOnlyOnARiskyRow() {
        val risky = rawQualificationItemsWith(items, "full@30", RawQualificationItemStatus.RISKY, "x", 0L, achievedFps = 29.9, storageUntested = true)
        assertTrue(risky.first { it.spec == "full@30" }.storageUntested)
        assertEquals(29.9, risky.first { it.spec == "full@30" }.achievedFps)
        val passed = rawQualificationItemsWith(items, "full@30", RawQualificationItemStatus.PASSED, "x", 0L, storageUntested = true)
        assertFalse(passed.first { it.spec == "full@30" }.storageUntested)
        // A new row (a compressed follow-up) keeps it too.
        val twin = rawQualificationItemsWith(items, "full@30+bp", RawQualificationItemStatus.RISKY, "twin", 0L, storageUntested = true)
        assertTrue(twin.last().storageUntested)
        assertTrue(rawQualificationStorageUntested(listOf("fps-reduced", com.librestatic.opencinecam.rawvideo.RawQualificationRisk.REASON_STORAGE_NOT_TESTED)))
        assertFalse(rawQualificationStorageUntested(listOf("sweep-median-margin-fail")))
    }

    @Test fun anUntestedRowShowsTheStorageTestedVerdictInForce() {
        val run = items.map { it.copy(status = RawQualificationItemStatus.RISKY, storageUntested = true, achievedFps = 29.9) }
        val shown = rawQualificationItemsInForce(run) { item ->
            when (item.spec) {
                // A full qualification left it passed: the camera-only run did not replace that record.
                "full@30" -> RawQualificationInForce(RawQualificationItemStatus.PASSED, burstSeconds = 75L, achievedFps = 29.8)
                // Risky with a measured burst.
                else -> RawQualificationInForce(RawQualificationItemStatus.RISKY, burstSeconds = 75L, achievedFps = 24.0)
            }
        }
        assertEquals(RawQualificationItem("full@30", "4080×3060 · 30 fps", 0L, RawQualificationItemStatus.PASSED, achievedFps = 29.8), shown[0])
        assertEquals(RawQualificationItem("full@24", "4080×3060 · 24 fps", 0L, RawQualificationItemStatus.RISKY, burstSeconds = 75L, achievedFps = 24.0),
            shown[1])
    }

    @Test fun aStorageLimitInForceDropsTheRate() {
        val run = listOf(items[0].copy(status = RawQualificationItemStatus.RISKY, storageUntested = true, achievedFps = 29.9))
        val shown = rawQualificationItemsInForce(run) { RawQualificationInForce(RawQualificationItemStatus.STORAGE_LIMIT, burstSeconds = 9L, achievedFps = 29.9) }
        assertEquals(RawQualificationItemStatus.STORAGE_LIMIT, shown[0].status)
        assertNull(shown[0].achievedFps)
        assertNull(shown[0].burstSeconds)
        assertFalse(shown[0].storageUntested)
    }

    @Test fun theRunsOwnUntestedVerdictAndOtherRowsStayAsTheyAre() {
        val untested = items[0].copy(status = RawQualificationItemStatus.RISKY, storageUntested = true)
        val limit = items[1].copy(status = RawQualificationItemStatus.CAMERA_LIMIT)
        var asked = 0
        val shown = rawQualificationItemsInForce(listOf(untested, limit)) {
            asked++
            RawQualificationInForce(RawQualificationItemStatus.RISKY, storageUntested = true)
        }
        assertSame(untested, shown[0])
        assertSame(limit, shown[1])
        // Only the untested row is looked up; one with no record stays too.
        assertEquals(1, asked)
        assertSame(untested, rawQualificationItemsInForce(listOf(untested)) { null }[0])
    }

    // endregion
}
