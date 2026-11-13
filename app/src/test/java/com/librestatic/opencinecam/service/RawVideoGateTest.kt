/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.RawVideoConfigKeys
import com.librestatic.opencinecam.RawVideoConfigOption
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
}
