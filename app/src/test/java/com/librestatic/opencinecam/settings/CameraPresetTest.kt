/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.settings

import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.capture.OperatorAction
import com.librestatic.opencinecam.capture.OperatorPreferences
import com.librestatic.opencinecam.capture.VolumeKeyPair
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.fold.SubjectDisplayMode
import com.librestatic.opencinecam.fold.SubjectDisplaySettings
import com.librestatic.opencinecam.fold.SubjectPreviewGuide
import com.librestatic.opencinecam.fold.SubjectSlateField
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CameraPresetTest {
    private fun preset() = CameraPreset(name = "Noche", settings = CameraSettings(
        timecodeRememberPosition = false, timecodeResetRevision = 7, videoCodec = com.librestatic.opencinecam.media.video.VideoCodecPreference.H264,
        exposure = ExposureSelection(ExposureMode.MANUAL, 800, shutterUnit = ShutterUnit.ANGLE, angleTenths = 900),
        whiteBalance = WhiteBalanceSelection.Kelvin(4300, 17), recordingWhiteBalance = RecordingWhiteBalancePolicy.LOCK_ON_RECORD,
        imageProcessing = ImageProcessingSelection(StabilizationMode.VIDEO, IspMode.HIGH_QUALITY, IspMode.OFF),
        audioInputDeviceId = 12345, audioInputKey = com.librestatic.opencinecam.media.audio.AudioInputKey(11, "Private Lavalier", "card=2"),
        audioInputLossPolicy = com.librestatic.opencinecam.media.audio.AudioInputLossPolicy.CONTINUE_SILENT, subjectDisplay = SubjectDisplaySettings(prompterText = "private script", operatorCue = "private cue", brightness = 0.4f),
        modeSelectorStyle = ModeSelectorStyle.BUTTONS, videoFps = 60), focusDiopters = 2.5f, zoomRatio = 2f)
    private fun document(change: (MutableMap<String, JsonElement>) -> Unit): String {
        val map = Json.parseToJsonElement(CameraPresetCodec.encode(preset())).jsonObject.toMutableMap(); change(map); return JsonObject(map).toString()
    }
    private fun invalid(text: String) { assertThrows(Exception::class.java) { CameraPresetCodec.decode(text) } }
    // OCC-PLAN-068 subject preferences stay device-local until a schema revision and privacy review.
    private val subjectFeatureKeys = setOf("subject-preview-bands", "subject-preview-guide", "subject-preview-audio-meter",
        "subject-tally-border", "subject-giant-countdown", "subject-fill-kelvin", "subject-fill-tint", "subject-fill-timeout-seconds",
        "subject-interview-questions", "subject-slate-fields", "subject-slate-sync-flash", "subject-slate-sync-beep",
        "subject-out-of-frame", "subject-out-of-frame-delay")
    @Test fun completePortableSettingsRoundTripAndLocalValuesAreExcluded() {
        val original = preset(); val text = CameraPresetCodec.encode(original); val decoded = CameraPresetCodec.decode(text)
        assertEquals(original.settings, CameraPresetCodec.mergeLocal(decoded.settings, original.settings))
        assertNull(decoded.settings.audioInputDeviceId)
        assertEquals("", decoded.settings.subjectDisplay.prompterText)
        for (secret in listOf(original.id, "12345", "private script", "private cue", "audio-input-device-id", "Private Lavalier", "audio-input-key", "audio-input-loss-policy")) assertFalse(text.contains(secret))
        assertEquals(original.mode, decoded.mode); assertEquals(original.focusDiopters, decoded.focusDiopters); assertEquals(original.zoomRatio, decoded.zoomRatio)
        assertFalse(text.contains("timecode-remember-position")); assertFalse(text.contains("timecode-reset-revision"))
        assertEquals(189, CameraPresetCodec.portableKeys.size)
        assertFalse(text.contains("video-codec")); assertFalse(text.contains("proxy-max-long-edge")); assertFalse(text.contains("proxy-video-bitrate-mbps"))
        val memory = PresetPreferences(); CameraSettingsStore(memory).save(CameraSettings())
        assertEquals(memory.all.keys - setOf("audio-input-device-id", "audio-input-key", "audio-input-loss-policy", "audio-listening-output-device-id", "subject-script", "subject-cue", "audio-aac-log-migrated-v1", "mode-selector-carousel-migrated-v1", "recording-policy-migrated-v1", "recording-policy", "timecode-remember-position", "timecode-reset-revision", "geotagging-enabled", "proxy-max-long-edge", "proxy-video-bitrate-mbps", "log-grey-reference", "video-codec", "review-log-view", "review-native-surface", "review-raw-playback", "gyroflow-log-enabled", "zero-shutter-lag", "hdr-video-format", "low-light-boost", "image-saturation", "image-sharpness", "night-photo-mode", "video-low-light-fps", "video-fps-lock", "lens-correction-video", "lens-correction-photos", "lens-correction-alignment", "lens-lock", "smooth-lens-switch", "rotate-when-locked", "audio-sync-offset-ms", "still-sizes") - subjectFeatureKeys, CameraPresetCodec.portableKeys)
        println("PRESET_V20_KEYS=" + CameraPresetCodec.portableKeys.size)
    }
    @Test fun audioSyncOffsetStaysOnThisDeviceWhenAPresetIsApplied() {
        val text = CameraPresetCodec.encode(CameraPreset(name = "Look", settings = CameraSettings(audioSyncOffsetMs = 120)))
        assertFalse(text.contains("audio-sync-offset-ms")); assertFalse("audio-sync-offset-ms" in CameraPresetCodec.portableKeys)
        assertEquals(0, CameraPresetCodec.decode(text).settings.audioSyncOffsetMs)
        assertEquals(30, CameraPresetCodec.VERSION)
        val current = CameraSettings(audioSyncOffsetMs = -80)
        assertEquals(-80, CameraPresetCodec.mergeLocal(CameraPresetCodec.decode(text).settings, current).audioSyncOffsetMs)
    }
    @Test fun pictureProfileIsPortableFromV22AndSurvivesAPresetRoundTrip() {
        assertTrue("image-picture-profile" in CameraPresetCodec.portableKeys)
        assertFalse("image-picture-profile" in CameraPresetCodec.portableKeysFor(21))
        val text = CameraPresetCodec.encode(CameraPreset(name = "Cine", settings = CameraSettings(imageProcessing = ImageProcessingSelection(pictureProfile = PictureProfile.CINE))))
        assertEquals(PictureProfile.CINE, CameraPresetCodec.decode(text).settings.imageProcessing.pictureProfile)
        assertEquals(PictureProfile.CINE, CameraPresetCodec.mergeLocal(CameraPresetCodec.decode(text).settings, CameraSettings()).imageProcessing.pictureProfile)
    }
    @Test fun pictureControlsStayOnThisDeviceWhenAPresetIsApplied() {
        val current = CameraSettings(imageProcessing = com.librestatic.opencinecam.camera.ImageProcessingSelection(saturation = 8, sharpness = 4))
        val text = CameraPresetCodec.encode(CameraPreset(name = "Look", settings = current))
        assertFalse(text.contains("image-saturation")); assertFalse(text.contains("image-sharpness"))
        val applied = CameraPresetCodec.mergeLocal(CameraPresetCodec.decode(text).settings, current)
        assertEquals(8, applied.imageProcessing.saturation); assertEquals(4, applied.imageProcessing.sharpness)
    }
    @Test fun hapticKeysArePortableFromV23AndRoundTrip() {
        val keys = setOf("haptic-level", "haptic-capture", "haptic-controls", "haptic-focus-exposure", "haptic-navigation", "haptic-mute-during-take")
        assertTrue(CameraPresetCodec.portableKeys.containsAll(keys))
        assertTrue(CameraPresetCodec.portableKeysFor(22).none { it in keys })
        assertTrue(CameraPresetCodec.portableKeysFor(23).containsAll(keys))
        val haptics = HapticPreferences(HapticLevel.FULL, capture = false, navigation = false, muteDuringTake = false)
        val text = CameraPresetCodec.encode(CameraPreset(name = "Haptic", settings = CameraSettings(haptics = haptics)))
        assertEquals(haptics, CameraPresetCodec.decode(text).settings.haptics)
    }
    @Test fun v22PayloadWithoutHapticKeysStillDecodesToDefaults() {
        val v21 = document {
            it["version"] = JsonPrimitive(22)
            it["settings"] = JsonObject(it.getValue("settings").jsonObject.filterKeys { key -> key in CameraPresetCodec.portableKeysFor(22) })
        }
        assertEquals(HapticPreferences(), CameraPresetCodec.decode(v21).settings.haptics)
    }
    @Test fun rawVideoConfigIsPortableFromV24AndRoundTrips() {
        assertTrue("raw-video-config" in CameraPresetCodec.portableKeys)
        assertFalse("raw-video-config" in CameraPresetCodec.portableKeysFor(23))
        assertTrue("raw-video-config" in CameraPresetCodec.portableKeysFor(24))
        val text = CameraPresetCodec.encode(CameraPreset(name = "Raw", settings = CameraSettings(rawVideoConfig = "1920x1080@60"), mode = CaptureMode.RAW_VIDEO))
        val decoded = CameraPresetCodec.decode(text)
        assertEquals("1920x1080@60", decoded.settings.rawVideoConfig)
        assertEquals("1920x1080@60", CameraPresetCodec.mergeLocal(decoded.settings, CameraSettings()).rawVideoConfig)
    }
    @Test fun aPlacedRawVideoWindowRoundTripsWithoutAVersionBump() {
        assertEquals(30, CameraPresetCodec.VERSION)
        val key = "1920x816+1080+1122@24+bp"
        val text = CameraPresetCodec.encode(CameraPreset(name = "Raw", settings = CameraSettings(rawVideoConfig = key), mode = CaptureMode.RAW_VIDEO))
        val decoded = CameraPresetCodec.decode(text)
        assertEquals(key, decoded.settings.rawVideoConfig)
        assertEquals(key, CameraPresetCodec.mergeLocal(decoded.settings, CameraSettings()).rawVideoConfig)
    }
    @Test fun v23PayloadWithoutRawVideoConfigDecodesToTheAutomaticChoice() {
        val v23 = document {
            it["version"] = JsonPrimitive(23)
            it["settings"] = JsonObject(it.getValue("settings").jsonObject.filterKeys { key -> key in CameraPresetCodec.portableKeysFor(23) })
        }
        assertEquals("", CameraPresetCodec.decode(v23).settings.rawVideoConfig)
        // A V23 payload that already carries the V24 key is not a V23 payload.
        invalid(document { it["version"] = JsonPrimitive(23) })
    }
    @Test fun scrollLongTextIsPortableFromV25AndRoundTrips() {
        assertTrue("scroll-long-text" in CameraPresetCodec.portableKeys)
        assertFalse("scroll-long-text" in CameraPresetCodec.portableKeysFor(24))
        assertTrue("scroll-long-text" in CameraPresetCodec.portableKeysFor(25))
        val text = CameraPresetCodec.encode(CameraPreset(name = "Still", settings = CameraSettings(scrollLongText = false)))
        assertTrue(text.contains("\"version\":${CameraPresetCodec.VERSION}"))
        val decoded = CameraPresetCodec.decode(text)
        assertFalse(decoded.settings.scrollLongText)
        assertFalse(CameraPresetCodec.mergeLocal(decoded.settings, CameraSettings()).scrollLongText)
    }
    @Test fun v24PayloadWithoutScrollLongTextDecodesToTheDefault() {
        val v24 = document {
            it["version"] = JsonPrimitive(24)
            it["settings"] = JsonObject(it.getValue("settings").jsonObject.filterKeys { key -> key in CameraPresetCodec.portableKeysFor(24) })
        }
        assertTrue(CameraPresetCodec.decode(v24).settings.scrollLongText)
        // A V24 payload that already carries the V25 key is not a V24 payload.
        invalid(document { it["version"] = JsonPrimitive(24) })
    }
    @Test fun rawVideoNearLosslessIsPortableFromV26AndRoundTrips() {
        assertTrue("raw-video-near-lossless" in CameraPresetCodec.portableKeys)
        assertFalse("raw-video-near-lossless" in CameraPresetCodec.portableKeysFor(25))
        assertTrue("raw-video-near-lossless" in CameraPresetCodec.portableKeysFor(26))
        val text = CameraPresetCodec.encode(CameraPreset(name = "Raw", settings = CameraSettings(rawVideoNearLossless = false)))
        assertTrue(text.contains("\"version\":${CameraPresetCodec.VERSION}"))
        val decoded = CameraPresetCodec.decode(text)
        assertFalse(decoded.settings.rawVideoNearLossless)
        assertFalse(CameraPresetCodec.mergeLocal(decoded.settings, CameraSettings()).rawVideoNearLossless)
    }
    @Test fun v25PayloadWithoutRawVideoNearLosslessDecodesToTheDefault() {
        val v25 = document {
            it["version"] = JsonPrimitive(25)
            it["settings"] = JsonObject(it.getValue("settings").jsonObject.filterKeys { key -> key in CameraPresetCodec.portableKeysFor(25) })
        }
        assertTrue(CameraPresetCodec.decode(v25).settings.rawVideoNearLossless)
        // A V25 payload that already carries the V26 key is not a V25 payload.
        invalid(document { it["version"] = JsonPrimitive(25) })
    }
    @Test fun volumeKeyGesturesArePortableFromV27AndRoundTrip() {
        val keys = setOf("operator-volume-up-long", "operator-volume-down-long", "operator-volume-up-double", "operator-volume-down-double",
            "operator-volume-both", "operator-volume-pair", "operator-volume-pair-inverted")
        assertTrue(CameraPresetCodec.portableKeys.containsAll(keys))
        assertTrue(CameraPresetCodec.portableKeysFor(26).none { it in keys })
        assertTrue(CameraPresetCodec.portableKeysFor(27).containsAll(keys))
        val op = OperatorPreferences(volumeUpLong = OperatorAction.CAPTURE, volumeDownDouble = OperatorAction.TORCH, volumeBoth = OperatorAction.CONTROL_LOCK,
            volumePair = VolumeKeyPair.ZOOM, volumePairInverted = true)
        val text = CameraPresetCodec.encode(CameraPreset(name = "Keys", settings = CameraSettings(operation = op)))
        assertTrue(text.contains("\"version\":${CameraPresetCodec.VERSION}"))
        assertEquals(op, CameraPresetCodec.decode(text).settings.operation)
        // A gesture can never carry the system volume, not even through a crafted preset.
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + ("operator-volume-both" to JsonPrimitive("SYSTEM_VOLUME"))) })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + ("operator-volume-pair" to JsonPrimitive("WARP"))) })
    }
    @Test fun v26PayloadWithoutVolumeGesturesDecodesToTheDefaults() {
        val v26 = document {
            it["version"] = JsonPrimitive(26)
            it["settings"] = JsonObject(it.getValue("settings").jsonObject.filterKeys { key -> key in CameraPresetCodec.portableKeysFor(26) })
        }
        val operation = CameraPresetCodec.decode(v26).settings.operation
        assertEquals(VolumeKeyPair.NONE, operation.volumePair)
        assertTrue(operation.volumeGestures.all { it == OperatorAction.NONE })
        // A V26 payload that already carries the V27 keys is not a V26 payload.
        invalid(document { it["version"] = JsonPrimitive(26) })
    }
    @Test fun slowMotionTakeLengthIsPortableFromV28AndRoundTrips() {
        assertTrue("slow-motion-take-length-s" in CameraPresetCodec.portableKeys)
        assertFalse("slow-motion-take-length-s" in CameraPresetCodec.portableKeysFor(27))
        assertTrue("slow-motion-take-length-s" in CameraPresetCodec.portableKeysFor(28))
        val text = CameraPresetCodec.encode(CameraPreset(name = "Slow 10", settings = CameraSettings(videoOffSpeed = true, slowMotionTakeLengthSeconds = 10)))
        assertTrue(text.contains("\"version\":${CameraPresetCodec.VERSION}"))
        assertEquals(10, CameraPresetCodec.decode(text).settings.slowMotionTakeLengthSeconds)
        assertEquals(10, CameraPresetCodec.mergeLocal(CameraPresetCodec.decode(text).settings, CameraSettings()).slowMotionTakeLengthSeconds)
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + ("slow-motion-take-length-s" to JsonPrimitive(7))) })
    }
    @Test fun v27PayloadWithoutTheSlowMotionTakeLengthDecodesToUnbounded() {
        val v27 = document {
            it["version"] = JsonPrimitive(27)
            it["settings"] = JsonObject(it.getValue("settings").jsonObject.filterKeys { key -> key in CameraPresetCodec.portableKeysFor(27) })
        }
        assertEquals(0, CameraPresetCodec.decode(v27).settings.slowMotionTakeLengthSeconds)
        // A V27 payload that already carries the V28 key is not a V27 payload.
        invalid(document { it["version"] = JsonPrimitive(27) })
    }
    @Test fun equaliserCurveAndProfileArePortableFromV29AndRoundTrip() {
        val keys = setOf("audio-eq-curve", "audio-eq-profile-id")
        assertTrue(CameraPresetCodec.portableKeys.containsAll(keys))
        assertTrue(CameraPresetCodec.portableKeysFor(28).none { it in keys })
        assertTrue(CameraPresetCodec.portableKeysFor(29).containsAll(keys))
        // A full curve is longer than the 128 characters other strings may carry.
        val curve = EqCurve(points = List(EqCurve.MAX_POINTS) { EqPoint(EqFilterType.BELL, 100.0 + it * 500.0, -3.5, 1.25) }, trimDb = -1.5)
        val text = CameraPresetCodec.encode(CameraPreset(name = "Razr", settings = CameraSettings(audioEqCurve = curve, audioEqProfileId = "bundled-motorola-blanc")))
        assertTrue(text.contains("\"version\":${CameraPresetCodec.VERSION}"))
        assertTrue(curve.toJson().length > 128)
        val decoded = CameraPresetCodec.decode(text).settings
        assertEquals(curve, decoded.audioEqCurve)
        assertEquals("bundled-motorola-blanc", decoded.audioEqProfileId)
        assertEquals(curve, CameraPresetCodec.mergeLocal(decoded, CameraSettings()).audioEqCurve)
        // No profile travels as an empty string and comes back as none.
        assertNull(CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name = "Flat", settings = CameraSettings()))).settings.audioEqProfileId)
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + ("audio-eq-profile-id" to JsonPrimitive("../escape"))) })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + ("audio-eq-curve" to JsonPrimitive("x".repeat(4_097)))) })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + ("audio-eq-curve" to JsonPrimitive("not a curve"))) })
    }
    @Test fun v28PayloadWithoutTheEqualiserDecodesFlatWithNoProfile() {
        val v28 = document {
            it["version"] = JsonPrimitive(28)
            it["settings"] = JsonObject(it.getValue("settings").jsonObject.filterKeys { key -> key in CameraPresetCodec.portableKeysFor(28) })
        }
        val settings = CameraPresetCodec.decode(v28).settings
        assertEquals(EqCurve(), settings.audioEqCurve)
        assertNull(settings.audioEqProfileId)
        // A V28 payload that already carries the V29 keys is not a V28 payload.
        invalid(document { it["version"] = JsonPrimitive(28) })
    }
    @Test fun photoSizeAndRestoreArePortableFromV30AndThePerCameraSizesAreNot() {
        val keys = setOf("photo-width", "photo-height", "operator-restore-photo")
        assertEquals(30, CameraPresetCodec.VERSION)
        assertTrue(CameraPresetCodec.portableKeys.containsAll(keys))
        assertTrue(CameraPresetCodec.portableKeysFor(29).none { it in keys })
        assertTrue(CameraPresetCodec.portableKeysFor(30).containsAll(keys))
        assertFalse("still-sizes" in CameraPresetCodec.portableKeys)
        val settings = CameraSettings(photoWidth = 4000, photoHeight = 3000, stillSizes = mapOf("0:JPEG" to (4000 to 3000), "1:HEIC" to (1920 to 1080)),
            operation = OperatorPreferences(restorePhotoSettings = false))
        val text = CameraPresetCodec.encode(CameraPreset(name = "Stills", settings = settings, mode = CaptureMode.PHOTO))
        assertTrue(text.contains("\"version\":30"))
        assertFalse(text.contains("still-sizes")); assertFalse(text.contains("0:JPEG"))
        val decoded = CameraPresetCodec.decode(text).settings
        assertEquals(4000 to 3000, decoded.photoWidth to decoded.photoHeight)
        assertFalse(decoded.operation.restorePhotoSettings)
        assertTrue(decoded.stillSizes.isEmpty())
        // The camera's largest travels as 0 by 0; a half-set or negative size is not canonical.
        assertEquals(0 to 0, CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name = "Largest", settings = CameraSettings()))).settings
            .let { it.photoWidth to it.photoHeight })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + ("photo-width" to JsonPrimitive(4000))) })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + ("photo-height" to JsonPrimitive(-1))) })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + ("still-sizes" to JsonPrimitive("0:JPEG=4000x3000"))) })
    }
    @Test fun v29PayloadWithoutThePhotoSizeDecodesToTheLargestAndRestoring() {
        val v29 = document {
            it["version"] = JsonPrimitive(29)
            it["settings"] = JsonObject(it.getValue("settings").jsonObject.filterKeys { key -> key in CameraPresetCodec.portableKeysFor(29) })
        }
        val settings = CameraPresetCodec.decode(v29).settings
        assertEquals(0 to 0, settings.photoWidth to settings.photoHeight)
        assertTrue(settings.operation.restorePhotoSettings)
        // A V29 payload that already carries the V30 keys is not a V29 payload.
        invalid(document { it["version"] = JsonPrimitive(29) })
    }
    @Test fun applyingAPresetWithAnotherPhotoSizeClearsThePerCameraSizes() {
        val local = mapOf("0:JPEG" to (2000 to 1500), "1:HEIC" to (1920 to 1080))
        val current = CameraSettings(photoWidth = 2000, photoHeight = 1500, stillSizes = local)
        val other = CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name = "Big", settings = CameraSettings(photoWidth = 4000, photoHeight = 3000)))).settings
        val applied = CameraPresetCodec.mergeLocal(other, current)
        assertEquals(4000 to 3000, applied.photoWidth to applied.photoHeight)
        assertTrue(applied.stillSizes.isEmpty())
        assertEquals(4000 to 3000, applied.stillSizeFor("0", StillPhotoFormat.JPEG))
        // The same photo size keeps what this device remembers per camera.
        val same = CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name = "Same", settings = CameraSettings(photoWidth = 2000, photoHeight = 1500)))).settings
        assertEquals(local, CameraPresetCodec.mergeLocal(same, current).stillSizes)
    }
    @Test fun malformedRawVideoConfigIsRejectedNotNormalised() {
        for (bad in listOf("full", "4080x3060", "full@0", "0x10@30", "full@30 ", "FULL@30", "1920x1080@60@60")) {
            invalid(document { map ->
                map["settings"] = JsonObject(map.getValue("settings").jsonObject + ("raw-video-config" to JsonPrimitive(bad)))
            })
        }
        val store = CameraSettingsStore(PresetPreferences(mapOf("raw-video-config" to "nonsense")))
        assertEquals("", store.load().rawVideoConfig)
        for (good in listOf("", "full@30", "full@60", "1920x1080@60")) {
            val memory = PresetPreferences(); CameraSettingsStore(memory).save(CameraSettings(rawVideoConfig = good))
            assertEquals(good, CameraSettingsStore(memory).load().rawVideoConfig)
        }
    }
    @Test fun splitPreviewKeysArePortableAndSurviveAPresetRoundTrip() {
        assertTrue(CameraPresetCodec.portableKeys.containsAll(setOf("subject-split-preview-modes", "subject-fill-light-preview-level")))
        assertFalse("subject-split-preview-modes" in CameraPresetCodec.portableKeysFor(20))
        val subject = SubjectDisplaySettings(splitPreviewModes = setOf(SubjectDisplayMode.SLATE), fillLightPreviewLevel = 25)
        val text = CameraPresetCodec.encode(CameraPreset(name = "Split", settings = CameraSettings(subjectDisplay = subject)))
        assertEquals(subject.splitPreviewModes, CameraPresetCodec.decode(text).settings.subjectDisplay.splitPreviewModes)
        assertEquals(25, CameraPresetCodec.decode(text).settings.subjectDisplay.fillLightPreviewLevel)
        val applied = CameraPresetCodec.mergeLocal(CameraPresetCodec.decode(text).settings, CameraSettings())
        assertEquals(subject.splitPreviewModes, applied.subjectDisplay.splitPreviewModes)
        assertEquals(25, applied.subjectDisplay.fillLightPreviewLevel)
    }
    @Test fun subjectFeaturePreferencesAreNeitherExportedNorResetByAPreset() {
        val local = SubjectDisplaySettings(interviewQuestions = listOf("private question"), fillLightKelvin = 3200, tallyBorder = false,
            slateFields = setOf(SubjectSlateField.TIMECODE), outOfFrameWarning = true, previewGuide = SubjectPreviewGuide.THIRDS)
        val current = CameraSettings(subjectDisplay = local)
        val text = CameraPresetCodec.encode(CameraPreset(name = "Subject", settings = current))
        assertFalse(text.contains("private question"))
        for (key in subjectFeatureKeys) assertFalse(key, text.contains(key))
        val applied = CameraPresetCodec.mergeLocal(CameraPresetCodec.decode(text).settings, current.copy(subjectDisplay = local.copy(mode = SubjectDisplayMode.SLATE)))
        assertEquals(local, applied.subjectDisplay)
    }
    @Test fun versionOneMigratesMissingSettingsAndCaptureIntentWithoutAppPreferenceMigrations() {
        val text = """{"format":"OpenCineCamPreset","version":1,"name":"Draft","settings":{"zebra-enabled":true,"audio-output-format":"WAV_PCM","mode-selector-style":"BUTTONS"}}"""
        val decoded = CameraPresetCodec.decode(text)
        assertTrue(decoded.settings.zebraEnabled)
        assertEquals(com.librestatic.opencinecam.media.audio.AudioOutputFormat.WAV_PCM, decoded.settings.audioOutputFormat)
        assertEquals(ModeSelectorStyle.BUTTONS, decoded.settings.modeSelectorStyle)
        assertEquals(RecordingWhiteBalancePolicy.CONTINUOUS, decoded.settings.recordingWhiteBalance)
        assertEquals(CaptureMode.VIDEO, decoded.mode); assertEquals(1f, decoded.zoomRatio); assertNull(decoded.focusDiopters)
    }
    @Test fun unknownVersionFieldsAndPhysicalRoutingAreRejected() {
        invalid(document { it["version"] = JsonPrimitive(999) })
        invalid(document { it["version"] = JsonPrimitive("2") })
        invalid(document { it["credential"] = JsonPrimitive("token") })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + ("audio-input-device-id" to JsonPrimitive(7))) })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject - "video-geometry-fps") })
    }
    @Test fun invalidTypesRangesEnumsAndPartialCanonicalFieldsAreRejected() {
        for ((key,value) in listOf("video-geometry-fps" to JsonPrimitive(-1), "video-geometry-width" to JsonPrimitive(999999),
            "exposure-iso" to JsonPrimitive("800"), "image-edge-enhancement" to JsonPrimitive("MAGIC"), "subject-brightness" to JsonPrimitive(2))) {
            invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + (key to value)) })
        }
        invalid(document { it["name"] = JsonPrimitive(" ") })
        invalid(document { it["name"] = JsonPrimitive("a".repeat(65)) })
        invalid(document { it["zoomRatio"] = JsonPrimitive(-1) })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + mapOf("timecode-nominalfps" to JsonPrimitive(24), "timecode-dropframe" to JsonPrimitive(true))) })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + ("timecode-startframes" to JsonPrimitive(59))) })
    }
    @Test fun duplicateKeysIncludingEscapedNamesAndNestedArrayBombAreRejected() {
        val canonical = CameraPresetCodec.encode(preset())
        val version = "\"version\":${CameraPresetCodec.VERSION}"
        assertTrue("Duplicate-key fixture must find the actual version field", canonical.contains(version))
        val duplicate = canonical.replace(version, "$version,\"version\":1")
        assertNotEquals(canonical, duplicate)
        invalid(duplicate)
        invalid(CameraPresetCodec.encode(preset()).replace("\"name\":", "\"na\\u006de\":\"Duplicate\",\"name\":"))
        invalid("[".repeat(10000) + "]".repeat(10000))
        invalid(" ".repeat(CameraPresetCodec.MAX_BYTES + 1))
    }
    @Test fun boundedInputRejectsTooMuchDataAndInvalidUtf8() {
        assertThrows(Exception::class.java) { readPresetDocument(java.io.ByteArrayInputStream(ByteArray(CameraPresetCodec.MAX_BYTES + 1))) }
        assertThrows(Exception::class.java) { readPresetDocument(java.io.ByteArrayInputStream(byteArrayOf(0xC3.toByte()))) }
        assertEquals("{}", readPresetDocument(java.io.ByteArrayInputStream("\uFEFF{}".toByteArray())))
    }
    @Test fun differencesAreExplicitAndPrivateValuesNeverBecomePresetChanges() {
        val changes = CameraPresetCodec.differences(CameraSettings(), preset().settings)
        assertTrue(changes.any { it.startsWith("exposure-iso:") && it.contains("800") })
        assertFalse(changes.any { it.contains("script") || it.contains("device-id") })
        assertEquals(listOf("camera"), preset().compatibilityIssues(CameraUiState()))
    }
    private class Memory(var raw: String? = null) : PresetPersistence {
        var failWrite = false
        override fun read() = raw
        override fun write(value: String) { check(!failWrite); raw = value }
    }
    @Test fun librarySaveUpdateRenameDeleteAndSlotsSurviveRecreation() {
        val memory = Memory(); val repository = PresetRepository(memory); val p = preset()
        repository.save(p); repository.assign("C1", p.id); repository.assign("C2", p.id)
        repository.save(p.copy(name = "Otra"))
        assertEquals(repository.states.value, PresetRepository(memory).states.value)
        repository.delete(p.id)
        assertTrue(repository.states.value.presets.isEmpty()); assertTrue(repository.states.value.slots.isEmpty())
    }
    @Test fun duplicateNamesLimitsAndFailedWritesDoNotMutateLibrary() {
        val memory = Memory(); val repository = PresetRepository(memory); val p = preset(); repository.save(p)
        assertThrows(Exception::class.java) { repository.save(p.copy(id = "different", name = "NOCHE")) }
        val before = repository.states.value
        memory.failWrite = true
        assertThrows(Exception::class.java) { repository.delete(p.id) }
        assertEquals(before, repository.states.value)
        memory.failWrite = false
        repeat(31) { repository.save(CameraPreset(name = "Preset $it", settings = CameraSettings())) }
        assertThrows(Exception::class.java) { repository.save(CameraPreset(name = "Overflow", settings = CameraSettings())) }
    }
    @Test fun unreadableLibraryIsRetainedUntilExplicitReset() {
        val memory = Memory("broken"); val repository = PresetRepository(memory)
        assertNotNull(repository.states.value.error)
        assertThrows(Exception::class.java) { repository.save(preset()) }
        assertEquals("broken", memory.raw)
        repository.reset(); assertNull(PresetRepository(memory).states.value.error)
    }
    @Test fun structuralPresetValuesStayPendingDuringRecording() {
        val current = CameraSettings(recordingWhiteBalance = RecordingWhiteBalancePolicy.LOCK_ON_RECORD)
        val preset = preset().settings.copy(zebraEnabled = true, videoWidth = 3840)
        val effective = current.withLivePreferencesFrom(preset)
        assertEquals(current.videoWidth, effective.videoWidth)
        assertEquals(current.whiteBalance, effective.whiteBalance)
        assertEquals(current.imageProcessing, effective.imageProcessing)
        assertEquals(preset.exposure, effective.exposure); assertTrue(effective.zebraEnabled)
    }
}

// Keys newer than preset V19 (V20 to V29), so the historical-document tests can strip them.
internal val PRESET_V20_KEYS = setOf("photo-width", "photo-height", "operator-restore-photo", "audio-eq-curve", "audio-eq-profile-id", "slow-motion-take-length-s", "operator-volume-up-long", "operator-volume-down-long", "operator-volume-up-double", "operator-volume-down-double", "operator-volume-both", "operator-volume-pair", "operator-volume-pair-inverted", "translucent-chrome", "viewfinder-scale", "chrome-opacity", "subject-split-preview-modes", "subject-fill-light-preview-level", "image-picture-profile", "haptic-level", "haptic-capture", "haptic-controls", "haptic-focus-exposure", "haptic-navigation", "haptic-mute-during-take", "raw-video-config", "scroll-long-text", "raw-video-near-lossless")
