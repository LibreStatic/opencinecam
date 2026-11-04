/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.rawvideo.RawQualificationEstimate
import com.librestatic.opencinecam.rawvideo.RawQualificationFrameRate
import com.librestatic.opencinecam.rawvideo.RawQualificationPhase
import com.librestatic.opencinecam.rawvideo.RawQualificationPlannedConfig
import com.librestatic.opencinecam.rawvideo.RawQualificationSpaceEstimate
import com.librestatic.opencinecam.rawvideo.RawQualificationSpecs
import com.librestatic.opencinecam.rawvideo.RawQualificationStaging
import com.librestatic.opencinecam.rawvideo.RawQualificationProgress
import com.librestatic.opencinecam.rawvideo.RawQualificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawVideoQualificationFlowTest {

    // region mode choice

    @Test fun rawVideoCandidateAsksInsteadOfSelecting() {
        assertEquals(ModeChoiceAction.QUALIFY_RAW_VIDEO, modeChoiceAction(CaptureMode.RAW_VIDEO, ModeGateState.CANDIDATE))
        assertTrue(isModeChoosable(CaptureMode.RAW_VIDEO, ModeGateState.CANDIDATE))
        // Selecting itself stays AVAILABLE-only.
        assertFalse(CameraUiState.isModeSelectable(ModeGateState.CANDIDATE))
    }

    @Test fun availableModesSelectAndOtherGatesAreRefused() {
        assertEquals(ModeChoiceAction.SELECT, modeChoiceAction(CaptureMode.RAW_VIDEO, ModeGateState.AVAILABLE))
        assertEquals(ModeChoiceAction.SELECT, modeChoiceAction(CaptureMode.VIDEO, ModeGateState.AVAILABLE))
        assertEquals(ModeChoiceAction.REJECT, modeChoiceAction(CaptureMode.RAW_VIDEO, ModeGateState.FAILED))
        assertEquals(ModeChoiceAction.REJECT, modeChoiceAction(CaptureMode.RAW_VIDEO, ModeGateState.UNSUPPORTED))
        assertEquals(ModeChoiceAction.REJECT, modeChoiceAction(CaptureMode.RAW_VIDEO, null))
        // Only RAW video has a qualification: another candidate is not choosable.
        assertEquals(ModeChoiceAction.REJECT, modeChoiceAction(CaptureMode.VIDEO, ModeGateState.CANDIDATE))
        assertFalse(isModeChoosable(CaptureMode.VIDEO, ModeGateState.CANDIDATE))
    }

    // endregion

    // region readiness

    @Test fun readyAtFiftyPercentUnpluggedAndLight() {
        assertEquals(emptyList<RawQualificationBlocker>(), rawQualificationReadiness(50, thermalStatus = 1))
        assertEquals(emptyList<RawQualificationBlocker>(), rawQualificationReadiness(100, thermalStatus = 0))
    }

    @Test fun batteryBelowFiftyBlocks() {
        assertEquals(listOf(RawQualificationBlocker.BATTERY_LOW), rawQualificationReadiness(49, thermalStatus = 0))
    }

    @Test fun unknownBatteryDoesNotBlock() {
        assertEquals(emptyList<RawQualificationBlocker>(), rawQualificationReadiness(null, thermalStatus = 0))
    }

    @Test fun aChargerNoLongerBlocks() {
        // Charging is allowed: heat is judged by the thermal status, before and during the runs.
        assertEquals(listOf(RawQualificationBlocker.BATTERY_LOW, RawQualificationBlocker.TOO_WARM), RawQualificationBlocker.entries.toList())
    }

    @Test fun moderateThermalBlocksButLightDoesNot() {
        assertEquals(listOf(RawQualificationBlocker.TOO_WARM), rawQualificationReadiness(90, thermalStatus = 2))
        assertTrue(rawQualificationReadiness(90, thermalStatus = 1).isEmpty())
    }

    @Test fun everyBlockerIsListed() {
        val conditions = RawQualificationConditions(batteryPercent = 35, thermalStatus = 2)
        assertEquals(
            listOf(RawQualificationBlocker.BATTERY_LOW, RawQualificationBlocker.TOO_WARM),
            conditions.blockers,
        )
    }

    @Test fun runStopsBelowTwentyPercentOnly() {
        assertEquals(RawQualificationStopReasons.BATTERY, rawQualificationRunAbort(19))
        assertNull(rawQualificationRunAbort(20))
        assertNull(rawQualificationRunAbort(80))
        assertNull(rawQualificationRunAbort(null))
    }

    // endregion

    // region start refusal

    private fun refusal(
        running: Boolean = false,
        phase: CameraUiPhase = CameraUiPhase.PREVIEWING,
        frozen: Boolean = false,
        take: Boolean = false,
        transferring: Boolean = false,
        blockers: List<RawQualificationBlocker> = emptyList(),
        candidate: Boolean = true,
    ) = rawQualificationStartRefusal(running, phase, frozen, take, transferring, blockers, candidate)

    @Test fun startsWithOrWithoutAViewfinder() {
        // The onboarding wizard has no surface: the camera may be opening, ready or not open at all.
        assertNull(refusal(phase = CameraUiPhase.PREVIEWING))
        assertNull(refusal(phase = CameraUiPhase.READY))
        assertNull(refusal(phase = CameraUiPhase.OPENING))
    }

    @Test fun refusesASecondStart() {
        assertEquals(RawQualificationRefusals.RUNNING, refusal(running = true))
    }

    @Test fun refusesDuringATakeATransferOrFrozenSettings() {
        assertEquals(RawQualificationRefusals.BUSY, refusal(phase = CameraUiPhase.RECORDING))
        assertEquals(RawQualificationRefusals.BUSY, refusal(phase = CameraUiPhase.CAPTURING))
        assertEquals(RawQualificationRefusals.BUSY, refusal(take = true))
        assertEquals(RawQualificationRefusals.BUSY, refusal(transferring = true))
        assertEquals(RawQualificationRefusals.BUSY, refusal(frozen = true))
    }

    @Test fun refusesWhenTheConditionsBlockOrNoCameraQualifies() {
        assertEquals(RawQualificationRefusals.NOT_READY, refusal(blockers = listOf(RawQualificationBlocker.TOO_WARM)))
        assertEquals(RawQualificationRefusals.UNSUPPORTED, refusal(candidate = false))
    }

    @Test fun aCameraJudgedBeforeMayBeQualifiedAgain() {
        assertTrue(rawQualificationGateQualifiable(ModeGateState.CANDIDATE))
        assertTrue(rawQualificationGateQualifiable(ModeGateState.AVAILABLE))
        assertTrue(rawQualificationGateQualifiable(ModeGateState.FAILED))
        assertFalse(rawQualificationGateQualifiable(ModeGateState.UNSUPPORTED))
        assertFalse(rawQualificationGateQualifiable(null))
        assertNull(refusal(candidate = rawQualificationGateQualifiable(ModeGateState.AVAILABLE)))
        assertEquals(RawQualificationRefusals.UNSUPPORTED, refusal(candidate = rawQualificationGateQualifiable(ModeGateState.UNSUPPORTED)))
    }

    @Test fun settingsOffersQualifyAgainOnlyOnACameraJudgedBefore() {
        assertTrue(rawVideoRequalifyOffered(ModeGateState.AVAILABLE))
        assertTrue(rawVideoRequalifyOffered(ModeGateState.FAILED))
        // A candidate keeps its hint: choosing RAW video in the mode selector qualifies it.
        assertFalse(rawVideoRequalifyOffered(ModeGateState.CANDIDATE))
        assertFalse(rawVideoRequalifyOffered(ModeGateState.UNSUPPORTED))
        assertFalse(rawVideoRequalifyOffered(null))
    }

    // endregion

    // region outcome and progress

    @Test fun outcomeFollowsStatusAndUsableConfigs() {
        assertEquals(RawQualificationOutcomeKind.CANCELLED, rawQualificationOutcome(RawQualificationStatus.CANCELLED, 3))
        assertEquals(RawQualificationOutcomeKind.PASSED, rawQualificationOutcome(RawQualificationStatus.PASS, 6))
        assertEquals(RawQualificationOutcomeKind.NO_CONFIG, rawQualificationOutcome(RawQualificationStatus.FAIL, 0))
        assertEquals(RawQualificationOutcomeKind.FAILED_TO_RUN, rawQualificationOutcome(RawQualificationStatus.UNKNOWN, 0))
    }

    @Test fun aStorageNotRestedRunCouldNotRunEvenWithAnEarlierQualificationKept() {
        val busy = com.librestatic.opencinecam.rawvideo.RawVideoQualifier.REASON_STORAGE_NOT_RESTED
        // The records of the earlier qualification still make configurations usable; the run itself did not judge them.
        assertEquals(RawQualificationOutcomeKind.FAILED_TO_RUN, rawQualificationOutcome(RawQualificationStatus.UNKNOWN, 6, busy))
        assertEquals(RawQualificationOutcomeKind.FAILED_TO_RUN, rawQualificationOutcome(RawQualificationStatus.UNKNOWN, 0, busy))
        assertEquals(RawQualificationOutcomeKind.CANCELLED, rawQualificationOutcome(RawQualificationStatus.CANCELLED, 6, busy))
        assertEquals(RawQualificationOutcomeKind.PASSED, rawQualificationOutcome(RawQualificationStatus.UNKNOWN, 6, "raw-throughput-exception"))
        assertTrue(rawQualificationStorageBusy(busy))
        assertFalse(rawQualificationStorageBusy(null))
        assertFalse(rawQualificationStorageBusy("raw-throughput-exception"))
    }

    @Test fun stepTextPerPhase() {
        assertEquals(RawQualificationStepText.STORAGE, rawQualificationStepText(RawQualificationPhase.SWEEP))
        assertEquals(RawQualificationStepText.PREPARING, rawQualificationStepText(RawQualificationPhase.RING))
        assertEquals(RawQualificationStepText.CONFIG, rawQualificationStepText(RawQualificationPhase.RUN))
        assertEquals(RawQualificationStepText.CAMERA, rawQualificationStepText(RawQualificationPhase.CAMERA_CHECK))
        assertEquals(RawQualificationStepText.COMPRESSION, rawQualificationStepText(RawQualificationPhase.ENCODE_BENCH))
        assertEquals(RawQualificationStepText.COOLING, rawQualificationStepText(RawQualificationPhase.COOLING))
        assertEquals(RawQualificationStepText.WAITING_TO_COOL, rawQualificationStepText(RawQualificationPhase.THERMAL_WAIT))
        assertEquals(RawQualificationStepText.SAVING, rawQualificationStepText(RawQualificationPhase.SAVING))
        assertEquals(RawQualificationStepText.SAVING, rawQualificationStepText(RawQualificationPhase.DONE))
    }

    @Test fun remainingTimeScalesWithTheFractionLeft() {
        assertEquals(600_000L, rawQualificationRemainingMs(1_000_000L, 0.4))
        assertEquals(1_000_000L, rawQualificationRemainingMs(1_000_000L, -1.0))
        assertEquals(0L, rawQualificationRemainingMs(1_000_000L, 1.5))
    }

    @Test fun uiLabelsOnlyTheConfigurationSteps() {
        val run = rawQualificationUiOf(RawQualificationProgress(RawQualificationPhase.RUN, 0.4, 3, 16, "full@24"), 1_000_000L, "4080×3060 · 24 fps")
        assertEquals("4080×3060 · 24 fps", run.specLabel)
        assertEquals(3, run.runIndex)
        assertEquals(16, run.runCount)
        assertEquals(0.4f, run.fraction, 1e-6f)
        assertEquals(600_000L, run.remainingMs)
        assertTrue(run.running)
        val sweep = rawQualificationUiOf(RawQualificationProgress(RawQualificationPhase.SWEEP, 0.05), 1_000_000L, "ignored")
        assertNull(sweep.specLabel)
        for (phase in listOf(RawQualificationPhase.CAMERA_CHECK, RawQualificationPhase.ENCODE_BENCH)) {
            assertEquals("4080×3060 · 24 fps", rawQualificationUiOf(RawQualificationProgress(phase, 0.5, 4, 9, "full@24"), 1_000_000L, "4080×3060 · 24 fps").specLabel)
        }
    }

    @Test fun progressIsThrottledButStepChangesPostAtOnce() {
        val a = RawVideoQualificationUi(phase = RawQualificationPhase.RUN, runIndex = 3, runCount = 16, fraction = 0.40f)
        assertTrue(rawQualificationShouldPost(null, a, 0))
        assertFalse(rawQualificationShouldPost(a, a.copy(fraction = 0.41f), 100))
        assertTrue(rawQualificationShouldPost(a, a.copy(fraction = 0.41f), 250))
        assertTrue(rawQualificationShouldPost(a, a.copy(runIndex = 4), 10))
        assertTrue(rawQualificationShouldPost(a, a.copy(phase = RawQualificationPhase.COOLING), 10))
    }

    @Test fun minutesRoundUpAndNeverReadZero() {
        assertEquals(14, rawQualificationMinutes(13 * 60_000L + 20_000L))
        assertEquals(12, rawQualificationMinutes(12 * 60_000L))
        assertEquals(1, rawQualificationMinutes(0))
    }

    // endregion

    // region entry points

    @Test fun onboardingReturnFinishesTheWizardInRawVideoOnlyAfterAPass() {
        assertEquals(RawQualificationReturn(finishOnboarding = true, mode = CaptureMode.RAW_VIDEO),
            rawQualificationReturn(RawQualificationEntry.ONBOARDING, passed = true, previousMode = null))
        assertEquals(RawQualificationReturn(finishOnboarding = true, mode = null),
            rawQualificationReturn(RawQualificationEntry.ONBOARDING, passed = false, previousMode = CaptureMode.VIDEO))
    }

    @Test fun selectorReturnGoesBackToThePreviousModeUnlessSomethingPassed() {
        assertEquals(RawQualificationReturn(finishOnboarding = false, mode = CaptureMode.RAW_VIDEO),
            rawQualificationReturn(RawQualificationEntry.SELECTOR, passed = true, previousMode = CaptureMode.PHOTO))
        assertEquals(RawQualificationReturn(finishOnboarding = false, mode = CaptureMode.PHOTO),
            rawQualificationReturn(RawQualificationEntry.SELECTOR, passed = false, previousMode = CaptureMode.PHOTO))
    }

    @Test fun settingsReturnLeavesTheModeAsItIs() {
        assertEquals(RawQualificationReturn(finishOnboarding = false, mode = null),
            rawQualificationReturn(RawQualificationEntry.SETTINGS, passed = true, previousMode = CaptureMode.PHOTO))
        assertEquals(RawQualificationReturn(finishOnboarding = false, mode = null),
            rawQualificationReturn(RawQualificationEntry.SETTINGS, passed = false, previousMode = CaptureMode.RAW_VIDEO))
        assertFalse(rawQualificationSelectsOnPass(RawQualificationEntry.SETTINGS))
        assertTrue(rawQualificationSelectsOnPass(RawQualificationEntry.SELECTOR))
        assertTrue(rawQualificationSelectsOnPass(RawQualificationEntry.ONBOARDING))
    }

    @Test fun rawVideoGivesWayOnlyWhenNothingQualifiesAnyMore() {
        val qualified = mapOf(CaptureMode.RAW_VIDEO to ModeGateState.AVAILABLE, CaptureMode.PHOTO to ModeGateState.AVAILABLE,
            CaptureMode.VIDEO to ModeGateState.AVAILABLE, CaptureMode.LOG to ModeGateState.UNSUPPORTED)
        val lost = qualified + (CaptureMode.RAW_VIDEO to ModeGateState.CANDIDATE)
        // Still qualified (perhaps another configuration): stay; the service's automatic choice replaces a stale one.
        assertNull(rawVideoModeFallback(CaptureMode.RAW_VIDEO, qualified, CaptureMode.PHOTO))
        // Outside RAW video nothing changes.
        assertNull(rawVideoModeFallback(CaptureMode.PHOTO, lost, CaptureMode.VIDEO))
        assertEquals(CaptureMode.PHOTO, rawVideoModeFallback(CaptureMode.RAW_VIDEO, lost, CaptureMode.PHOTO))
        assertEquals(CaptureMode.VIDEO, rawVideoModeFallback(CaptureMode.RAW_VIDEO, lost, CaptureMode.LOG))
        assertEquals(CaptureMode.VIDEO, rawVideoModeFallback(CaptureMode.RAW_VIDEO, lost, null))
        assertEquals(CaptureMode.VIDEO, rawVideoModeFallback(CaptureMode.RAW_VIDEO, lost, CaptureMode.RAW_VIDEO))
    }

    @Test fun onboardingAsksOnlyWithTheCameraGrantedAndACandidate() {
        assertEquals(OnboardingRawStage.QUESTION, onboardingRawStageAfterPages(cameraGranted = true, rawGate = ModeGateState.CANDIDATE))
        assertNull(onboardingRawStageAfterPages(cameraGranted = false, rawGate = ModeGateState.CANDIDATE))
        assertNull(onboardingRawStageAfterPages(cameraGranted = true, rawGate = ModeGateState.AVAILABLE))
        assertNull(onboardingRawStageAfterPages(cameraGranted = true, rawGate = ModeGateState.FAILED))
        assertNull(onboardingRawStageAfterPages(cameraGranted = true, rawGate = ModeGateState.UNSUPPORTED))
        assertNull(onboardingRawStageAfterPages(cameraGranted = true, rawGate = null))
    }

    @Test fun nowOpensTheStepAndLaterEndsTheWizard() {
        assertEquals(OnboardingRawStage.STEP, onboardingRawStageAfterAnswer(now = true))
        assertNull(onboardingRawStageAfterAnswer(now = false))
    }

    // endregion
    // region per-configuration status

    private fun planned(spec: String, needed: Long, free: Long): RawQualificationPlannedConfig {
        val run = requireNotNull(RawQualificationSpecs.parse(spec, "0", 4080, 3060))
        return RawQualificationPlannedConfig(spec, run.config, needed, fits = needed <= free)
    }

    private val plannedSpace = RawQualificationSpaceEstimate(
        estimate = RawQualificationEstimate(durationMs = 60_000L, freeBytesNeeded = 10L, sweepFreeBytesNeeded = 1L, runCount = 3),
        freeBytes = 20L,
        skippedRuns = 1,
        minimumFreeBytes = 1L,
        configs = listOf(planned("full@15", 10L, 20L), planned("full@60", 92L, 20L), planned("1920x1080@30", 3L, 20L)),
        fullPlanFreeBytes = 92L,
    )

    private val items = listOf(
        RawQualificationItem("full@15", "a", 10L, RawQualificationItemStatus.PENDING),
        RawQualificationItem("full@24", "b", 15L, RawQualificationItemStatus.PENDING),
    )

    private fun run(spec: String?, phase: RawQualificationPhase = RawQualificationPhase.RUN) =
        RawQualificationProgress(phase, 0.5, runIndex = 1, runCount = 2, spec = spec)

    @Test fun planItemsArePendingOrSkippedWithTheConfigLabelAndNeededSpace() {
        val planItems = rawQualificationPlanItems(plannedSpace)
        assertEquals(listOf("full@15", "full@60", "1920x1080@30"), planItems.map { it.spec })
        assertEquals(
            listOf(RawQualificationItemStatus.PENDING, RawQualificationItemStatus.SKIPPED_SPACE, RawQualificationItemStatus.PENDING),
            planItems.map { it.status },
        )
        assertEquals(listOf(10L, 92L, 3L), planItems.map { it.neededBytes })
        assertEquals("4080×3060 · 15 fps", planItems[0].label)
        assertEquals(RawVideoConfigOption.of(plannedSpace.configs[2].config).label, planItems[2].label)
        assertTrue(rawQualificationPlanItems(null).isEmpty())
    }

    @Test fun compressedConfigsGetADistinctLabel() {
        for (spec in listOf("full@24+bp", "full@24+lj92")) {
            val config = requireNotNull(RawQualificationSpecs.parse(spec, "0", 4080, 3060)).config
            assertTrue(config.isCompressed)
            val space = plannedSpace.copy(configs = listOf(RawQualificationPlannedConfig(spec, config, 5L, true)))
            assertEquals("4080×3060 · 24 fps · Compressed", rawQualificationPlanItems(space).single().label)
        }
    }

    @Test fun runStatusMapsResultRows() {
        assertEquals(RawQualificationItemStatus.PASSED, rawQualificationRunStatus("PASS", null))
        assertEquals(RawQualificationItemStatus.FAILED, rawQualificationRunStatus("FAIL", "dropped-frames"))
        assertEquals(RawQualificationItemStatus.SKIPPED_SPACE, rawQualificationRunStatus("UNKNOWN", RAW_QUALIFICATION_SPACE_SKIP))
        assertEquals(RawQualificationItemStatus.SKIPPED_HEAT, rawQualificationRunStatus("UNKNOWN", "thermal-skip"))
        assertEquals(RawQualificationItemStatus.SKIPPED_HEAT, rawQualificationRunStatus("UNKNOWN", RAW_QUALIFICATION_THERMAL_SKIP))
        assertEquals(RawQualificationItemStatus.NOT_QUALIFIED, rawQualificationRunStatus("UNKNOWN", "camera-open-timeout"))
        assertEquals(RawQualificationItemStatus.NOT_QUALIFIED, rawQualificationRunStatus(null, null))
    }

    @Test fun severeHeatDuringARunReadsAsTooHot() {
        assertEquals(RawQualificationItemStatus.SKIPPED_HEAT, rawQualificationRunStatus("FAIL", RawQualificationStaging.REASON_THERMAL_LIMIT))
        assertEquals(RawQualificationItemStatus.SKIPPED_HEAT,
            rawQualificationRunStatus("FAIL", "fps-not-reached", listOf("fps-not-reached", RawQualificationStaging.REASON_THERMAL_LIMIT)))
    }

    @Test fun aCameraFpsLimitIsItsOwnStatus() {
        assertEquals(RawQualificationItemStatus.CAMERA_LIMIT, rawQualificationRunStatus("FAIL", RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT))
        assertEquals(RawQualificationItemStatus.FAILED, rawQualificationRunStatus("FAIL", RawQualificationFrameRate.REASON_CAMERA_NO_FRAMES))
        assertEquals(RawQualificationItemStatus.FAILED, rawQualificationRunStatus("FAIL", "fps-not-reached"))
    }

    @Test fun aStorageOnlyFailureIsRisky() {
        assertEquals(RawQualificationItemStatus.RISKY, rawQualificationRunStatus("FAIL", "sweep-median-margin-fail"))
        assertEquals(RawQualificationItemStatus.RISKY, rawQualificationRunStatus("FAIL", "ring-overflow",
            listOf("ring-overflow", "raw-frame-loss", "ring-peak-exceeded", "buffer-backlog-exceeded")))
        // Any other reason keeps it failed: a fault of the run, an invalid sweep, a camera that did not deliver.
        assertEquals(RawQualificationItemStatus.FAILED, rawQualificationRunStatus("FAIL", "sweep-median-margin-fail",
            listOf("sweep-median-margin-fail", "fps-not-reached")))
        assertEquals(RawQualificationItemStatus.FAILED, rawQualificationRunStatus("FAIL", "sweep-window-invalid",
            listOf("sweep-window-invalid", "sweep-median-margin-fail")))
        assertEquals(RawQualificationItemStatus.FAILED, rawQualificationRunStatus("FAIL", "live-run-too-short",
            listOf("live-run-too-short", "raw-frame-loss")))
        assertEquals(RawQualificationItemStatus.FAILED, rawQualificationRunStatus("FAIL", RawQualificationFrameRate.REASON_CAMERA_NO_FRAMES,
            listOf(RawQualificationFrameRate.REASON_CAMERA_NO_FRAMES, "fps-not-reached", "raw-frame-loss")))
        // The camera limit leads and wins over a storage reason.
        assertEquals(RawQualificationItemStatus.CAMERA_LIMIT, rawQualificationRunStatus("FAIL", RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT,
            listOf(RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT, "fps-not-reached", "sweep-median-margin-fail")))
        // Heat and space skips are never risky.
        assertEquals(RawQualificationItemStatus.SKIPPED_HEAT, rawQualificationRunStatus("UNKNOWN", RAW_QUALIFICATION_THERMAL_SKIP))
        assertEquals(RawQualificationItemStatus.SKIPPED_SPACE, rawQualificationRunStatus("UNKNOWN", RAW_QUALIFICATION_SPACE_SKIP))
        // A storage reason in a row that did not fail is not risky.
        assertEquals(RawQualificationItemStatus.NOT_QUALIFIED, rawQualificationRunStatus("UNKNOWN", "ring-overflow"))
    }

    @Test fun rawVideoGivesWayWhenOnlyRiskyConfigurationsAreLeftUnchosen() {
        val gates = mapOf(CaptureMode.RAW_VIDEO to ModeGateState.AVAILABLE, CaptureMode.PHOTO to ModeGateState.AVAILABLE)
        assertNull(rawVideoModeFallback(CaptureMode.RAW_VIDEO, gates, CaptureMode.PHOTO, hasConfig = true))
        assertEquals(CaptureMode.PHOTO, rawVideoModeFallback(CaptureMode.RAW_VIDEO, gates, CaptureMode.PHOTO, hasConfig = false))
        assertNull(rawVideoModeFallback(CaptureMode.PHOTO, gates, CaptureMode.VIDEO, hasConfig = false))
    }

    @Test fun specFpsIsParsedFromTheSpec() {
        assertEquals(60, rawQualificationSpecFps("full@60"))
        assertEquals(60, rawQualificationSpecFps("full@60+lj92"))
        assertEquals(30, rawQualificationSpecFps("3840x2160@30"))
        assertEquals(24, rawQualificationSpecFps("FULL@24+LJ92"))
        assertNull(rawQualificationSpecFps("full"))
        assertNull(rawQualificationSpecFps("full@"))
        assertNull(rawQualificationSpecFps("full@0"))
        assertNull(rawQualificationSpecFps("full@x"))
    }

    @Test fun aHeatSkipIsNeverReadAsAFailure() {
        // The device run: after full@60 the phone reached SEVERE and the qualifier skipped the next two.
        val items = listOf(
            RawQualificationItem("full@60", "a", 1L, RawQualificationItemStatus.TESTING),
            RawQualificationItem("3840x2160@30", "b", 1L, RawQualificationItemStatus.PENDING),
            RawQualificationItem("1920x1080@30", "c", 1L, RawQualificationItemStatus.PENDING),
        )
        val ended = listOf("full@60" to ("FAIL" to "dropped-frames"), "3840x2160@30" to ("UNKNOWN" to "thermal-skip"),
            "1920x1080@30" to ("UNKNOWN" to "thermal-skip"))
            .fold(items) { acc, (spec, row) -> rawQualificationItemsWith(acc, spec, rawQualificationRunStatus(row.first, row.second), spec, 1L) }
        assertEquals(listOf(RawQualificationItemStatus.FAILED, RawQualificationItemStatus.SKIPPED_HEAT, RawQualificationItemStatus.SKIPPED_HEAT),
            rawQualificationItemsFinished(ended).map { it.status })
        // A later progress report for a heat-skipped run leaves it as it is.
        val progress = RawQualificationProgress(RawQualificationPhase.RUN, 0.9, 3, 3, "1920x1080@30")
        assertEquals(ended, rawQualificationItemsOnProgress(ended, progress, "c", 1L))
    }

    @Test fun itemsWithSetsAKnownSpecOrAppendsAFollowup() {
        val set = rawQualificationItemsWith(items, "full@24", RawQualificationItemStatus.PASSED, "ignored", 0L)
        assertEquals(listOf(RawQualificationItemStatus.PENDING, RawQualificationItemStatus.PASSED), set.map { it.status })
        assertEquals("b", set[1].label)
        val appended = rawQualificationItemsWith(items, "full@15+lj92", RawQualificationItemStatus.TESTING, "a · LJ92", 7L)
        assertEquals(3, appended.size)
        assertEquals(RawQualificationItem("full@15+lj92", "a · LJ92", 7L, RawQualificationItemStatus.TESTING), appended.last())
    }

    @Test fun itemsWithKeepTheBurstEstimateOnlyOnARiskyItem() {
        val risky = rawQualificationItemsWith(items, "full@24", RawQualificationItemStatus.RISKY, "b", 0L, burstSeconds = 75L)
        assertEquals(75L, risky[1].burstSeconds)
        assertNull(rawQualificationItemsWith(items, "full@24", RawQualificationItemStatus.PASSED, "b", 0L, burstSeconds = 75L)[1].burstSeconds)
        // A later status without one (a requalified run) drops it.
        assertNull(rawQualificationItemsWith(risky, "full@24", RawQualificationItemStatus.RISKY, "b", 0L)[1].burstSeconds)
    }

    @Test fun itemsWithKeepTheAchievedRateOnAQualifiedOrRiskyItem() {
        val passed = rawQualificationItemsWith(items, "full@24", RawQualificationItemStatus.PASSED, "b", 0L, achievedFps = 20.1)
        assertEquals(20.1, passed[1].achievedFps)
        assertTrue(passed[1].reducedRate)
        val atTarget = rawQualificationItemsWith(items, "full@24", RawQualificationItemStatus.RISKY, "b", 0L, achievedFps = 23.9)
        assertEquals(23.9, atTarget[1].achievedFps)
        assertFalse(atTarget[1].reducedRate)
        assertNull(rawQualificationItemsWith(items, "full@24", RawQualificationItemStatus.FAILED, "b", 0L, achievedFps = 20.1)[1].achievedFps)
    }

    @Test fun progressMarksOnlyAPendingRunAsTesting() {
        val testing = rawQualificationItemsOnProgress(items, run("full@24"), "b", 15L)
        assertEquals(RawQualificationItemStatus.TESTING, testing[1].status)
        assertEquals(RawQualificationItemStatus.PENDING, testing[0].status)
        // A run with a result keeps it.
        val passed = rawQualificationItemsWith(items, "full@15", RawQualificationItemStatus.PASSED, "a", 10L)
        assertEquals(passed, rawQualificationItemsOnProgress(passed, run("full@15"), "a", 10L))
        // A camera check marks its configuration too; the LJ92 benchmark judges no single item.
        assertEquals(RawQualificationItemStatus.TESTING,
            rawQualificationItemsOnProgress(items, run("full@24", RawQualificationPhase.CAMERA_CHECK), "b", 15L)[1].status)
        assertEquals(items, rawQualificationItemsOnProgress(items, run("full@24", RawQualificationPhase.ENCODE_BENCH), "b", 15L))
        // Steps outside a run, or without a spec, change nothing.
        assertEquals(items, rawQualificationItemsOnProgress(items, run("full@24", RawQualificationPhase.COOLING), "b", 15L))
        assertEquals(items, rawQualificationItemsOnProgress(items, run(null), null, 0L))
        // An LJ92 follow-up joins as it starts.
        val followup = rawQualificationItemsOnProgress(items, run("full@15+lj92"), null, 4L)
        assertEquals(RawQualificationItem("full@15+lj92", "full@15+lj92", 4L, RawQualificationItemStatus.TESTING), followup.last())
    }

    @Test fun finishedRunReturnsACutShortItemToPending() {
        val mixed = listOf(
            items[0].copy(status = RawQualificationItemStatus.PASSED),
            items[1].copy(status = RawQualificationItemStatus.TESTING),
        )
        assertEquals(
            listOf(RawQualificationItemStatus.PASSED, RawQualificationItemStatus.PENDING),
            rawQualificationItemsFinished(mixed).map { it.status },
        )
    }

    @Test fun itemChangesPostAtOnce() {
        val last = RawVideoQualificationUi(phase = RawQualificationPhase.RUN, runIndex = 1, runCount = 2, items = items)
        val next = last.copy(items = rawQualificationItemsWith(items, "full@15", RawQualificationItemStatus.PASSED, "a", 10L))
        assertTrue(rawQualificationShouldPost(last, next, sinceLastMs = 0L))
        assertFalse(rawQualificationShouldPost(last, last.copy(fraction = 0.6f), sinceLastMs = 0L))
    }

    // endregion
    // region config labels

    @Test fun configLabelsSplitOffTheCompressedSuffix() {
        assertEquals("4080×3060 · 30 fps" to null, rawConfigLabelParts("4080×3060 · 30 fps"))
        assertEquals("4080×3060 · 30 fps" to "· Compressed", rawConfigLabelParts("4080×3060 · 30 fps · Compressed"))
        // The UI swaps in the localized word.
        assertEquals("4080×3060 · 30 fps" to "· Komprimiert", rawConfigLabelParts("4080×3060 · 30 fps · Compressed", "Komprimiert"))
        assertEquals("4080×3060 · 30 fps · Komprimiert", rawConfigLabelLocalized("4080×3060 · 30 fps · Compressed", "Komprimiert"))
        assertEquals("4080×3060 · 30 fps", rawConfigLabelLocalized("4080×3060 · 30 fps", "Komprimiert"))
    }

    @Test fun configLabelsOnlyBreakBeforeTheCompressedSuffix() {
        assertEquals("4080×3060\u00A0·\u00A030\u00A0fps", rawConfigLabelNoBreak("4080×3060 · 30 fps"))
        val compressed = rawConfigLabelNoBreak("4080×3060 · 30 fps · Compressed")
        assertEquals("4080×3060\u00A0·\u00A030\u00A0fps ·\u00A0Compressed", compressed)
        assertEquals(1, compressed.count { it == ' ' })
        assertEquals("4080×3060\u00A0·\u00A030\u00A0fps ·\u00A0Komprimiert", rawConfigLabelNoBreak("4080×3060 · 30 fps · Compressed", "Komprimiert"))
        val option = RawVideoConfigOption.of(requireNotNull(RawQualificationSpecs.parse("1920x1080@60+bp", "0", 4080, 3060)).config)
        assertEquals(1, rawConfigLabelNoBreak(option.label).count { it == ' ' })
    }

    // endregion
}
