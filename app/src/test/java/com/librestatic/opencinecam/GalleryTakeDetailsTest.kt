/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.exifinterface.media.ExifInterface
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawFrameIndex
import com.librestatic.opencinecam.media.ocraw.OcrawHeader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamFrame
import com.librestatic.opencinecam.media.ocraw.OcrawTimeBase
import com.librestatic.opencinecam.playback.ClipMetadata
import com.librestatic.opencinecam.storage.LocalMediaArtifact
import com.librestatic.opencinecam.storage.LocalMediaKind
import com.librestatic.opencinecam.storage.parseOcLogSidecar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTakeDetailsTest {
    private val text = DetailText({ id, args ->
        when (id) {
            R.string.media_detail_requested -> "${args[0]} (requested ${args[1]})"
            R.string.media_detail_bits -> "${args[0]}-bit"
            R.string.media_detail_yes -> "Yes"
            R.string.media_detail_no -> "No"
            R.string.media_detail_on -> "On"
            R.string.media_detail_off -> "Off"
            R.string.media_detail_take_complete -> "Complete"
            R.string.media_detail_take_incomplete -> "Incomplete"
            R.string.media_detail_stereo -> "Stereo"
            R.string.media_detail_mono -> "Mono"
            R.string.media_detail_range_full -> "Full"
            R.string.media_detail_range_limited -> "Limited"
            R.string.media_detail_auto -> "Auto"
            R.string.media_detail_flash_not_fired -> "Did not fire"
            R.string.media_detail_hardware_encoder -> "Hardware"
            R.string.media_detail_wb_kelvin -> "Kelvin"
            R.string.media_detail_audio_separate_wav -> "Separate file (WAV)"
            R.string.recording_policy_adaptive -> "Adaptive"
            R.string.video_mode -> "Video"
            else -> "#$id"
        }
    }, { "$it B" }, { "t$it" })

    private fun <T> known(value: T?): T { assertNotNull(value); return value!! }
    private fun TakeTechnicalDetails.value(key: String): String? = groups.flatMap { it.rows }.firstOrNull { it.key == key }?.value
    private fun TakeTechnicalDetails.groupOf(key: String): DetailGroupKind? = groups.firstOrNull { g -> g.rows.any { it.key == key } }?.kind

    @Test fun formatsShutterWithAngleAndLongExposures() {
        assertEquals("1/48 s (180°)", shutterLabel(20_833_333, 24.0))
        assertEquals("1/48 s", shutterLabel(20_833_333))
        assertEquals("1/100 s (108°)", shutterLabel(10_000_000, 30.0))
        assertEquals("1.5 s", shutterLabel(1_500_000_000, 30.0))
        assertEquals("0.5 s", shutterLabel(500_000_000))
        assertEquals("1/100 s – 1/50 s", shutterRangeLabel(10_000_000, 20_000_000))
    }

    @Test fun formatsRatesIsoFocusAndBitrate() {
        assertEquals("23.976 fps", fpsLabel(24000.0 / 1001))
        assertEquals("30 fps", fpsLabel(30.0))
        assertEquals("29.97 fps", fpsLabel(29.97))
        assertEquals("29.97 fps (requested 30 fps)", text.requested(fpsLabel(29.97), fpsLabel(30.0)))
        assertEquals("30 fps", text.requested(fpsLabel(30.0), fpsLabel(30.0)))
        assertEquals("ISO 800", isoLabel(800))
        assertEquals("ISO 400–1600", isoLabel(400, 1600))
        assertEquals("2.00 m", focusDistanceLabel(0.5))
        assertEquals("∞", focusDistanceLabel(0.0))
        assertEquals("45.2 Mbps", bitrateLabel(45_240_000.0))
        assertEquals("212.4 MB/s", dataRateLabel(212_400_000.0))
        assertEquals("6.3 mm (24 mm eq.)", focalLabel(6.3, 24.0))
        assertEquals("f/1.8", apertureLabel(1.8))
        assertEquals("48 kHz", sampleRateLabel(48_000))
        assertEquals("44.1 kHz", sampleRateLabel(44_100))
        assertEquals("1×–2.5×", zoomLabel(1.0, 2.5))
        assertEquals("1.7:1", compressionLabel(1.7))
    }

    @Test fun joinsPathsAndOmitsUnknownOnes() {
        assertEquals("/storage/emulated/0/DCIM/OpenCineCam/OCC_TAKE_x/A001.mp4",
            artifactPath("/storage/emulated/0", "DCIM/OpenCineCam/OCC_TAKE_x/", "A001.mp4"))
        assertEquals("/storage/emulated/0/Documents/RAW/a.ocraw", artifactPath("/storage/emulated/0/", "Documents/RAW", "a.ocraw"))
        assertNull(artifactPath("/storage/emulated/0", null, "a.mp4"))
        assertNull(artifactPath(null, "DCIM/", "a.mp4"))
    }

    @Test fun fileRowsCarryPathOrName() {
        val video = LocalMediaArtifact("content://v/1", "A001.mp4", "video/mp4", 2048, 100)
        val audio = LocalMediaArtifact("content://a/2", "A001.wav", "audio/wav", 0, 0)
        val details = buildTakeDetails(TakeDetailInputs(LocalMediaKind.VIDEO,
            listOf(DetailFile(video, "/storage/emulated/0/DCIM/A001.mp4"), DetailFile(audio, null))), text)
        val file = details.group(DetailGroupKind.FILE)!!.rows
        val path = file.first { it.key == "path-0" }
        assertTrue(path.copyable)
        assertEquals("/storage/emulated/0/DCIM/A001.mp4", path.value)
        assertEquals("2048 B", details.value("size-0"))
        assertEquals("t100000", details.value("modified-0"))
        assertEquals("A001.wav", details.value("name-1"))
        assertNull(details.value("size-1"))
        assertEquals(listOf(DetailGroupKind.FILE), details.groups.map { it.kind })
    }

    @Test fun parsesAnOldClipSidecarWithoutCamera() {
        val clip = known(parseClipSidecarFacts(clipJson(camera = null)))
        assertNull(clip.camera)
        assertEquals("ADAPTIVE", clip.recordingPolicy)
        assertEquals(30.0, clip.fps!!, 0.0)
        assertNull(clip.encoderName)
        val probe = ClipMetadata(3840, 2160, 29.97, "HEVC 10-bit", 10, "SDR · BT.709", 10_000_000)
        val video = LocalMediaArtifact("content://v/1", "A001.mp4", "video/mp4", 50_000_000, 100)
        val details = buildTakeDetails(TakeDetailInputs(LocalMediaKind.VIDEO, listOf(DetailFile(video, null)), clip = clip, video = probe), text)
        assertEquals("29.97 fps (requested 30 fps)", details.value("fps"))
        assertEquals("4K 3840×2160", details.value("resolution"))
        assertEquals("HEVC 10-bit", details.value("codec"))
        assertEquals("40.0 Mbps", details.value("bitrate"))
        assertEquals("50 Mbps", details.value("bitrate-target"))
        assertEquals("0:10", details.value("duration"))
        assertEquals("299", details.value("frames"))
        assertEquals("Hardware", details.value("encoder-type"))
        assertEquals("SDR · BT.709", details.value("colour"))
        assertEquals("48 kHz", details.value("sample-rate"))
        assertEquals("Separate file (WAV)", details.value("audio-storage"))
        assertEquals("Adaptive", details.value("recording-policy"))
        assertEquals("Video", details.value("capture-mode"))
        assertEquals("#${R.string.media_detail_colour_sdr}", details.value("transfer"))
        assertEquals("0", details.value("camera-id"))
        assertEquals("Complete", details.value("take-status"))
        assertEquals("On", details.value("timecode"))
        assertEquals("blanc · VERIFIED", details.value("device-profile"))
        assertEquals("t1762600000000", details.value("created"))
        assertNull(details.value("iso"))
        assertEquals(listOf("camera-id"), details.group(DetailGroupKind.CAMERA)!!.rows.map { it.key })
        assertEquals(DetailGroupKind.RECORDING, details.groupOf("capture-mode"))
    }

    @Test fun parsesTheCameraBlockOfANewClipSidecar() {
        val camera = """{"knowledge":"known","value":{"cameraId":"2","focalLengthMm":6.3,"focalLength35mm":24.0,"aperture":1.8,
            "exposureMode":{"requested":"SHUTTER","reported":"MANUAL"},"iso":{"start":400,"end":800,"min":400,"max":1600},
            "exposureTimeNs":{"start":20833333,"end":20833333,"min":20833333,"max":20833333},
            "whiteBalance":{"mode":"KELVIN","kelvin":5600,"reportedCctStart":5400,"reportedCctEnd":5600,"tint":3},
            "focus":{"afMode":"OFF","distanceDiopters":0.5},"zoom":{"start":1.0,"end":2.5},
            "stabilization":{"ois":"ON","eis":"OFF"},"processing":{"noiseReduction":"FAST","edge":"OFF","toneMap":"CONTRAST_CURVE"}}}"""
        val clip = known(parseClipSidecarFacts(clipJson(camera = camera, fps = 24, requestedFps = 24)))
        val details = buildTakeDetails(TakeDetailInputs(LocalMediaKind.VIDEO, clip = clip), text)
        assertEquals("2", details.value("camera-id"))
        assertEquals("6.3 mm (24 mm eq.)", details.value("focal"))
        assertEquals("f/1.8", details.value("aperture"))
        assertEquals("MANUAL (requested SHUTTER)", details.value("exposure-mode"))
        assertEquals("ISO 400–1600", details.value("iso"))
        assertEquals("1/48 s (180°)", details.value("shutter"))
        assertEquals("Kelvin · 5600 K", details.value("wb-mode"))
        assertEquals("5400–5600 K", details.value("wb-cct"))
        assertEquals("+3", details.value("wb-tint"))
        assertEquals("2.00 m", details.value("focus-distance"))
        assertEquals("1×–2.5×", details.value("zoom"))
        assertEquals("On", details.value("ois"))
        assertEquals("Off", details.value("eis"))
        assertEquals("OFF", details.value("af-mode"))
        assertEquals("CONTRAST_CURVE", details.value("tone-map"))
        assertEquals("24 fps", details.value("fps"))
        assertEquals(DetailGroupKind.CAMERA, details.groupOf("shutter"))
    }

    @Test fun mapsSidecarEnumValuesToWordsAndLeavesUnknownOnesAsWritten() {
        assertEquals(R.string.recording_policy_strict, detailWord(DetailEnum.RECORDING_POLICY, "STRICT"))
        assertEquals(R.string.raw_video_mode, detailWord(DetailEnum.CAPTURE_MODE, "RAW_VIDEO"))
        assertEquals(R.string.hlg_mode, detailWord(DetailEnum.CAPTURE_MODE, "HLG"))
        assertEquals(R.string.media_detail_colour_hlg, detailWord(DetailEnum.COLOUR_KIND, "HLG"))
        assertEquals(R.string.media_detail_colour_oclog, detailWord(DetailEnum.COLOUR_KIND, "OCLog2"))
        assertEquals(R.string.media_detail_auto, detailWord(DetailEnum.WHITE_BALANCE, "AUTO"))
        assertEquals(R.string.media_detail_audio_separate_flac, detailWord(DetailEnum.AUDIO_STORAGE, "SEPARATE_FLAC"))
        assertEquals(R.string.media_detail_audio_embedded_aac, detailWord(DetailEnum.AUDIO_STORAGE, "EMBEDDED_AAC"))
        assertEquals(R.string.media_detail_eis_preview, detailWord(DetailEnum.STABILIZATION, "PREVIEW_STABILIZATION"))
        assertNull(detailWord(DetailEnum.WHITE_BALANCE, "DAYLIGHT"))
        assertNull(detailWord(DetailEnum.CAPTURE_MODE, "video"))
        assertEquals("DAYLIGHT", text.word(DetailEnum.WHITE_BALANCE, "DAYLIGHT"))
        assertEquals("Kelvin", text.word(DetailEnum.WHITE_BALANCE, "KELVIN"))
        assertNull(text.word(DetailEnum.WHITE_BALANCE, null))
    }

    @Test fun incompleteTakesListTheirReasonsAndUnknownValuesAreOmitted() {
        val clip = known(parseClipSidecarFacts(clipJson(camera = null, incomplete = true)))
        val details = buildTakeDetails(TakeDetailInputs(LocalMediaKind.VIDEO, clip = clip), text)
        assertEquals("Incomplete · native-not-retired", details.value("take-status"))
        assertNull(parseClipSidecarFacts("""{"schemaVersion":"9.0.0"}"""))
        assertNull(parseClipSidecarFacts("not json"))
    }

    @Test fun hlgSidecarGivesTheColourRows() {
        val hdr = known(parseHdrVideoSidecar("""{"schema":"opencinecam.hdr-video.v1","hdr":{"transfer":"HLG","transferStandard":"ARIB STD-B67",
            "primaries":"BT.2020","range":"limited","sourceDynamicRange":"HDR","codecName":"HEVC","codecProfile":"Main10"}}"""))
        val details = buildTakeDetails(TakeDetailInputs(LocalMediaKind.VIDEO, hdr = hdr,
            video = ClipMetadata(1920, 1080, 30.0, "HEVC 10-bit", 10, "HLG · BT.2020", null)), text)
        assertEquals("HLG (ARIB STD-B67)", details.value("transfer"))
        assertEquals("BT.2020", details.value("gamut"))
        assertEquals("Limited", details.value("range"))
        assertEquals("Main10", details.value("codec-profile"))
        assertNull("the sidecar's rows replace the probe's summary", details.value("colour"))
        assertNull(parseHdrVideoSidecar("""{"schema":"opencinecam-oclog-sidecar-v2"}"""))
    }

    @Test fun oclogSidecarGivesTheColourRows() {
        val log = known(parseOcLogSidecar("""{"schema":"opencinecam-oclog-sidecar-v2","videoUri":"content://v/1",
            "transform":{"curve":"OCLog2","gamut":"BT.2020","version":"2.1"},"encoding":{"range":"full","codecName":"HEVC","profile":"Main10"}}""",
            "content://v/1"))
        val details = buildTakeDetails(TakeDetailInputs(LocalMediaKind.VIDEO, log = log), text)
        assertEquals("OCLog2 2.1", details.value("transfer"))
        assertEquals("BT.2020", details.value("gamut"))
        assertEquals("Full", details.value("range"))
        assertEquals(DetailGroupKind.COLOUR, details.groupOf("range"))
    }

    @Test fun mapsExifThroughAGetter() {
        val tags = mapOf(
            ExifInterface.TAG_PIXEL_X_DIMENSION to "4000", ExifInterface.TAG_PIXEL_Y_DIMENSION to "3000",
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "800", ExifInterface.TAG_EXPOSURE_TIME to "0.01",
            ExifInterface.TAG_F_NUMBER to "1.8", ExifInterface.TAG_FOCAL_LENGTH to "6300/1000",
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to "24", ExifInterface.TAG_WHITE_BALANCE to "0",
            ExifInterface.TAG_FLASH to "16", ExifInterface.TAG_MAKE to "motorola", ExifInterface.TAG_MODEL to "razr 50 ultra",
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:11:08 10:15:00", ExifInterface.TAG_COLOR_SPACE to "1",
            ExifInterface.TAG_BITS_PER_SAMPLE to "16", ExifInterface.TAG_CFA_PATTERN to "2,0,2,0,0,1,1,2",
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE to "-1/3",
        )
        val rows = DetailRowsBuilder().apply { addExifRows(tags::get, text) }.build()
        assertEquals("4000×3000 (12 MP)", rows.value("resolution"))
        assertEquals("ISO 800", rows.value("iso"))
        assertEquals("1/100 s", rows.value("shutter"))
        assertEquals("f/1.8", rows.value("aperture"))
        assertEquals("6.3 mm (24 mm eq.)", rows.value("focal"))
        assertEquals("Auto", rows.value("wb-mode"))
        assertEquals("Did not fire", rows.value("flash"))
        assertEquals("motorola razr 50 ultra", rows.value("device"))
        assertEquals("2026-11-08 10:15:00", rows.value("taken"))
        assertEquals("sRGB", rows.value("colour-space"))
        assertEquals("16-bit", rows.value("bit-depth"))
        assertEquals("RGGB", rows.value("cfa"))
        assertEquals("-0.3 EV", rows.value("exposure-bias"))
        assertEquals(DetailGroupKind.VIDEO, rows.groupOf("resolution"))
        assertNull(DetailRowsBuilder().apply { addExifRows({ null }, text) }.build().groups.firstOrNull())
    }

    @Test fun summarisesAnOcrawTakeWithAGapATornTailAndMixedEncodings() {
        val numbers = listOf(0L, 1L, 2L, 4L, 5L)
        val frames = numbers.mapIndexed { i, number ->
            OcrawStreamFrame(i.toLong(), i * 100L, i.toLong(), OcrawFrameHeader(width = 4, height = 2, rowBytes = 5, cropLeft = 0, cropTop = 0,
                sensorTimestampNs = 1_000_000_000L + i * 40_000_000L, frameNumber = number, dataLength = 10,
                encoding = if (i < 3) OcrawFrameHeader.ENCODING_RAW10 else OcrawFrameHeader.ENCODING_LJ92))
        }
        val metadata = mapOf("fps" to "30/1", "orientation" to "90", "cfa" to "RGGB", "blackLevel" to "64 64 64 64", "whiteLevel" to "1023",
            "cameraId" to "0", "model" to "razr 50 ultra", "appVersion" to "1.1.0", "fingerprint" to "motorola/blanc", "focalLength" to "6.3",
            "aperture" to "1.7", "audioSampleRate" to "48000", "audioChannels" to "2", "audioSource" to "CAMCORDER", "audioDroppedSamples" to "0")
        val index = OcrawFrameIndex(OcrawHeader(ByteArray(16)), fileSize = 25, validEnd = 20, tornTail = true, frames = frames,
            metadata = metadata, frameMetadata = mapOf(0L to mapOf("iso" to "400", "exposureNs" to "20000000"),
                3L to mapOf("iso" to "800", "exposureNs" to "10000000")), timeBase = OcrawTimeBase(1, 1_000_000_000), hasEnd = false,
            errors = emptyList())
        val raw = OcrawSummary.from(metadata, index, sizeBytes = 25)
        assertEquals(2, raw.width); assertEquals(4, raw.height)
        assertEquals(1L, raw.missingFrames)
        assertFalse(raw.closedCleanly)
        assertEquals("RAW10+LJ92", raw.encoding)
        assertEquals(25.0, raw.measuredFps!!, 1e-9)
        assertEquals(160_000L + 33_333L, raw.durationUs)
        assertEquals(2.0, raw.compressionRatio!!, 1e-9)
        val details = buildTakeDetails(TakeDetailInputs(LocalMediaKind.RAW_VIDEO, raw = raw), text)
        assertEquals("25 fps (requested 30 fps)", details.value("fps"))
        assertEquals("5", details.value("frames"))
        assertEquals("90°", details.value("orientation"))
        assertEquals("1", details.value("missing-frames"))
        assertEquals("No", details.value("closed-cleanly"))
        assertEquals("2.0:1", details.value("compression"))
        assertEquals("ISO 400–800", details.value("iso"))
        assertEquals("1/100 s (108°) – 1/50 s (216°)", details.value("shutter"))
        assertEquals("10-bit", details.value("raw-bit-depth"))
        assertEquals("Stereo", details.value("channels"))
        assertEquals("6.3 mm", details.value("focal"))
        assertTrue(details.group(DetailGroupKind.RECORDING)!!.rows.first { it.key == "fingerprint" }.copyable)
        assertEquals(listOf(DetailGroupKind.VIDEO, DetailGroupKind.AUDIO, DetailGroupKind.CAMERA, DetailGroupKind.RAW, DetailGroupKind.RECORDING),
            details.groups.map { it.kind })
        val facts = known(details.facts)
        assertEquals(193L, facts.durationMs)
        assertEquals(2, facts.width)
        assertEquals("RAW10", facts.codec?.name)
    }

    @Test fun aCleanOcrawTakeHasNoGaps() {
        val frames = List(3) { i -> OcrawStreamFrame(i.toLong(), i * 100L, i.toLong(), OcrawFrameHeader(4, 2, 5, 0, 0,
            i * 33_333_333L, i.toLong(), 10)) }
        val index = OcrawFrameIndex(OcrawHeader(ByteArray(16)), 0, 0, false, frames, emptyMap(), emptyMap(), null, true, emptyList())
        val raw = OcrawSummary.from(emptyMap(), index, 0)
        assertEquals(0L, raw.missingFrames)
        assertTrue(raw.closedCleanly)
        assertNull(raw.bytesPerSecond)
        assertNull(raw.compressionRatio)
    }

    @Test fun aFailingSectionDropsOnlyItsRows() {
        val rows = DetailRowsBuilder()
        rows.section { add(DetailGroupKind.FILE, R.string.media_detail_name, "a", "name-0") }
        rows.section { add(DetailGroupKind.VIDEO, R.string.media_detail_codec, "HEVC", "codec"); error("boom") }
        rows.section { add(DetailGroupKind.FILE, R.string.media_detail_name, "b", "name-0"); add(DetailGroupKind.AUDIO, R.string.media_detail_codec, "", "x") }
        val built = rows.build()
        assertEquals(listOf(DetailGroupKind.FILE), built.groups.map { it.kind })
        assertEquals("a", built.value("name-0"))
    }

    @Test fun mergesFactsPreferringTheKnownOnes() {
        val store = GalleryFacts(durationMs = null, width = 1920, height = null)
        val file = GalleryFacts(durationMs = 5000, width = 4080, height = 3060, codec = CodecBadge("LJ92"))
        assertEquals(GalleryFacts(5000, 1920, 3060, codec = CodecBadge("LJ92")), store.mergedWith(file))
        assertEquals(file, null.mergedWith(file))
        assertEquals(store, store.mergedWith(null))
    }

    private fun clipJson(camera: String?, fps: Int = 30, requestedFps: Int = 30, incomplete: Boolean = false): String = """
        {"schemaVersion":"1.0.0","recordingId":"r1","createdAt":"2025-11-08T11:06:40Z",
         "intent":{"recordingPolicy":{"knowledge":"known","value":"ADAPTIVE"},"captureMode":{"knowledge":"known","value":"VIDEO"},
           "requestedSize":{"knowledge":"known","value":{"width":3840,"height":2160}},"requestedFps":{"knowledge":"known","value":$requestedFps},
           "videoCodecPreference":{"knowledge":"known","value":"HEVC"},"videoBitrateMbps":{"knowledge":"known","value":50},
           "audio":{"enabled":{"knowledge":"known","value":true},"requestedFormat":{"knowledge":"known","value":"WAV"},"sampleRateHz":48000,"channels":2},
           "timecodeEnabled":{"knowledge":"known","value":true}},
         "resolvedGraph":{"cameraId":{"knowledge":"known","value":"0"},"route":{"knowledge":"unknown"},
           "recordingRoute":{"knowledge":"known","value":"MediaCodec"},
           "video":{"codecMime":{"knowledge":"known","value":"video/hevc"},"encoderName":{"knowledge":"unknown"},
             "hardwareAccelerated":{"knowledge":"known","value":true},"softwareEncoder":false,
             "encodedSize":{"knowledge":"known","value":{"width":3840,"height":2160}},"fps":{"knowledge":"known","value":$fps},
             "containerRotationDegrees":{"knowledge":"unknown"}},
           "color":{"knowledge":"known","value":{"kind":"SDR_BT709"}},
           "audio":{"storage":"SEPARATE_WAV","container":"WAV","sampleRateHz":48000,"bitDepth":24},
           "deviceProfile":{"knowledge":"known","value":{"profileId":"blanc","status":"ACTIVE","appliedQuirkIds":[],"buildCheck":"VERIFIED"}}
           ${if (camera != null) ",\"camera\":$camera" else ""}},
         "events":[],"validation":[],"media":{"displayName":"A001.mp4","durationMs":10000,"encodedFrames":299}
         ${if (incomplete) ",\"takeStatus\":\"incomplete\",\"failureReasons\":[\"native-not-retired\"]" else ""}}
    """.trimIndent()
}
