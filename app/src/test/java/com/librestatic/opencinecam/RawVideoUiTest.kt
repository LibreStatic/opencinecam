/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.storage.LocalMediaArtifact
import com.librestatic.opencinecam.storage.LocalMediaKind
import com.librestatic.opencinecam.storage.rawVideoTake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCC-PLAN-071 slice F1: the pure logic behind the RAW video chooser, capacity and ring meter. */
class RawVideoUiTest {
    private val full30 = RawVideoConfigOption("full@30", "4080×3060 · 30 fps", 468_180_000L, isCrop = false)
    private val full15 = RawVideoConfigOption("full@15", "4080×3060 · 15 fps", 234_090_000L, isCrop = false)
    private val crop60 = RawVideoConfigOption("1920x1080@60", "1920×1080 · 60 fps", 155_520_000L, isCrop = true)
    private val options = listOf(full30, full15, crop60)

    @Test fun burstDurationIsSecondsUnderAMinuteThenRoundedMinutes() {
        assertEquals(0L to false, rawBurstDurationParts(-3))
        assertEquals(45L to false, rawBurstDurationParts(45))
        assertEquals(59L to false, rawBurstDurationParts(59))
        assertEquals(1L to true, rawBurstDurationParts(60))
        assertEquals(1L to true, rawBurstDurationParts(75))
        assertEquals(2L to true, rawBurstDurationParts(90))
        assertEquals(3L to true, rawBurstDurationParts(171))
    }

    @Test fun aRiskyOptionKeepsItsBurstEstimateAndASafeOneDropsIt() {
        val config = com.librestatic.opencinecam.camera.RawVideoConfig("0", 4080, 3060, 4080, 3060, 60)
        assertEquals(75L, RawVideoConfigOption.of(config, risky = true, burstSeconds = 75L).burstSeconds)
        assertNull(RawVideoConfigOption.of(config, risky = false, burstSeconds = 75L).burstSeconds)
    }

    @Test fun capacityIsTheFreeSpaceOverTheRateRoundedDown() {
        // 96 GB at 468.18 MB/s lasts 205.049… s: whole milliseconds, rounded down.
        assertEquals(205_049L, rawRecordTimeLeftMs(96_000_000_000L, full30.bytesPerSecond))
        assertEquals(3L, rawRecordMinutesLeft(96_000_000_000L, full30.bytesPerSecond))
    }

    @Test fun capacityMinutesAtTypicalRates() {
        val free = 100_000_000_000L
        assertEquals(3L, rawRecordMinutesLeft(free, full30.bytesPerSecond))
        assertEquals(7L, rawRecordMinutesLeft(free, full15.bytesPerSecond))
        assertEquals(10L, rawRecordMinutesLeft(free, crop60.bytesPerSecond))
        assertEquals("3 min", formatRecordTimeLeft(rawRecordTimeLeftMs(free, full30.bytesPerSecond)!!))
        assertEquals("1 h 47 min", formatRecordTimeLeft(rawRecordTimeLeftMs(1_000_000_000_000L, crop60.bytesPerSecond)!!))
    }

    @Test fun capacityIsUnknownWithoutFreeSpaceOrRate() {
        assertNull(rawRecordTimeLeftMs(null, full30.bytesPerSecond))
        assertNull(rawRecordTimeLeftMs(0L, full30.bytesPerSecond))
        assertNull(rawRecordTimeLeftMs(-1L, full30.bytesPerSecond))
        assertNull(rawRecordTimeLeftMs(1_000L, 0L))
        assertNull(rawRecordMinutesLeft(null, full30.bytesPerSecond))
        assertEquals("< 1 min", formatRecordTimeLeft(rawRecordTimeLeftMs(1_000_000_000L, full30.bytesPerSecond)!!))
    }

    @Test fun capacityDoesNotOverflowOnHugeVolumes() {
        // freeBytes * 1000 would overflow: the estimate divides first and stays positive.
        assertEquals(Long.MAX_VALUE / 1_000L * 1_000L, rawRecordTimeLeftMs(Long.MAX_VALUE, 1_000L))
        assertTrue(rawRecordTimeLeftMs(Long.MAX_VALUE, 1L)!! > 0L)
    }

    @Test fun lj92CapacityUsesTheConservativeRatio() {
        val lj92 = full30.copy(key = "full@30+lj92", isCompressed = true)
        assertEquals((full30.bytesPerSecond / RawVideoConfigOption.COMPRESSED_CAPACITY_RATIO).toLong(), lj92.capacityBytesPerSecond)
        assertEquals(468L, rawRateMegabytes(lj92))
    }

    @Test fun ringLevelsTurnAmberAtHalfAndRedAtEightyPercent() {
        assertEquals(RawRingLevel.NORMAL, rawRingLevel(0))
        assertEquals(RawRingLevel.NORMAL, rawRingLevel(49))
        assertEquals(RawRingLevel.WARNING, rawRingLevel(50))
        assertEquals(RawRingLevel.WARNING, rawRingLevel(79))
        assertEquals(RawRingLevel.DANGER, rawRingLevel(80))
        assertEquals(RawRingLevel.DANGER, rawRingLevel(100))
    }

    @Test fun rateIsWholeDecimalMegabytes() {
        assertEquals(468L, rawRateMegabytes(full30))
        assertEquals(234L, rawRateMegabytes(full15))
        assertEquals(156L, rawRateMegabytes(crop60))
    }

    @Test fun chosenKeyFallsBackToAutomaticForAStaleOrEmptyKey() {
        assertEquals("full@15", rawVideoChosenKey("full@15", options))
        assertEquals("", rawVideoChosenKey("", options))
        assertEquals("", rawVideoChosenKey("3840x2160@30", options))
        assertEquals("", rawVideoChosenKey("full@15", emptyList()))
    }

    @Test fun activeOptionIsTheServiceChoiceOrTheHighestRate() {
        val state = CameraUiState(rawVideoOptions = options, rawVideoSelectedKey = "1920x1080@60")
        assertEquals(crop60, rawVideoActiveOption(state))
        assertEquals(full30, rawVideoActiveOption(state.copy(rawVideoSelectedKey = null)))
        assertEquals(full30, rawVideoActiveOption(state.copy(rawVideoSelectedKey = "gone@24")))
        assertNull(rawVideoActiveOption(CameraUiState()))
    }

    @Test fun activeOptionFallbackSkipsAReducedRateOne() {
        val reduced = full30.copy(achievedFps = 24.6)
        val state = CameraUiState(rawVideoOptions = listOf(reduced) + options.filter { it != full30 }, rawVideoSelectedKey = null)
        assertTrue(reduced.isReducedRate)
        assertEquals(options.filter { it != full30 }.first(), rawVideoActiveOption(state))
        // Chosen by hand it runs.
        assertEquals(reduced, rawVideoActiveOption(state.copy(rawVideoSelectedKey = reduced.key)))
    }

    @Test fun configurationChangesOnlyBetweenTakes() {
        val idle = CameraUiState(phase = CameraUiPhase.PREVIEWING, selectedMode = CaptureMode.RAW_VIDEO)
        assertTrue(rawVideoConfigChangeAllowed(idle))
        assertFalse(rawVideoConfigChangeAllowed(idle.copy(phase = CameraUiPhase.RECORDING)))
        assertFalse(rawVideoConfigChangeAllowed(idle.copy(phase = CameraUiPhase.CAPTURING)))
        assertFalse(rawVideoConfigChangeAllowed(idle.copy(recordingFinalizing = true)))
    }

    @Test fun rawVideoUsesTheVideoSlots() {
        assertEquals(captureSlots(CaptureMode.VIDEO), captureSlots(CaptureMode.RAW_VIDEO))
    }

    @Test fun rawTakesAreVideosToTheGalleryAndOpenInTheApp() {
        assertEquals(GalleryMediaKind.VIDEO, LocalMediaKind.RAW_VIDEO.galleryKind())
        assertEquals(R.string.gallery_raw_video, LocalMediaKind.RAW_VIDEO.kindLabel())
        assertEquals(galleryKindLabel(GalleryMediaKind.VIDEO), LocalMediaKind.VIDEO.kindLabel())
        // PLAN-071 §7 decision 7: every kind opens in the review, RAW included, and RAW plays.
        LocalMediaKind.entries.forEach { assertTrue("$it", it.opensInApp()) }
        assertEquals(R.string.media_action_play, LocalMediaKind.RAW_VIDEO.primaryActionLabel())
    }

    @Test fun rawTakesKeepThePlaceholderAndCannotBeRenamedOrDeleted() {
        assertFalse(LocalMediaKind.RAW_VIDEO.hasSystemThumbnail())
        assertFalse(LocalMediaKind.RAW_VIDEO.renamesAndDeletes())
        LocalMediaKind.entries.filter { it != LocalMediaKind.RAW_VIDEO }.forEach {
            assertTrue("$it", it.hasSystemThumbnail())
            assertTrue("$it", it.renamesAndDeletes())
        }
    }

    @Test fun aRawTakeOpensInTheReviewByItsKindNotItsType() {
        val raw = rawVideoTake(LocalMediaArtifact("content://media/external_primary/file/9", "OCC_TAKE_1.ocraw",
            "application/octet-stream", 4_000_000_000L, 1L))
        val takes = listOf(raw)
        assertTrue(opensInReview(raw.primary, takes))
        // The same octet-stream file outside a RAW take is handed to another app.
        assertFalse(opensInReview(raw.primary, emptyList()))
        assertFalse(opensInReview(LocalMediaArtifact("content://media/external_primary/file/10", "notes.bin",
            "application/octet-stream", 10L, 1L), takes))
        listOf("video/mp4", "audio/mp4", "image/jpeg").forEach {
            assertTrue(it, opensInReview(LocalMediaArtifact("content://x/$it", "a", it, 1L, 1L), emptyList()))
        }
    }

    @Test fun reviewPagingKeepsRawTakes() {
        val raw = rawVideoTake(LocalMediaArtifact("content://media/external_primary/file/9", "OCC_TAKE_1.ocraw",
            "application/octet-stream", 4_000_000_000L, 1L))
        // The review's paging filter (MediaPlaybackDialog) and the gallery's selection both use opensInApp().
        assertEquals(listOf(raw), listOf(raw).filter { it.kind.opensInApp() })
    }

    @Test fun rawTakesCarryTheRaw10Badge() {
        val take = rawVideoTake(LocalMediaArtifact("content://media/external_primary/file/9", "OCC_TAKE_1.ocraw",
            "application/octet-stream", 4_000_000_000L, 1L))
        assertEquals(CodecBadge("RAW10"), codecBadge(take, null))
    }

    @Test fun statusLineReadsTheConfigurationRateAndCapacityNotTheEncoderBitrate() {
        val state = CameraUiState(selectedMode = CaptureMode.RAW_VIDEO, rawVideoOptions = options, rawVideoSelectedKey = "full@30",
            availableStorageBytes = 96_000_000_000L)
        assertEquals(RawStatusFacts("4080×3060 · 30 fps", false, 468L, 205_049L), rawVideoStatusFacts(state))
        val crop = rawVideoStatusFacts(state.copy(rawVideoSelectedKey = "1920x1080@60"))!!
        assertTrue(crop.isCrop)
        assertEquals(156L, crop.megabytesPerSecond)
        // Unknown free space: the rate stays, the time is left out.
        assertNull(rawVideoStatusFacts(state.copy(availableStorageBytes = null))!!.timeLeftMs)
        // Other modes keep the encoded-video line; RAW without a configuration says nothing about rate.
        assertNull(rawVideoStatusFacts(state.copy(selectedMode = CaptureMode.VIDEO)))
        assertNull(rawVideoStatusFacts(state.copy(rawVideoOptions = emptyList())))
    }

    @Test fun statusLineCapacityForAnLj92OptionUsesTheConservativeRatio() {
        val lj92 = full30.copy(key = "full@30+lj92", label = "4080×3060 · 30 fps · LJ92", isCompressed = true)
        val state = CameraUiState(selectedMode = CaptureMode.RAW_VIDEO, rawVideoOptions = listOf(lj92), rawVideoSelectedKey = lj92.key,
            availableStorageBytes = 96_000_000_000L)
        val facts = rawVideoStatusFacts(state)!!
        assertEquals(468L, facts.megabytesPerSecond)
        assertEquals(96_000_000_000L * 1_000L / lj92.capacityBytesPerSecond, facts.timeLeftMs)
        assertTrue(facts.timeLeftMs!! > rawRecordTimeLeftMs(96_000_000_000L, full30.bytesPerSecond)!!)
    }
}
