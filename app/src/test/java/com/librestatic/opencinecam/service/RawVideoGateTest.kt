/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.capture.RAW_QUALIFICATION_SPACE_SKIP
import com.librestatic.opencinecam.capture.RAW_QUALIFICATION_THERMAL_SKIP
import com.librestatic.opencinecam.capture.RawQualificationItemStatus
import com.librestatic.opencinecam.capture.RawVideoConfigKeys
import com.librestatic.opencinecam.capture.RawVideoConfigOption
import com.librestatic.opencinecam.capture.RawVideoCropPreview
import com.librestatic.opencinecam.capture.RawVideoCropStatus
import com.librestatic.opencinecam.capture.RawVideoLeftOutReason
import com.librestatic.opencinecam.capture.RawVideoSelection
import com.librestatic.opencinecam.rawvideo.RawVideoArmState
import com.librestatic.opencinecam.rawvideo.RawVideoCrop
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.rawvideo.RawQualificationFrameRate
import com.librestatic.opencinecam.rawvideo.RawQualificationSpecs
import com.librestatic.opencinecam.rawvideo.RawQualificationStaging
import com.librestatic.opencinecam.camera.BacklogSimulation
import com.librestatic.opencinecam.camera.RawBenchmarkStatus
import com.librestatic.opencinecam.camera.RawLiveRun
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoQualification
import com.librestatic.opencinecam.camera.RawVideoQualificationRecord
import com.librestatic.opencinecam.camera.RawVideoReasons
import com.librestatic.opencinecam.rawvideo.RawTakeReasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawVideoGateTest {
    private val sensor = 4080 to 3060
    private val sizes = listOf(sensor, 1920 to 1080)
    private val full30 = config(fps = 30)
    private val full24 = config(fps = 24)
    private val crop60 = config(cropWidth = 1920, cropHeight = 1080, fps = 60)
    private val otherCamera = config(cameraId = "2", fps = 30)

    private fun config(cameraId: String = "0", sensorWidth: Int = sensor.first, sensorHeight: Int = sensor.second,
        cropWidth: Int = sensorWidth, cropHeight: Int = sensorHeight, fps: Int) =
        RawVideoConfig(cameraId, sensorWidth, sensorHeight, cropWidth, cropHeight, fps)

    @Test fun gateIsUnsupportedWithoutRaw10WhateverElseHolds() {
        assertEquals(ModeGateState.UNSUPPORTED, rawVideoGate(emptyList(), listOf(full30), failedThisSession = true))
        assertEquals(ModeGateState.UNSUPPORTED, rawVideoGate(emptyList(), emptyList(), failedThisSession = false))
    }

    @Test fun aRefusedGraphFailsTheCameraForTheSessionEvenWhenQualified() {
        assertEquals(ModeGateState.FAILED, rawVideoGate(sizes, listOf(full30), failedThisSession = true))
        assertEquals(ModeGateState.FAILED, rawVideoGate(sizes, emptyList(), failedThisSession = true))
    }

    @Test fun oneUsableConfigMakesItAvailableNoneLeavesACandidate() {
        assertEquals(ModeGateState.AVAILABLE, rawVideoGate(sizes, listOf(crop60), failedThisSession = false))
        assertEquals(ModeGateState.CANDIDATE, rawVideoGate(sizes, emptyList(), failedThisSession = false))
    }

    @Test fun usableConfigsKeepThisCameraAndAdvertisedSensorSizesHighestRateFirst() {
        val stale = config(sensorWidth = 4000, sensorHeight = 3000, fps = 30)
        val usable = rawVideoUsableConfigs("0", sizes, listOf(full24, otherCamera, crop60, stale, full30, full30.copy()))
        // 4080x3060x1.25x30 = 468 MB/s, x24 = 374 MB/s; 1920x1080x1.25x60 = 155 MB/s.
        assertEquals(listOf(full30, full24, crop60), usable)
    }

    @Test fun usableConfigsBreakEqualRatesOnTheHigherFrameRate() {
        val half = config(cropWidth = 2040, cropHeight = 1530, fps = 120)
        val quarterSlow = config(cropWidth = 4080, cropHeight = 765, fps = 120)
        val sameRateFaster = config(cropWidth = 1020, cropHeight = 765, fps = 480)
        assertEquals(half.streamBytesPerSecond, sameRateFaster.streamBytesPerSecond)
        val usable = rawVideoUsableConfigs("0", sizes, listOf(half, sameRateFaster, quarterSlow))
        assertEquals(sameRateFaster, usable.first())
    }

    @Test fun selectionHonoursAQualifiedKeyAndOtherwiseTakesTheHighestRate() {
        val usable = rawVideoUsableConfigs("0", sizes, listOf(crop60, full24, full30))
        assertEquals(crop60, selectRawVideoConfig(usable, "1920x1080@60"))
        assertEquals(full24, selectRawVideoConfig(usable, "full@24"))
        // Default, unknown and no longer qualified keys.
        assertEquals(full30, selectRawVideoConfig(usable, ""))
        assertEquals(full30, selectRawVideoConfig(usable, "full@60"))
        assertEquals(full30, selectRawVideoConfig(usable, "1280x720@120"))
        assertEquals(full30, selectRawVideoConfig(usable.reversed(), ""))
        assertNull(selectRawVideoConfig(emptyList(), "full@30"))
    }

    @Test fun defaultSelectionKeepsTheFullFieldOfViewOverAFasterCrop() {
        // On the S24 3840x2160@60 (622 MB/s) outruns full@30 (468 MB/s), but it is a crop.
        val uhd60 = config(cropWidth = 3840, cropHeight = 2160, fps = 60)
        val usable = rawVideoUsableConfigs("0", sizes, listOf(uhd60, crop60, full24, full30))
        assertEquals(uhd60, usable.first())
        assertEquals(full30, selectRawVideoConfig(usable, ""))
        assertEquals(uhd60, selectRawVideoConfig(usable, "3840x2160@60"))
        // Only crops qualified: the highest rate among them.
        assertEquals(uhd60, selectRawVideoConfig(rawVideoUsableConfigs("0", sizes, listOf(crop60, uhd60)), ""))
    }

    @Test fun admissionNamesTheGateReasonFirst() {
        assertEquals(RawTakeAdmission.Refuse(RawVideoServiceReasons.UNSUPPORTED), admitRawTake(ModeGateState.UNSUPPORTED, full30, false, Long.MAX_VALUE))
        assertEquals(RawTakeAdmission.Refuse(RawVideoServiceReasons.FAILED), admitRawTake(ModeGateState.FAILED, full30, true, null))
        assertEquals(RawTakeAdmission.Refuse(RawVideoServiceReasons.NOT_QUALIFIED), admitRawTake(ModeGateState.CANDIDATE, full30, false, Long.MAX_VALUE))
        assertEquals(RawTakeAdmission.Refuse(RawVideoServiceReasons.NOT_QUALIFIED), admitRawTake(null, full30, false, Long.MAX_VALUE))
        assertEquals(RawTakeAdmission.Refuse(RawVideoServiceReasons.NOT_QUALIFIED), admitRawTake(ModeGateState.AVAILABLE, null, false, Long.MAX_VALUE))
    }

    @Test fun admissionRefusesWithoutARingBeforeLookingAtStorage() {
        assertEquals(RawTakeAdmission.Refuse(RawVideoServiceReasons.MEMORY), admitRawTake(ModeGateState.AVAILABLE, full30, true, null))
    }

    @Test fun admissionNeedsTenSecondsOfTheDataRate() {
        val needed = rawTakeMinimumFreeBytes(full30)
        assertEquals(full30.streamBytesPerSecond * 10, needed)
        assertEquals(RawTakeAdmission.Admit, admitRawTake(ModeGateState.AVAILABLE, full30, false, needed))
        assertEquals(RawTakeAdmission.Refuse(RawVideoServiceReasons.STORAGE), admitRawTake(ModeGateState.AVAILABLE, full30, false, needed - 1))
        // An unreadable figure never admits a take of this rate.
        assertEquals(RawTakeAdmission.Refuse(RawVideoServiceReasons.STORAGE), admitRawTake(ModeGateState.AVAILABLE, full30, false, null))
        // A lighter configuration needs less.
        assertEquals(RawTakeAdmission.Admit, admitRawTake(ModeGateState.AVAILABLE, crop60, false, needed - 1))
    }

    @Test fun theRecorderReasonWinsThenTheEngineThenThePolicy() {
        assertEquals(RawTakeReasons.RING_FULL, rawTakeEndReason(RawTakeReasons.RING_FULL, RawVideoReasons.SESSION_CLOSED, "thermal"))
        assertEquals(RawVideoReasons.CAMERA_ERROR, rawTakeEndReason(null, RawVideoReasons.CAMERA_ERROR, "thermal"))
        assertEquals("thermal", rawTakeEndReason(null, null, "thermal"))
        assertNull(rawTakeEndReason(null, null, null))
    }

    @Test fun endReasonsMapToWhatTheOperatorIsTold() {
        assertEquals(RawTakeEndKind.RING_FULL, rawTakeEndKind(RawTakeReasons.RING_FULL))
        assertEquals(RawTakeEndKind.RING_FULL, rawTakeEndKind(RawVideoReasons.RING_FULL))
        assertEquals(RawTakeEndKind.WRITER, rawTakeEndKind(RawTakeReasons.WRITER_ERROR))
        assertEquals(RawTakeEndKind.STALLED, rawTakeEndKind(RawTakeReasons.WRITER_STALLED))
        assertEquals(RawTakeEndKind.FRAME, rawTakeEndKind(RawTakeReasons.FRAME_INVALID))
        assertEquals(RawTakeEndKind.FRAME, rawTakeEndKind(RawVideoReasons.FRAME_LOST))
        assertEquals(RawTakeEndKind.FRAME, rawTakeEndKind(RawTakeReasons.STREAM_FAILED_PREFIX + RawVideoReasons.FRAME_LOST))
        assertEquals(RawTakeEndKind.STREAM, rawTakeEndKind(RawTakeReasons.STREAM_FAILED_PREFIX + RawVideoReasons.CAMERA_ERROR))
        for (reason in listOf(RawVideoReasons.CAMERA_DISCONNECTED, RawVideoReasons.CAMERA_ERROR, RawVideoReasons.SESSION_LOST, RawVideoReasons.SESSION_CLOSED)) {
            assertEquals(reason, RawTakeEndKind.STREAM, rawTakeEndKind(reason))
        }
        assertEquals(RawTakeEndKind.OTHER, rawTakeEndKind(RawVideoReasons.CONSUMER_FAILED))
        assertEquals(RawTakeEndKind.OTHER, rawTakeEndKind("something-new"))
    }

    @Test fun optionsCarryTheKeyLabelRateAndCrop() {
        val full = RawVideoConfigOption.of(full30)
        assertEquals("full@30", full.key)
        assertEquals("4080×3060 · 30 fps", full.label)
        assertEquals(full30.streamBytesPerSecond, full.bytesPerSecond)
        assertFalse(full.isCrop)
        assertEquals(468.18, full.megabytesPerSecond, 0.01)
        val crop = RawVideoConfigOption.of(crop60)
        assertEquals("1920x1080@60", crop.key)
        assertEquals("1920×1080 · 60 fps", crop.label)
        assertTrue(crop.isCrop)
    }

    @Test fun keysAreValidatedStrictly() {
        for (key in listOf("", "full@30", "1920x1080@60", "4080x3060@24")) assertTrue(key, RawVideoConfigKeys.isValid(key))
        for (key in listOf("full", "full@0", "FULL@30", "0x1080@60", "1920x1080", "1920x1080@60 ", "x@30", "123456x1@30", "full@12345")) {
            assertFalse(key, RawVideoConfigKeys.isValid(key))
        }
        assertEquals("full@24", RawVideoConfigKeys.key(full24))
        // A crop as tall as the sensor but narrower is still a crop.
        assertEquals("2040x3060@30", RawVideoConfigKeys.key(config(cropWidth = 2040, fps = 30)))
    }

    @Test fun lj92KeysAndOptionsShowTheUncompressedRateAndAConservativeCapacity() {
        val lj92 = full30.copy(encoding = RawVideoConfig.ENCODING_LJ92)
        assertEquals("full@30+lj92", RawVideoConfigKeys.key(lj92))
        assertEquals("1920x1080@60+lj92", RawVideoConfigKeys.key(crop60.copy(encoding = RawVideoConfig.ENCODING_LJ92)))
        for (key in listOf("full@30+lj92", "1920x1080@60+lj92")) assertTrue(key, RawVideoConfigKeys.isValid(key))
        for (key in listOf("full@30+", "full@30+LJ92", "full@30+lj92+lj92", "full+lj92@30", "full@30+BP", "full@30+bp+lj92")) {
            assertFalse(key, RawVideoConfigKeys.isValid(key))
        }

        val option = RawVideoConfigOption.of(lj92)
        assertEquals("full@30+lj92", option.key)
        assertEquals("4080×3060 · 30 fps · Compressed", option.label)
        assertTrue(option.isCompressed)
        // The rate is never discounted by the ratio; only the capacity estimate is, at 1.8x.
        assertEquals(full30.streamBytesPerSecond, option.bytesPerSecond)
        assertEquals((full30.streamBytesPerSecond / 1.8).toLong(), option.capacityBytesPerSecond)
        val plain = RawVideoConfigOption.of(full30)
        assertFalse(plain.isCompressed)
        assertEquals(plain.bytesPerSecond, plain.capacityBytesPerSecond)
    }

    @Test fun blockPackKeysAndOptions() {
        val bp = full30.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        assertEquals("full@30+bp", RawVideoConfigKeys.key(bp))
        assertEquals("1920x1080@60+bp", RawVideoConfigKeys.key(crop60.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)))
        for (key in listOf("full@30+bp", "1920x1080@60+bp")) assertTrue(key, RawVideoConfigKeys.isValid(key))
        val option = RawVideoConfigOption.of(bp)
        assertEquals("full@30+bp", option.key)
        assertEquals("4080×3060 · 30 fps · Compressed", option.label)
        assertTrue(option.isCompressed)
        assertEquals(full30.streamBytesPerSecond, option.bytesPerSecond)
        assertEquals((full30.streamBytesPerSecond / RawVideoConfigOption.COMPRESSED_CAPACITY_RATIO).toLong(), option.capacityBytesPerSecond)
    }

    @Test fun aBlockPackConfigIsUsableLikeLj92AndWinsOverALegacyLj92Twin() {
        val full30Bp = full30.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        val full30Lj92 = full30.copy(encoding = RawVideoConfig.ENCODING_LJ92)
        val crop60Lj92 = crop60.copy(encoding = RawVideoConfig.ENCODING_LJ92)
        val usable = rawVideoUsableConfigs("0", sizes, listOf(full30Lj92, full30Bp, crop60Lj92, full30), codecAvailable = true)
        // Both would read "Compressed": block-pack stays, a legacy LJ92 record of another geometry too.
        assertEquals(listOf(full30, full30Bp, crop60Lj92), usable)
        assertEquals(listOf(full30), rawVideoUsableConfigs("0", sizes, listOf(full30Bp, full30), codecAvailable = false))
        // Its twin failing for the camera keeps it out.
        assertEquals(listOf(full30), rawVideoUsableConfigs("0", sizes, listOf(full30Bp, full30), codecAvailable = true, cameraFailed = listOf(full30)))
        // Chosen only by its key; the default stays uncompressed.
        assertEquals(full30Bp, selectRawVideoConfig(usable, "full@30+bp"))
        assertEquals(full30, selectRawVideoConfig(usable, ""))
    }

    @Test fun anLj92ConfigIsUsableOnItsOwnRecordWithTheCodec() {
        val full30Lj92 = full30.copy(encoding = RawVideoConfig.ENCODING_LJ92)
        val crop60Lj92 = crop60.copy(encoding = RawVideoConfig.ENCODING_LJ92)
        val blockPack = full24.copy(encoding = "bayer-block-pack")
        // crop60 qualified only compressed: usable on its own record (protocol 3, decision 1).
        val usable = rawVideoUsableConfigs("0", sizes, listOf(full30Lj92, crop60Lj92, full30, full24, blockPack), codecAvailable = true)
        assertEquals(listOf(full30, full30Lj92, full24, crop60Lj92), usable)
        // Without the native codec the LJ92 variants disappear.
        assertEquals(listOf(full30, full24), rawVideoUsableConfigs("0", sizes, listOf(full30Lj92, full30, full24), codecAvailable = false))
        // Only this camera's configurations count.
        assertEquals(listOf(full30Lj92), rawVideoUsableConfigs("0", sizes, listOf(full30Lj92, otherCamera), codecAvailable = true))

        // LJ92 is chosen only by its key; the default stays uncompressed.
        assertEquals(full30Lj92, selectRawVideoConfig(usable, "full@30+lj92"))
        assertEquals(full30, selectRawVideoConfig(usable, ""))
        assertEquals(crop60Lj92, selectRawVideoConfig(usable.reversed(), "1920x1080@60+lj92"))
    }

    @Test fun anLj92ConfigStaysOutWhenItsTwinFailedForTheCamera() {
        val full30Lj92 = full30.copy(encoding = RawVideoConfig.ENCODING_LJ92)
        val crop60Lj92 = crop60.copy(encoding = RawVideoConfig.ENCODING_LJ92)
        val qualified = listOf(full30Lj92, crop60Lj92, full24)
        assertEquals(listOf(full30Lj92, full24), rawVideoUsableConfigs("0", sizes, qualified, codecAvailable = true, cameraFailed = listOf(crop60)))
        // A camera failure of the LJ92 key itself or of another camera is not its twin's.
        assertEquals(listOf(full30Lj92, full24, crop60Lj92),
            rawVideoUsableConfigs("0", sizes, qualified, codecAvailable = true, cameraFailed = listOf(crop60Lj92, otherCamera)))
        // An uncompressed configuration is never removed by cameraFailed: it is only offered when it qualified.
        assertEquals(listOf(full24), rawVideoUsableConfigs("0", sizes, listOf(full24), codecAvailable = true, cameraFailed = listOf(full24)))
    }

    @Test fun automaticNeverPicksARiskyConfiguration() {
        val full60 = config(fps = 60)
        val usable = rawVideoUsableConfigs("0", sizes, listOf(full60, full30, crop60))
        val risky = setOf(full60)
        // Automatic, a stale key and an unknown key skip the risky full@60 for full@30.
        assertEquals(full30, selectRawVideoConfig(usable, "", risky))
        assertEquals(full30, selectRawVideoConfig(usable, "1280x720@120", risky))
        // Chosen by hand it runs.
        assertEquals(full60, selectRawVideoConfig(usable, "full@60", risky))
        // Only risky configurations: Automatic resolves to none, an explicit choice still works.
        val onlyRisky = rawVideoUsableConfigs("0", sizes, listOf(full60))
        assertNull(selectRawVideoConfig(onlyRisky, "", setOf(full60)))
        assertEquals(full60, selectRawVideoConfig(onlyRisky, "full@60", setOf(full60)))
    }

    @Test fun aRiskyOnlyCameraIsStillAvailable() {
        val full60 = config(fps = 60)
        val usable = rawVideoUsableConfigs("0", sizes, listOf(full60))
        assertEquals(ModeGateState.AVAILABLE, rawVideoGate(sizes, usable, failedThisSession = false))
        assertEquals(setOf(full60), rawVideoRiskyConfigs(usable, listOf(full60)))
    }

    @Test fun anLj92TwinOfARiskyConfigurationIsJudgedOnItsOwnRecord() {
        val full60 = config(fps = 60)
        val full60Lj92 = full60.copy(encoding = RawVideoConfig.ENCODING_LJ92)
        val usable = rawVideoUsableConfigs("0", sizes, listOf(full60, full60Lj92, full30), codecAvailable = true)
        // The Razr: full@60 risky, its LJ92 variant ready at the compressed rate.
        assertEquals(setOf(full60), rawVideoRiskyConfigs(usable, listOf(full60)))
        assertEquals(setOf(full60, full60Lj92), rawVideoRiskyConfigs(usable, listOf(full60, full60Lj92)))
        assertTrue(rawVideoRiskyConfigs(usable, emptyList()).isEmpty())
        assertTrue(RawVideoConfigOption.of(full60, risky = true).isRisky)
        assertFalse(RawVideoConfigOption.of(full60).isRisky)
    }

    @Test fun aDriveIsGatedAndAdmittedOnItsOwnRecordsAndFreeSpace() {
        // PLAN-072 Delivery 3: the phone qualified full@30; a ~100 MB/s HDD only the 1080p crop; a new drive nothing.
        val dir = java.nio.file.Files.createTempDirectory("raw-gate").toFile()
        try {
            val store = com.librestatic.opencinecam.storage.RawVideoQualificationStore(
                java.io.File(dir, com.librestatic.opencinecam.storage.RawVideoQualificationStore.FILE_NAME), "fp",
                com.librestatic.opencinecam.camera.RawThroughputBenchmark.PROTOCOL_VERSION)
            val phone = rawDestinationId(com.librestatic.opencinecam.storage.RecordingDestination.Phone)
            val hdd = com.librestatic.opencinecam.storage.RawVideoQualificationStore.destinationIdForDrive("FFF1-7571")
            store.upsertAll(listOf(record(full30, destination = phone), record(crop60, destination = hdd)))
            fun usable(destination: String) = rawVideoUsableConfigs("0", sizes, store.qualifiedConfigs("0", destinationId = destination))
            assertEquals(listOf(full30), usable(phone))
            assertEquals(listOf(crop60), usable(hdd))
            assertEquals(ModeGateState.CANDIDATE, rawVideoGate(sizes, usable(rawVideoQualificationTestDrive), failedThisSession = false))
            assertEquals(crop60, selectRawVideoConfig(usable(hdd), ""))
            // A key qualified only on the phone does not carry over to the drive.
            assertEquals(crop60, selectRawVideoConfig(usable(hdd), RawVideoConfigKeys.key(full30)))
            // Admission measures the drive: ten seconds of the crop's rate on it, whatever the phone holds.
            val needed = rawTakeMinimumFreeBytes(crop60)
            assertEquals(RawTakeAdmission.Admit, admitRawTake(ModeGateState.AVAILABLE, crop60, false, needed))
            assertEquals(RawTakeAdmission.Refuse(RawVideoServiceReasons.STORAGE), admitRawTake(ModeGateState.AVAILABLE, crop60, false, needed - 1))
            // A drive whose provider gives no figure refuses rather than borrowing the phone's.
            assertEquals(RawTakeAdmission.Refuse(RawVideoServiceReasons.STORAGE), admitRawTake(ModeGateState.AVAILABLE, crop60, false, null))
        } finally {
            dir.deleteRecursively()
        }
    }

    private val rawVideoQualificationTestDrive = com.librestatic.opencinecam.storage.RawVideoQualificationStore.destinationIdForDrive("AAAA-0001")

    // region runtime ring risk

    private val mb = 1_000_000L

    private fun record(
        config: RawVideoConfig,
        ringBytes: Long = 1_000 * mb,
        livePeak: Long? = 200 * mb,
        backlog: Long? = 150 * mb,
        at: Long = 1_000,
        destination: String = "dest",
    ): RawVideoQualificationRecord {
        val live = livePeak?.let { RawLiveRun(config, 20_400, 600, 600, false, it, ringBytes, 12.0) }
        val simulated = backlog?.let { BacklogSimulation(ringBytes, it, it.toDouble() / ringBytes, false, it, it.toDouble() / ringBytes, false) }
        val q = RawVideoQualification(RawBenchmarkStatus.PASS, "pass", listOf("pass"), config.streamBytesPerSecond, 0, 20, null, null,
            null, null, null, ringBytes, ringBytes / 2, simulated, live, live?.let { it.peakRingBytes.toDouble() / ringBytes })
        return RawVideoQualificationRecord("fp", destinationId = destination, config = config, ringBytes = ringBytes,
            qualification = q, measuredAtEpochMs = at)
    }

    @Test fun aRingAsBigAsTheQualifiedOneIsNotRiskyAtRuntime() {
        val r = record(full30)
        assertFalse(rawVideoRingTooSmall(r, 1_000 * mb))
        assertFalse(rawVideoRingTooSmall(r, 2_000 * mb))
        // Exactly twice the peak still meets the half-ring rule.
        assertFalse(rawVideoRingTooSmall(r, 400 * mb))
        assertFalse(rawVideoRingTooSmall(null, 10 * mb))
    }

    @Test fun aRingBelowTwiceTheQualifiedPeakIsRiskyAtRuntime() {
        val r = record(full30)
        // Halved to 500 MB, half the ring still holds the 200 MB peak; below 400 MB it does not.
        assertFalse(rawVideoRingTooSmall(r, 500 * mb))
        assertTrue(rawVideoRingTooSmall(r, 399 * mb))
        // A 300 MB peak passed on 1 GB but not on a halved ring; the larger of the live peak and the backlog counts.
        assertTrue(rawVideoRingTooSmall(record(full30, livePeak = 100 * mb, backlog = 300 * mb), 500 * mb))
        assertTrue(rawVideoRingTooSmall(record(full30, livePeak = null, backlog = 300 * mb), 500 * mb))
        assertTrue(rawVideoRingTooSmall(record(full30, livePeak = 300 * mb, backlog = null), 500 * mb))
    }

    @Test fun withoutPeakFiguresTheRingsAreCompared() {
        val r = record(full30, livePeak = null, backlog = null)
        assertFalse(rawVideoRingTooSmall(r, 1_000 * mb))
        assertTrue(rawVideoRingTooSmall(r, 999 * mb))
    }

    @Test fun theNewestRecordOfTheConfigurationCountsAndLj92FallsBackToItsTwin() {
        val old = record(full30, at = 1)
        val newer = record(full30, at = 2, livePeak = 400 * mb)
        val elsewhere = record(full30, at = 3, destination = "other")
        val records = listOf(old, newer, elsewhere, record(crop60, at = 4))
        assertEquals(newer, rawVideoNewestRecord(records, "dest", full30))
        val lj92 = full30.copy(encoding = RawVideoConfig.ENCODING_LJ92)
        assertEquals(newer, rawVideoNewestRecord(records, "dest", lj92))
        val own = record(lj92, at = 5)
        assertEquals(own, rawVideoNewestRecord(records + own, "dest", lj92))
        assertNull(rawVideoNewestRecord(records, "dest", full24))
    }

    // endregion

    // region reduced-rate configurations

    @Test fun automaticNeverResolvesToAReducedRateConfiguration() {
        val full60 = config(fps = 60)
        val usable = rawVideoUsableConfigs("0", sizes, listOf(full60, full30, crop60))
        // full@60 passed but its camera reaches 51.9 fps; full@30 reaches its target, an old record names no rate.
        val records = listOf(record(full60).copy(achievedFps = 51.9), record(full30).copy(achievedFps = 29.9), record(crop60))
        val reduced = rawVideoReducedRates(usable, records, "dest")
        assertEquals(mapOf(full60 to 51.9), reduced)
        // Automatic skips it, like a risky one; the user can still choose it by key.
        assertEquals(full60, selectRawVideoConfig(usable, ""))
        assertEquals(full30, selectRawVideoConfig(usable, "", reduced.keys))
        assertEquals(full60, selectRawVideoConfig(usable, "full@60", reduced.keys))
        // Only reduced-rate full-sensor ones: Automatic falls to a crop, or to nothing.
        assertEquals(crop60, selectRawVideoConfig(listOf(full60, crop60), "", reduced.keys))
        assertNull(selectRawVideoConfig(listOf(full60), "", reduced.keys))
        // Another destination's records say nothing here.
        assertTrue(rawVideoReducedRates(usable, records, "other").isEmpty())
    }

    @Test fun aReducedRateOptionCarriesItsRateOnlyBelowTarget() {
        val full60 = config(fps = 60)
        assertEquals(51.9, RawVideoConfigOption.of(full60, achievedFps = 51.9).achievedFps)
        assertTrue(RawVideoConfigOption.of(full60, achievedFps = 51.9).isReducedRate)
        // At or within the tolerance of its target the option names no rate.
        assertNull(RawVideoConfigOption.of(full60, achievedFps = 59.9).achievedFps)
        assertNull(RawVideoConfigOption.of(full60, achievedFps = 57.0).achievedFps)
        assertFalse(RawVideoConfigOption.of(full60).isReducedRate)
    }

    // endregion

    // region left-out configurations

    private fun verdict(config: RawVideoConfig, status: RawBenchmarkStatus, vararg reasons: String, at: Long = 1_000, destination: String = "dest") =
        record(config, at = at, destination = destination).let { r ->
            val codes = reasons.toList()
            r.copy(qualification = r.qualification.copy(status = status, reasonCode = codes.firstOrNull() ?: "pass", reasonCodes = codes))
        }

    private val fullAt60 = config(fps = 60)
    private val uhdAt60 = config(cropWidth = 3840, cropHeight = 2160, fps = 60)
    private val cropAt120 = config(cropWidth = 1920, cropHeight = 1080, fps = 120)
    private val heat = RawQualificationStaging.REASON_THERMAL_LIMIT
    private val sweepFail = RawThroughputBenchmark.REASON_SWEEP_MEDIAN_MARGIN_FAIL

    // region storage not tested (PLAN-071, 2026-11-20)

    private val untested = com.librestatic.opencinecam.rawvideo.RawQualificationRisk.REASON_STORAGE_NOT_TESTED

    @Test fun riskyConfigsLeftByACameraOnlyRunAreUntested() {
        val records = listOf(
            verdict(full30, RawBenchmarkStatus.FAIL, untested, at = 2),
            // A storage-tested risky verdict reads as before.
            verdict(fullAt60, RawBenchmarkStatus.FAIL, sweepFail),
            // The newest record counts: a full qualification after the camera-only one tested the storage.
            verdict(full24, RawBenchmarkStatus.FAIL, untested, at = 1),
            verdict(full24, RawBenchmarkStatus.FAIL, sweepFail, at = 3),
            // Another destination's camera-only record says nothing here.
            verdict(uhdAt60, RawBenchmarkStatus.FAIL, untested, destination = "other"),
        )
        assertEquals(setOf(full30), rawVideoUntestedConfigs(listOf(full30, fullAt60, full24, uhdAt60), records, "dest"))
        assertTrue(rawVideoUntestedConfigs(emptyList(), records, "dest").isEmpty())
    }

    @Test fun theVerdictInForceOfARecordReadsAsAResultRow() {
        val passed = rawQualificationInForceOf(record(full30).copy(achievedFps = 29.8))
        assertEquals(RawQualificationItemStatus.PASSED, passed.status)
        assertEquals(29.8, passed.achievedFps)
        assertFalse(passed.storageUntested)
        val cameraOnly = rawQualificationInForceOf(verdict(full30, RawBenchmarkStatus.FAIL, untested))
        assertEquals(RawQualificationItemStatus.RISKY, cameraOnly.status)
        assertTrue(cameraOnly.storageUntested)
        val slow = rawQualificationInForceOf(verdict(fullAt60, RawBenchmarkStatus.FAIL, sweepFail))
        assertFalse(slow.storageUntested)
        assertTrue(slow.status == RawQualificationItemStatus.RISKY || slow.status == RawQualificationItemStatus.STORAGE_LIMIT)
    }

    // endregion

    @Test fun leftOutConfigsSayWhyEachJudgedConfigurationIsMissing() {
        val records = listOf(
            verdict(full30, RawBenchmarkStatus.PASS),
            verdict(fullAt60, RawBenchmarkStatus.FAIL, heat),
            // Hot, but the sweep alone had already ruled it out: the storage, not the heat.
            verdict(uhdAt60, RawBenchmarkStatus.FAIL, heat, sweepFail),
            verdict(cropAt120, RawBenchmarkStatus.FAIL, RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT),
            verdict(crop60, RawBenchmarkStatus.FAIL, "live-run-exception"),
            verdict(full24, RawBenchmarkStatus.UNKNOWN, RAW_QUALIFICATION_THERMAL_SKIP),
        )
        val usable = listOf(full30)
        val leftOut = rawVideoLeftOutConfigs("0", sizes, records, "dest", usable)
        // Highest data rate first, as the chooser lists them; the usable full@30 is never left out.
        assertEquals(listOf(fullAt60, uhdAt60, full24, cropAt120, crop60), leftOut.map { it.config })
        assertEquals(listOf(RawQualificationItemStatus.SKIPPED_HEAT, RawQualificationItemStatus.STORAGE_LIMIT, RawQualificationItemStatus.SKIPPED_HEAT,
            RawQualificationItemStatus.CAMERA_LIMIT, RawQualificationItemStatus.FAILED), leftOut.map { it.status })
        assertEquals(listOf(RawVideoLeftOutReason.HEAT, RawVideoLeftOutReason.STORAGE, RawVideoLeftOutReason.HEAT,
            RawVideoLeftOutReason.CAMERA_LIMIT, RawVideoLeftOutReason.NOT_QUALIFIED), rawVideoLeftOutOptions(leftOut).map { it.reason })
        val option = rawVideoLeftOutOptions(leftOut).first()
        assertEquals("full@60", option.option.key)
        assertEquals(60, option.fps)
    }

    @Test fun aStorageFailureUnderTheBurstFloorIsLeftOutAsStorageTooSlow() {
        val tooSlow = verdict(fullAt60, RawBenchmarkStatus.FAIL, sweepFail).let { it.copy(burstSeconds = 9L) }
        val leftOut = rawVideoLeftOutConfigs("0", sizes, listOf(tooSlow), "dest", emptyList())
        assertEquals(listOf(RawQualificationItemStatus.STORAGE_LIMIT), leftOut.map { it.status })
        assertEquals(RawVideoLeftOutReason.STORAGE, rawVideoLeftOutOptions(leftOut).single().reason)
        // A minute or more, or unknown, is risky (usable by hand), so it is not left out.
        val risky = tooSlow.copy(burstSeconds = 75L)
        assertTrue(rawVideoLeftOutConfigs("0", sizes, listOf(risky), "dest", listOf(fullAt60)).isEmpty())
    }

    @Test fun aStorageFailureWithNoBurstButASlowMedianIsLeftOutAsStorageTooSlow() {
        val base = verdict(fullAt60, RawBenchmarkStatus.FAIL, sweepFail)
        val q = base.qualification
        val slow = base.copy(burstSeconds = null, qualification = q.copy(streamBytesPerSecond = 450_000_000L,
            requiredMedianBytesPerSecond = 505_125_000L, sweepMedianBytesPerSecond = 94_649_675L, ringBytes = 4_290_000_000L))
        assertEquals(listOf(RawQualificationItemStatus.STORAGE_LIMIT), rawVideoLeftOutConfigs("0", sizes, listOf(slow), "dest", emptyList()).map { it.status })
    }

    @Test fun aSpaceSkipReadsNotQualified() {
        val leftOut = rawVideoLeftOutConfigs("0", sizes, listOf(verdict(fullAt60, RawBenchmarkStatus.UNKNOWN, RAW_QUALIFICATION_SPACE_SKIP)), "dest", emptyList())
        assertEquals(listOf(RawQualificationItemStatus.SKIPPED_SPACE), leftOut.map { it.status })
        assertEquals(RawVideoLeftOutReason.NOT_QUALIFIED, rawVideoLeftOutOptions(leftOut).single().reason)
    }

    @Test fun onlyTheNewestRecordOfAConfigurationCounts() {
        // A later pass revokes the earlier failure: usable, so not left out.
        val records = listOf(verdict(fullAt60, RawBenchmarkStatus.FAIL, heat, at = 1), verdict(fullAt60, RawBenchmarkStatus.PASS, at = 2))
        assertTrue(rawVideoLeftOutConfigs("0", sizes, records, "dest", listOf(fullAt60)).isEmpty())
        // And a later failure of a passed configuration explains it.
        val revoked = listOf(verdict(fullAt60, RawBenchmarkStatus.PASS, at = 1), verdict(fullAt60, RawBenchmarkStatus.FAIL, heat, at = 2))
        assertEquals(RawQualificationItemStatus.SKIPPED_HEAT, rawVideoLeftOutConfigs("0", sizes, revoked, "dest", emptyList()).single().status)
    }

    @Test fun aCompressedTwinOfACameraFailedConfigurationTakesTheTwinsReason() {
        val fullAt60Bp = fullAt60.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        val full30Bp = full30.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        val records = listOf(
            verdict(fullAt60, RawBenchmarkStatus.FAIL, heat), verdict(fullAt60Bp, RawBenchmarkStatus.PASS),
            // The twin passed: a passed compressed record not usable (no codec here) says nothing it could explain.
            verdict(full30, RawBenchmarkStatus.PASS), verdict(full30Bp, RawBenchmarkStatus.PASS),
        )
        val leftOut = rawVideoLeftOutConfigs("0", sizes, records, "dest", listOf(full30))
        assertEquals(listOf(fullAt60, fullAt60Bp), leftOut.map { it.config })
        assertEquals(listOf(RawQualificationItemStatus.SKIPPED_HEAT, RawQualificationItemStatus.SKIPPED_HEAT), leftOut.map { it.status })
        // A compressed configuration that failed on its own record keeps its own reason.
        val ownFail = rawVideoLeftOutConfigs("0", sizes, listOf(verdict(full30Bp, RawBenchmarkStatus.FAIL, sweepFail, heat)), "dest", emptyList())
        assertEquals(RawQualificationItemStatus.STORAGE_LIMIT, ownFail.single().status)
    }

    @Test fun leftOutConfigsIgnoreOtherDestinationsCamerasStaleSizesAndLj92() {
        val stale = config(sensorWidth = 4000, sensorHeight = 3000, fps = 60)
        val records = listOf(
            verdict(fullAt60, RawBenchmarkStatus.FAIL, heat, destination = "saf:OTHER"),
            verdict(config(cameraId = "2", fps = 60), RawBenchmarkStatus.FAIL, heat),
            verdict(stale, RawBenchmarkStatus.FAIL, heat),
            verdict(fullAt60.copy(encoding = RawVideoConfig.ENCODING_LJ92), RawBenchmarkStatus.FAIL, heat),
            verdict(fullAt60.copy(encoding = "something-new"), RawBenchmarkStatus.FAIL, heat),
        )
        assertTrue(rawVideoLeftOutConfigs("0", sizes, records, "dest", emptyList()).isEmpty())
        // A risky (storage-only) record is usable and listed as such, never left out.
        assertTrue(rawVideoLeftOutConfigs("0", sizes, listOf(verdict(fullAt60, RawBenchmarkStatus.FAIL, sweepFail)), "dest", listOf(fullAt60)).isEmpty())
    }

    // endregion

    // region PLAN-076 placed windows

    @Test fun keysMayPlaceTheWindowAfterItsSize() {
        for (key in listOf("1920x816+1080+1122@24", "1920x816+1080+1122@24+bp", "1920x816+0+0@24+lj92", "320x240+1880+1410@120")) {
            assertTrue(key, RawVideoConfigKeys.isValid(key))
        }
        for (key in listOf(
            "full+0+0@30", "1920x816+1080@24", "1920x816+1080+@24", "1920x816++1122@24", "1920x816+-4+2@24", "1920x816-4+2@24",
            "1920x816+01+2@24", "1920x816+1080+1122", "1920x816@24+1080+1122", "1920x816+1080+1122+4@24", "1920x816+123456+0@24",
        )) {
            assertFalse(key, RawVideoConfigKeys.isValid(key))
        }
    }

    @Test fun aPlacedKeyRoundTripsAndACentredOriginIsLeftOut() {
        val config = config(cropWidth = 1920, cropHeight = 816, fps = 24).copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        val placed = RawVideoCrop(1000, 1100, 1920, 816)
        val key = RawVideoConfigKeys.key(config, placed)
        assertEquals("1920x816+1000+1100@24+bp", key)
        assertEquals(RawVideoSelection(config, placed), RawVideoConfigKeys.parse(key, "0", 4080, 3060))

        // (4080 - 1920) / 2 = 1080, (3060 - 816) / 2 = 1122: the centred origin, so the key omits it.
        assertEquals("1920x816@24+bp", RawVideoConfigKeys.key(config, RawVideoCrop(1080, 1122, 1920, 816)))
        val centred = config(cropWidth = 1920, cropHeight = 1080, fps = 60)
        assertEquals("1920x1080@60", RawVideoConfigKeys.key(centred, RawVideoCrop.centred(centred)))
        assertEquals("1920x1080+0+0@60", RawVideoConfigKeys.key(centred, RawVideoCrop(0, 0, 1920, 1080)))
        assertEquals("full@30", RawVideoConfigKeys.key(full30, RawVideoCrop(0, 0, 4080, 3060)))
        // An explicit centred origin parses to the same selection as none.
        assertEquals(RawVideoConfigKeys.parse("1920x1080@60", "0", 4080, 3060), RawVideoConfigKeys.parse("1920x1080+1080+990@60", "0", 4080, 3060))
        assertEquals(RawVideoSelection(centred, RawVideoCrop(1080, 990, 1920, 1080)), RawVideoConfigKeys.parse("1920x1080@60", "0", 4080, 3060))
        assertEquals(RawVideoSelection(full30, RawVideoCrop(0, 0, 4080, 3060)), RawVideoConfigKeys.parse("full@30", "0", 4080, 3060))
        // A small window at a high rate.
        val small = config(cropWidth = 320, cropHeight = 240, fps = 120)
        assertEquals(RawVideoSelection(small, RawVideoCrop(1880, 1410, 320, 240)), RawVideoConfigKeys.parse("320x240+1880+1410@120", "0", 4080, 3060))
        assertThrows(IllegalArgumentException::class.java) { RawVideoConfigKeys.key(centred, RawVideoCrop(0, 0, 1920, 816)) }
    }

    @Test fun parseRefusesWindowsATakeCannotWrite() {
        for (key in listOf(
            "", "nonsense", "1920x817+1080+1122@24", "1918x816+1080+1122@24", "1920x816+1082+1122@24", "1920x816+1080+1121@24",
            "1920x816+2164+0@24", "1920x816+0+2246@24", "5000x816@24", "124x240@120", "320x94@120", "1920x1081@24",
        )) {
            assertNull(key, RawVideoConfigKeys.parse(key, "0", 4080, 3060))
        }
        assertNull(RawVideoConfigKeys.parse("1920x816@24", " ", 4080, 3060))
        // At the sensor's edge it still fits: 4080 - 1920 = 2160, 3060 - 816 = 2244.
        assertEquals(RawVideoCrop(2160, 2244, 1920, 816), RawVideoConfigKeys.parse("1920x816+2160+2244@24", "0", 4080, 3060)?.crop)
    }

    private val custom = config(cropWidth = 1920, cropHeight = 816, fps = 24)
    private val crop1080at24 = config(cropWidth = 1920, cropHeight = 1080, fps = 24)
    private val crop720at24 = config(cropWidth = 1280, cropHeight = 720, fps = 24)

    private fun customVerdict(
        config: RawVideoConfig = custom,
        usable: List<RawVideoConfig>,
        risky: Set<RawVideoConfig> = emptySet(),
        reduced: Map<RawVideoConfig, Double?> = emptyMap(),
        leftOut: List<RawVideoLeftOutConfig> = emptyList(),
        median: Long? = null,
        ring: Long? = null,
    ) = rawVideoCustomVerdict(config, usable, risky, reduced, leftOut, median, ring)

    @Test fun aQualifiedSizeKeepsItsOwnVerdict() {
        assertEquals(RawVideoCustomVerdict.Passed(false, null), customVerdict(crop1080at24, listOf(full24, crop1080at24)))
        assertEquals(RawVideoCustomVerdict.Risky(null), customVerdict(crop1080at24, listOf(crop1080at24), risky = setOf(crop1080at24)))
        assertEquals(RawVideoCustomVerdict.Passed(true, 21.3), customVerdict(crop1080at24, listOf(crop1080at24), reduced = mapOf(crop1080at24 to 21.3)))
    }

    @Test fun theCameraMustBeKnownToHoldTheRate() {
        val at60 = config(cropWidth = 1920, cropHeight = 816, fps = 60)
        val usable = listOf(full30, full24)
        fun leftOut(vararg statuses: RawQualificationItemStatus) = statuses.mapIndexed { i, status ->
            RawVideoLeftOutConfig(config(cropWidth = 4080 - 4 * i, fps = 60), status)
        }
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.NOT_QUALIFIED), customVerdict(at60, usable))
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.CAMERA_LIMIT),
            customVerdict(at60, usable, leftOut = leftOut(RawQualificationItemStatus.SKIPPED_HEAT, RawQualificationItemStatus.CAMERA_LIMIT)))
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.HEAT),
            customVerdict(at60, usable, leftOut = leftOut(RawQualificationItemStatus.FAILED, RawQualificationItemStatus.SKIPPED_HEAT)))
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.NOT_QUALIFIED),
            customVerdict(at60, usable, leftOut = leftOut(RawQualificationItemStatus.FAILED, RawQualificationItemStatus.SKIPPED_SPACE)))
        // Another camera's 60 fps says nothing about this one.
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.NOT_QUALIFIED),
            customVerdict(at60, usable + config(cameraId = "2", fps = 60)))
        // A configuration left out for the storage alone proves the camera: the storage then decides.
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.STORAGE),
            customVerdict(at60, usable, leftOut = leftOut(RawQualificationItemStatus.STORAGE_LIMIT)))
        // So does any usable configuration at the rate, of any size or encoding.
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.STORAGE),
            customVerdict(at60, usable + config(cropWidth = 1280, cropHeight = 720, fps = 60).copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)))
    }

    @Test fun anUncompressedWindowPassesUnderAQualifiedOneThatStreamsMore() {
        assertEquals(RawVideoCustomVerdict.Passed(false, null), customVerdict(usable = listOf(full24, crop1080at24, crop720at24)))
        // Reduced-rate only when every coverer is, at the highest rate one of them achieved.
        assertEquals(RawVideoCustomVerdict.Passed(true, 21.3),
            customVerdict(usable = listOf(full24, crop1080at24), reduced = mapOf(crop1080at24 to 21.3, full24 to 20.1)))
        assertEquals(RawVideoCustomVerdict.Passed(true, null), customVerdict(usable = listOf(crop1080at24), reduced = mapOf(crop1080at24 to null)))
        assertEquals(RawVideoCustomVerdict.Passed(false, null), customVerdict(usable = listOf(full24, crop1080at24), reduced = mapOf(crop1080at24 to 21.3)))
        // A risky coverer does not pass it; the passing one does.
        assertEquals(RawVideoCustomVerdict.Passed(false, null), customVerdict(usable = listOf(full24, crop1080at24), risky = setOf(full24)))
    }

    @Test fun withoutAPassingCoverTheStorageDecides() {
        // 1920 x 816 x 1.25 x 24 = 47 001 600 B/s; x 1.1225 = 52 759 296 B/s.
        assertEquals(47_001_600L, custom.streamBytesPerSecond)
        val usable = listOf(crop720at24)
        val gib = 1L shl 30
        // The sweep meets the margin, but no long run vouches for it.
        assertEquals(RawVideoCustomVerdict.Risky(null), customVerdict(usable = usable, median = 52_759_296L))
        // It keeps up below the margin: the cache does not limit it.
        assertEquals(RawVideoCustomVerdict.Risky(null), customVerdict(usable = usable, median = 50_000_000L, ring = gib))
        // Short of the stream: the ring lasts 1 GiB / 7 001 600 B/s = 153 s.
        assertEquals(RawVideoCustomVerdict.Risky(153), customVerdict(usable = usable, median = 40_000_000L, ring = gib))
        // A risky coverer keeps it risky even when the ring runs out sooner, or nothing is known.
        assertEquals(RawVideoCustomVerdict.Risky(14), customVerdict(usable = listOf(full24), risky = setOf(full24), median = 40_000_000L, ring = 100_000_000L))
        assertEquals(RawVideoCustomVerdict.Risky(null), customVerdict(usable = listOf(full24), risky = setOf(full24)))
        // Too short a burst, or no figures, and no cover.
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.STORAGE), customVerdict(usable = usable, median = 40_000_000L, ring = 100_000_000L))
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.STORAGE), customVerdict(usable = usable, median = 40_000_000L))
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.STORAGE), customVerdict(usable = usable))
        // A compressed configuration covers no uncompressed window.
        val bp = crop1080at24.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.STORAGE), customVerdict(usable = listOf(bp)))
    }

    @Test fun aBlockPackWindowNeedsABlockPackConfigurationWithAsManyPixels() {
        val customBp = custom.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        val bp1080 = crop1080at24.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        val bp720 = crop720at24.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        assertEquals(RawVideoCustomVerdict.Passed(false, null), customVerdict(customBp, listOf(bp1080, bp720)))
        assertEquals(RawVideoCustomVerdict.Passed(true, 22.0), customVerdict(customBp, listOf(bp1080), reduced = mapOf(bp1080 to 22.0)))
        assertEquals(RawVideoCustomVerdict.Risky(null), customVerdict(customBp, listOf(bp1080, bp720), risky = setOf(bp1080)))
        // The uncompressed twin and a smaller block-pack one do not cover it, whatever the sweep says.
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.NOT_QUALIFIED),
            customVerdict(customBp, listOf(full24, crop1080at24, bp720), median = 2_000_000_000L, ring = 1L shl 30))
        // Any other compressed encoding is never judged.
        val lj92 = custom.copy(encoding = RawVideoConfig.ENCODING_LJ92)
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.NOT_QUALIFIED),
            customVerdict(lj92, listOf(crop1080at24.copy(encoding = RawVideoConfig.ENCODING_LJ92), full24)))
    }

    private fun context(
        usable: List<RawVideoConfig>,
        risky: Set<RawVideoConfig> = emptySet(),
        reduced: Map<RawVideoConfig, Double?> = emptyMap(),
        leftOut: List<RawVideoLeftOutConfig> = emptyList(),
        median: Long? = null,
        ring: Long? = null,
        burst: Map<RawVideoConfig, Long> = emptyMap(),
        armed: RawVideoArmState? = null,
    ) = RawVideoCustomContext("0", 4080, 3060, usable, risky, reduced, leftOut, median, ring, burst, armed)

    @Test fun anExactUsableKeySelectsItCentred() {
        val usable = listOf(full24, crop1080at24)
        val resolved = requireNotNull(resolveRawVideoSelection("1920x1080@24", usable, custom = context(usable)))
        assertEquals(crop1080at24, resolved.config)
        assertEquals(RawVideoCrop.centred(crop1080at24), resolved.crop)
        assertNull(resolved.custom)
        assertFalse(resolved.isCustom)
        assertEquals("1920x1080@24", resolved.key)
    }

    @Test fun aWindowThePassedConfigurationsCoverIsSelectedAtItsOrigin() {
        val usable = listOf(full24, crop1080at24)
        val resolved = requireNotNull(resolveRawVideoSelection("1920x816+1000+1100@24", usable, custom = context(usable)))
        assertEquals(custom, resolved.config)
        assertEquals(RawVideoCrop(1000, 1100, 1920, 816), resolved.crop)
        assertEquals(RawVideoCustomVerdict.Passed(false, null), resolved.custom)
        assertTrue(resolved.isCustom)
        assertEquals("1920x816+1000+1100@24", resolved.key)
        // Centred, it is still a window of the operator's: no usable configuration has its size.
        val centred = requireNotNull(resolveRawVideoSelection("1920x816@24", usable, custom = context(usable)))
        assertEquals(RawVideoCrop.centred(custom), centred.crop)
        assertTrue(centred.isCustom)
    }

    @Test fun aRiskyWindowIsSelectedWithItsVerdict() {
        val usable = listOf(crop720at24)
        val resolved = requireNotNull(resolveRawVideoSelection("1920x816+1000+1100@24", usable, custom = context(usable, median = 52_759_296L)))
        assertEquals(RawVideoCustomVerdict.Risky(null), resolved.custom)
        assertEquals(RawVideoCrop(1000, 1100, 1920, 816), resolved.crop)
        val gib = 1L shl 30
        assertEquals(RawVideoCustomVerdict.Risky(153),
            resolveRawVideoSelection("1920x816+0+0@24", usable, custom = context(usable, median = 40_000_000L, ring = gib))?.custom)
    }

    @Test fun theRingArmedForAWindowSetsItsBurstButNotItsVerdict() {
        val usable = listOf(crop720at24)
        val gib = 1L shl 30
        val window = RawVideoCrop(1000, 1100, 1920, 816)
        // A 100 MB ring armed for this window lasts 100 000 000 / 7 001 600 B/s = 14 s, not the qualification ring's 153 s,
        // and stays risky: a smaller ring never refuses the window it was armed for.
        val armed = RawVideoArmState(custom, window, slotBytes = 1_000_000, slotCount = 100)
        assertEquals(RawVideoCustomVerdict.Risky(14),
            resolveRawVideoSelection("1920x816+1000+1100@24", usable, custom = context(usable, median = 40_000_000L, ring = gib, armed = armed))?.custom)
        // A ring armed for another window leaves the qualification ring's estimate.
        assertEquals(RawVideoCustomVerdict.Risky(153),
            resolveRawVideoSelection("1920x816+0+0@24", usable, custom = context(usable, median = 40_000_000L, ring = gib, armed = armed))?.custom)
        // A refused window stays refused whatever the armed ring.
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.STORAGE),
            context(usable, median = 40_000_000L, ring = 100_000_000L, armed = armed.copy(slotCount = 1000)).judge("1920x816+1000+1100@24")?.custom)
    }

    @Test fun aRefusedWindowFallsBackToAutomatic() {
        // At 24 fps only a smaller window passed, and nothing is known of the storage: refused.
        val usable = listOf(full30, crop720at24)
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.STORAGE), context(usable).judge("1920x816+1000+1100@24")?.custom)
        val resolved = requireNotNull(resolveRawVideoSelection("1920x816+1000+1100@24", usable, custom = context(usable)))
        assertEquals(full30, resolved.config)
        assertNull(resolved.custom)
        // Automatic never picks a manual-only one.
        assertEquals(crop720at24, resolveRawVideoSelection("1920x816+1000+1100@24", usable, setOf(full30), context(usable))?.config)
        // Without a context nothing is judged; with nothing to fall back to, nothing is selected.
        assertEquals(full30, resolveRawVideoSelection("1920x816@24", usable + crop1080at24)?.config)
        assertNull(resolveRawVideoSelection("1920x816+1000+1100@24", listOf(crop720at24), setOf(crop720at24), context(listOf(crop720at24))))
        // A key that does not parse falls back too.
        assertEquals(full30, resolveRawVideoSelection("1920x817@24", usable, custom = context(usable))?.config)
    }

    @Test fun automaticNeverYieldsAWindowOfTheOperators() {
        // Only a risky full sensor and a window the sweep would vouch for: Automatic selects nothing.
        val usable = listOf(full24)
        assertNull(resolveRawVideoSelection("", usable, setOf(full24), context(usable, risky = setOf(full24), median = 2_000_000_000L)))
        assertEquals(full24, resolveRawVideoSelection("", usable, custom = context(usable, median = 2_000_000_000L))?.config)
        assertNull(resolveRawVideoSelection("", usable, custom = context(usable))?.custom)
    }

    @Test fun anOriginAtAQualifiedSizeKeepsThatSizesVerdict() {
        val usable = listOf(full24, crop1080at24)
        val placed = "1920x1080+0+0@24"
        assertEquals(RawVideoCustomVerdict.Passed(false, null), resolveRawVideoSelection(placed, usable, custom = context(usable))?.custom)
        assertEquals(RawVideoCustomVerdict.Passed(true, 21.3),
            resolveRawVideoSelection(placed, usable, setOf(crop1080at24), context(usable, reduced = mapOf(crop1080at24 to 21.3)))?.custom)
        // A risky size stays risky at any origin, with its record's burst.
        val risky = context(usable, risky = setOf(crop1080at24), burst = mapOf(crop1080at24 to 75L))
        val resolved = requireNotNull(resolveRawVideoSelection(placed, usable, setOf(crop1080at24), risky))
        assertEquals(RawVideoCustomVerdict.Risky(75), resolved.custom)
        assertEquals(RawVideoCrop(0, 0, 1920, 1080), resolved.crop)
        assertEquals(placed, resolved.key)
    }

    @Test fun customRatesAreTheUsableOnesAndThoseOnlyTheStorageLeftOut() {
        val usable = listOf(full30, full24, crop720at24, config(cameraId = "2", fps = 50))
        val leftOut = listOf(
            RawVideoLeftOutConfig(config(fps = 60), RawQualificationItemStatus.STORAGE_LIMIT),
            RawVideoLeftOutConfig(config(cropWidth = 1920, cropHeight = 1080, fps = 60), RawQualificationItemStatus.STORAGE_LIMIT),
            RawVideoLeftOutConfig(config(cropWidth = 1920, cropHeight = 1080, fps = 120), RawQualificationItemStatus.CAMERA_LIMIT),
            RawVideoLeftOutConfig(config(cropWidth = 1280, cropHeight = 720, fps = 90), RawQualificationItemStatus.SKIPPED_HEAT),
            RawVideoLeftOutConfig(config(cropWidth = 1920, cropHeight = 1080, fps = 240).copy(sensorWidth = 4000, sensorHeight = 3000),
                RawQualificationItemStatus.STORAGE_LIMIT),
        )
        assertEquals(listOf(24, 30, 60), rawVideoCustomRates("0", 4080, 3060, usable, leftOut))
        assertEquals(listOf(24, 30, 60), context(usable, leftOut = leftOut).customRates)
        assertEquals(emptyList<Int>(), rawVideoCustomRates("0", 4080, 3060, emptyList(), emptyList()))
    }

    // region PLAN-076 high-rate candidates

    private val crop720at90 = config(cropWidth = 1280, cropHeight = 720, fps = 90)
    private val crop720at120 = config(cropWidth = 1280, cropHeight = 720, fps = 120)
    private val window240at120 = config(cropWidth = 320, cropHeight = 240, fps = 120)

    @Test fun aUsableHighRateCropEnablesItsCustomRates() {
        val usable = listOf(full30, crop720at90, crop720at120)
        assertEquals(listOf(30, 90, 120), rawVideoCustomRates("0", 4080, 3060, usable, emptyList()))
        assertEquals(listOf(30, 90, 120), context(usable).customRates)
    }

    @Test fun aSmallWindowPassesUnderAUsableHighRateCrop() {
        assertEquals(RawVideoCustomVerdict.Passed(false, null), customVerdict(window240at120, listOf(full30, crop720at120)))
        assertEquals(RawVideoCustomVerdict.Passed(false, null), context(listOf(full30, crop720at120)).judge("320x240@120")?.custom)
    }

    @Test fun aSmallWindowIsRefusedWhenTheHighRateCropIsACameraLimit() {
        val leftOut = listOf(RawVideoLeftOutConfig(crop720at120, RawQualificationItemStatus.CAMERA_LIMIT))
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.CAMERA_LIMIT), customVerdict(window240at120, listOf(full30), leftOut = leftOut))
        assertEquals(emptyList<Int>(), rawVideoCustomRates("0", 4080, 3060, emptyList(), leftOut))
        // The record the qualifier stores for a rate the camera cannot be asked for reads as that left-out status.
        val records = listOf(verdict(full30, RawBenchmarkStatus.PASS),
            verdict(crop720at120, RawBenchmarkStatus.FAIL, RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT))
        assertEquals(leftOut, rawVideoLeftOutConfigs("0", sizes, records, "dest", listOf(full30)))
    }

    @Test fun recordsOfTheEightEarlierSpecsKeepTheGateAsItWas() {
        // A store written before PLAN-076 added 1280x720 at 90 and 120 fps: the Razr-like result, 60 fps the camera limit.
        val earlier = RawQualificationSpecs.DEFAULT_RAW10.filterNot { it.startsWith("1280x720@") }
        assertEquals(8, earlier.size)
        val configs = earlier.map { requireNotNull(RawQualificationSpecs.parse(it, "0", 4080, 3060)).config }
        val records = configs.map {
            if (it.fps == 60) verdict(it, RawBenchmarkStatus.FAIL, RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT)
            else verdict(it, RawBenchmarkStatus.PASS)
        }
        val passed = configs.filter { it.fps < 60 }
        val usable = rawVideoUsableConfigs("0", sizes, passed, codecAvailable = false)
        assertEquals(passed.sortedByDescending { it.streamBytesPerSecond }, usable)
        assertEquals(ModeGateState.AVAILABLE, rawVideoGate(sizes, usable, failedThisSession = false))
        assertEquals(full30, selectRawVideoConfig(usable, ""))
        assertEquals(full30, selectRawVideoConfig(usable, "1280x720@120"))
        // Nothing lists the new specs: no record, so neither failed nor left out, and no rate above 30 is offered.
        val leftOut = rawVideoLeftOutConfigs("0", sizes, records, "dest", usable)
        assertEquals(configs.filter { it.fps == 60 }.toSet(), leftOut.map { it.config }.toSet())
        assertTrue(leftOut.all { it.status == RawQualificationItemStatus.CAMERA_LIMIT })
        assertTrue(leftOut.none { it.config.fps > 60 })
        assertEquals(listOf(15, 24, 30), rawVideoCustomRates("0", 4080, 3060, usable, leftOut))
        assertEquals(RawVideoCustomVerdict.Refused(RawVideoLeftOutReason.NOT_QUALIFIED), customVerdict(window240at120, usable, leftOut = leftOut))
    }

    @Test fun automaticPrefersARateUpToSixtyAmongCrops() {
        assertEquals(crop60, selectRawVideoConfig(listOf(crop720at120, crop720at90, crop60), ""))
        // With only faster windows usable, the highest rate among them.
        assertEquals(crop720at120, selectRawVideoConfig(listOf(crop720at90, crop720at120), ""))
        // The full sensor still comes first.
        assertEquals(full24, selectRawVideoConfig(listOf(crop720at120, full24), ""))
    }

    // endregion

    @Test fun theCropPreviewReadsTheVerdictWithTheOptionsRates() {
        val usable = listOf(full24, crop1080at24, crop720at24)
        val passed = requireNotNull(rawVideoCropPreview(context(usable), "1920x816+1000+1100@24"))
        assertEquals(RawVideoCropPreview("1920x816+1000+1100@24", 47_001_600L, 47_001_600L, RawVideoCropStatus.PASSED), passed)
        // Not canonical in, canonical out.
        assertEquals("1920x816@24", rawVideoCropPreview(context(usable), "1920x816+1080+1122@24")?.key)
        val reduced = rawVideoCropPreview(context(listOf(crop1080at24), reduced = mapOf(crop1080at24 to 21.3)), "1920x816@24")
        assertEquals(21.3, reduced?.achievedFps)
        val refused = requireNotNull(rawVideoCropPreview(context(usable), "1920x816@60"))
        assertEquals(RawVideoCropStatus.REFUSED, refused.status)
        assertEquals(RawVideoLeftOutReason.NOT_QUALIFIED, refused.refusedReason)
        // A usable configuration at its centred origin reads its own record.
        val risky = requireNotNull(rawVideoCropPreview(context(usable, risky = setOf(crop1080at24), burst = mapOf(crop1080at24 to 75L)), "1920x1080@24"))
        assertEquals(RawVideoCropStatus.RISKY, risky.status)
        assertEquals(75L, risky.burstSeconds)
        assertEquals(RawVideoCropStatus.PASSED, rawVideoCropPreview(context(usable), "full@24")?.status)
        // A compressed window's capacity rate assumes the compression, as its option does.
        val bp = crop1080at24.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        val bpPreview = requireNotNull(rawVideoCropPreview(context(listOf(bp)), "1920x816+0+0@24+bp"))
        assertEquals(RawVideoConfigOption.of(custom.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)).capacityBytesPerSecond, bpPreview.capacityBytesPerSecond)
        assertNull(rawVideoCropPreview(context(usable), "1920x817@24"))
    }

    // endregion
}
