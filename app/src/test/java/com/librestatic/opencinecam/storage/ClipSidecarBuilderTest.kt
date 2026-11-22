/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.JsonSchemaSubset
import com.librestatic.opencinecam.core.model.ClipSidecarDocument
import com.librestatic.opencinecam.core.model.decodeClipSidecar
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipSidecarBuilderTest {
    /** Gradle runs app host tests with the module directory as the working directory. */
    private val schema = Json.parseToJsonElement(File("../docs/schemas/clip-sidecar.schema.json").readText()) as JsonObject
    private val id = "123e4567-e89b-42d3-a456-426614174000"
    private val started = Instant.parse("2026-10-22T10:15:30.250Z")

    private val intent = ClipIntent(
        recordingPolicy = "STRICT", captureMode = "VIDEO", requestedWidth = 3840, requestedHeight = 2160, requestedFps = 30,
        videoCodecPreference = "AUTO", videoBitrateMbps = 80, audioEnabled = true, audioFormat = "WAV", audioSampleRateHz = 48_000,
        audioBitDepth = 24, audioChannels = 1, lutSelectionId = "abc:KIND:IN:OUT", timecodeEnabled = true,
    )
    private val sdrGraph = ClipGraph(
        cameraId = "0", routeTruth = "PHYSICAL_CONFIRMED", requestedRoute = "5", reportedRoute = "5", recorder = "MediaRecorder",
        codecMime = "video/hevc", encodedWidth = 3840, encodedHeight = 2160, fps = 30, colorKind = "SDR_BT709",
        audioStorage = "SEPARATE_WAV", audioContainer = "WAV", audioSampleRateHz = 48_000, audioBitDepth = 24,
        deviceProfile = ClipDeviceProfile("razr-fold-0", "ACTIVE", listOf("OCC-QUIRK-0001"), "NOT_VERIFIED_ON_THIS_BUILD"),
    )
    private fun facts(graph: ClipGraph = sdrGraph, success: Boolean = true, retired: Boolean = true, intent: ClipIntent? = this.intent,
        pauses: List<ClipPause> = emptyList(), warnings: List<ClipWarning> = emptyList()) = ClipTakeFacts(
        startedAt = started, intent = intent, graph = graph, pauses = pauses, warnings = warnings, stopReason = "storage",
        durationMs = 12_500, encodedFrames = 375, success = success, nativeRetired = retired,
        audioFiles = listOf("take.wav", "take.audio.json"),
    )
    private fun context(verified: ClipStepStatus = ClipStepStatus.PASS, reasons: List<String>? = null, metadata: String? = "take.timing.json") =
        ClipSidecarPrepareContext(id, "take.mp4", metadata, verified, incompleteReasons = reasons, observedAt = Instant.parse("2026-10-22T10:16:00Z"))

    private fun encoded(facts: ClipTakeFacts, context: ClipSidecarPrepareContext): String =
        clipSidecarProvider { facts }.build(context)!!

    private fun assertValid(json: String): ClipSidecarDocument {
        assertEquals(emptyList<String>(), JsonSchemaSubset.violations(schema, Json.parseToJsonElement(json)))
        return decodeClipSidecar(json)
    }

    @Test fun completeTakeIsValidAndDescribesIntentAndGraph() {
        val document = assertValid(encoded(facts(), context()))
        assertEquals("1.2.0", document.schemaVersion)
        assertEquals(id, document.recordingId)
        assertEquals("2026-10-22T10:15:30.250Z", document.createdAt)
        assertEquals("STRICT", document.intent.getValue("recordingPolicy").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals("known", document.resolvedGraph.getValue("cameraId").jsonObject.getValue("knowledge").jsonPrimitive.content)
        assertEquals(listOf("take-started", "take-stopped"), document.events.map { it.getValue("code").jsonPrimitive.content })
        assertEquals(listOf("Recorded", "FileVerified"), document.validation.map { it.stage })
        assertEquals(listOf("pass", "pass"), document.validation.map { it.status })
        assertEquals("MediaRecorder", document.validation.first().source)
        assertNull(document.takeStatus)
        assertNull(document.failureReasons)
        val media = document.media!!
        assertEquals("take.mp4", media.getValue("displayName").jsonPrimitive.content)
        assertEquals(12_500L, media.getValue("durationMs").jsonPrimitive.content.toLong())
        assertEquals(listOf("take.timing.json", "take.audio.json"), media.getValue("sidecars").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("take.wav"), media.getValue("audioFiles").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun incompleteTakeCarriesTheMarkerAnAnIncompleteEventPerReasonAndSkippedFileCheck() {
        val reasons = listOf("encoder-failed", "muxer-not-finalized", "encoder-failed")
        val document = assertValid(encoded(facts(success = false, retired = false), context(ClipStepStatus.NOT_RUN, reasons)))
        assertEquals("incomplete", document.takeStatus)
        assertEquals(listOf("encoder-failed", "muxer-not-finalized"), document.failureReasons)
        val incomplete = document.events.filter { it.getValue("code").jsonPrimitive.content.startsWith("incomplete:") }
        assertEquals(listOf("incomplete:encoder-failed", "incomplete:muxer-not-finalized"), incomplete.map { it.getValue("code").jsonPrimitive.content })
        assertTrue(incomplete.all { it.getValue("severity").jsonPrimitive.content == "error" })
        val stopped = document.events.single { it.getValue("code").jsonPrimitive.content == "take-stopped" }
        assertEquals("error", stopped.getValue("severity").jsonPrimitive.content)
        assertEquals(listOf("fail", "notRun"), document.validation.map { it.status })
    }

    @Test fun failedGeometryCheckIsRecordedAsAFailedFileVerification() {
        val document = assertValid(encoded(facts(), context(ClipStepStatus.FAIL).copy(fileVerifiedDetail = "size differs")))
        assertEquals("fail", document.validation.last().status)
        assertEquals("size differs", document.validation.last().details!!.getValue("message").jsonPrimitive.content)
    }

    @Test fun logTakeNamesTheMediaCodecRouteAndTheColour() {
        val graph = sdrGraph.copy(
            recorder = "MediaCodec", codecMime = "video/hevc", encoderName = "c2.qti.hevc.encoder", colorKind = "OCLog2",
            colorTransfer = "OCLog2", colorGamut = "BT.2020", colorProfile = "Main10", containerRotationDegrees = 90,
        )
        val document = assertValid(encoded(facts(graph), context(metadata = "take.oclog.json")))
        val color = document.resolvedGraph.getValue("color").jsonObject.getValue("value").jsonObject
        assertEquals("OCLog2", color.getValue("kind").jsonPrimitive.content)
        assertEquals("BT.2020", color.getValue("gamut").jsonPrimitive.content)
        assertEquals("MediaCodec", document.validation.first().source)
        assertEquals("MediaCodec", document.resolvedGraph.getValue("recordingRoute").jsonObject.getValue("value").jsonPrimitive.content)
        val video = document.resolvedGraph.getValue("video").jsonObject
        assertEquals("c2.qti.hevc.encoder", video.getValue("encoderName").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals(90, video.getValue("containerRotationDegrees").jsonObject.getValue("value").jsonPrimitive.content.toInt())
    }

    @Test fun hlgTakeIsValid() {
        val graph = sdrGraph.copy(recorder = "MediaCodec", colorKind = "HLG", colorTransfer = "HLG", colorGamut = "BT.2020", colorProfile = "Main10")
        val color = assertValid(encoded(facts(graph), context())).resolvedGraph.getValue("color").jsonObject.getValue("value").jsonObject
        assertEquals("HLG", color.getValue("kind").jsonPrimitive.content)
    }

    @Test fun softwareEncoderTakeIsDisclosedAsNotHardwareAccelerated() {
        val graph = sdrGraph.copy(codecMime = "video/avc", encoderName = "c2.android.avc.encoder", softwareEncoder = true, hardwareAccelerated = true)
        val warnings = listOf(ClipWarning("software-video-encoder", message = "c2.android.avc.encoder"))
        val document = assertValid(encoded(facts(graph, warnings = warnings), context()))
        val video = document.resolvedGraph.getValue("video").jsonObject
        assertEquals(true, video.getValue("softwareEncoder").jsonPrimitive.content.toBoolean())
        // A software encoder is never reported as hardware, whatever the caller passed.
        assertEquals("false", video.getValue("hardwareAccelerated").jsonObject.getValue("value").jsonPrimitive.content)
        val warning = document.events.single { it.getValue("code").jsonPrimitive.content == "software-video-encoder" }
        assertEquals("warning", warning.getValue("severity").jsonPrimitive.content)
        assertEquals("take-end", warning.getValue("timeBasis").jsonPrimitive.content)
    }

    @Test fun pausesBecomeOrderedEventsAndUnknownsStayUnknown() {
        val bare = facts(ClipGraph(), intent = null, pauses = listOf(ClipPause(2_000_000, 3_500_000), ClipPause(9_000_000, null)))
        val document = assertValid(encoded(bare, context(metadata = null)))
        assertEquals("unknown", document.intent.getValue("knowledge").jsonPrimitive.content)
        assertEquals("unknown", document.resolvedGraph.getValue("cameraId").jsonObject.getValue("knowledge").jsonPrimitive.content)
        assertEquals("unknown", document.resolvedGraph.getValue("deviceProfile").jsonObject.getValue("knowledge").jsonPrimitive.content)
        val timeline = document.events.map { it.getValue("code").jsonPrimitive.content to it.getValue("timeUs").jsonPrimitive.content.toLong() }
        assertEquals(listOf("take-started" to 0L, "pause" to 2_000_000L, "resume" to 3_500_000L, "pause" to 9_000_000L, "take-stopped" to 12_500_000L), timeline)
        assertFalse(document.media!!.getValue("sidecars").jsonArray.contains(JsonPrimitive("null")))
    }

    @Test fun cameraSettingsAreWrittenUnderTheResolvedGraph() {
        val camera = ClipCameraSettings(
            cameraId = "0", focalLengthMm = 6.3f, focalLength35mm = 24.1f, aperture = 1.7f,
            requestedExposureMode = "AUTO", reportedExposureMode = "AUTO",
            iso = ClipSampledRange(400, 800, 320, 1250), exposureTimeNs = ClipSampledRange(20_833_333L, 20_833_333L, 10_000_000L, 33_333_333L),
            whiteBalanceMode = "KELVIN", whiteBalanceKelvin = 5600, reportedCctStartK = 5400, reportedCctEndK = 5450, whiteBalanceTint = 0,
            afMode = "CONTINUOUS_VIDEO", focusDistanceDiopters = 0.5f, zoom = ClipSampledRange(1f, 2f, 1f, 2f),
            opticalStabilization = "ON", videoStabilization = "OFF", noiseReduction = "FAST", edge = "FAST", toneMap = "FAST",
        )
        val block = assertValid(encoded(facts(sdrGraph.copy(camera = camera)), context())).resolvedGraph.getValue("camera").jsonObject
        assertEquals("known", block.getValue("knowledge").jsonPrimitive.content)
        val expected = Json.parseToJsonElement("""
            {"cameraId":"0","focalLengthMm":6.3,"focalLength35mm":24.1,"aperture":1.7,
             "exposureMode":{"requested":"AUTO","reported":"AUTO"},
             "iso":{"start":400,"end":800,"min":320,"max":1250},
             "exposureTimeNs":{"start":20833333,"end":20833333,"min":10000000,"max":33333333},
             "whiteBalance":{"mode":"KELVIN","kelvin":5600,"reportedCctStart":5400,"reportedCctEnd":5450,"tint":0},
             "focus":{"afMode":"CONTINUOUS_VIDEO","distanceDiopters":0.5},
             "zoom":{"start":1.0,"end":2.0},
             "stabilization":{"ois":"ON","eis":"OFF"},
             "processing":{"noiseReduction":"FAST","edge":"FAST","toneMap":"FAST"}}
        """)
        // Compared by value, so the writer may print 1.0 as 1 or 1.0.
        assertEquals(numbersOf(expected), numbersOf(block.getValue("value")))
    }

    @Test fun cameraSettingsLeaveOutWhatWasNotReported() {
        val camera = ClipCameraSettings(cameraId = "2", iso = ClipSampledRange(100, 100, 100, 100), afMode = "3")
        val value = assertValid(encoded(facts(sdrGraph.copy(camera = camera)), context()))
            .resolvedGraph.getValue("camera").jsonObject.getValue("value").jsonObject
        assertEquals(setOf("cameraId", "iso", "focus"), value.keys)
        assertEquals(setOf("afMode"), value.getValue("focus").jsonObject.keys)
    }

    @Test fun cameraSettingsAreUnknownWhenNothingWasSampled() {
        listOf(null, ClipCameraSettings()).forEach { camera ->
            val block = assertValid(encoded(facts(sdrGraph.copy(camera = camera)), context())).resolvedGraph.getValue("camera").jsonObject
            assertEquals("unknown", block.getValue("knowledge").jsonPrimitive.content)
            assertFalse("value" in block)
        }
    }

    /** Every number in [element] keyed by its path, as a double, so `1` and `1.0` compare equal. */
    private fun numbersOf(element: kotlinx.serialization.json.JsonElement, path: String = ""): Map<String, Any> = when (element) {
        is JsonObject -> element.entries.flatMap { (key, child) -> numbersOf(child, "$path.$key").entries }.associate { it.key to it.value }
        is JsonPrimitive -> mapOf(path to (if (element.isString) element.content else element.content.toDouble()))
        else -> emptyMap()
    }

    @Test fun aContinuationNamesTheTakeItContinues() {
        // PLAN-072 Delivery 4: the phone part links to the drive part, whose sidecar is never rewritten.
        val parent = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val link = ClipContinuation(parent, "OCC_$parent.mp4", 1, "drive-disconnected")
        val json = encoded(facts().copy(continuation = link), context())
        val continuation = assertValid(json).continuation!!
        assertEquals(parent, continuation.getValue("of").jsonPrimitive.content)
        assertEquals("OCC_$parent.mp4", continuation.getValue("previousName").jsonPrimitive.content)
        assertEquals(1, continuation.getValue("index").jsonPrimitive.content.toInt())
        assertEquals("drive-disconnected", continuation.getValue("reason").jsonPrimitive.content)
        assertValid(encoded(facts().copy(continuation = link.copy(reason = "drive-write-failed")), context()))
    }

    @Test fun anOrdinaryTakeHasNoContinuationAndTheSchemaRejectsABadOne() {
        val json = encoded(facts(), context())
        assertFalse("continuation" in Json.parseToJsonElement(json).jsonObject)
        assertNull(assertValid(json).continuation)
        val bad = JsonObject(Json.parseToJsonElement(json).jsonObject + ("continuation" to JsonObject(mapOf(
            "of" to JsonPrimitive("not-a-uuid"), "previousName" to JsonPrimitive("x.mp4"), "index" to JsonPrimitive(1),
            "reason" to JsonPrimitive("operator")))))
        assertEquals(2, JsonSchemaSubset.violations(schema, bad).size)
        assertTrue(runCatching { ClipContinuation("a", "b", 0, "c") }.isFailure)
    }

    @Test fun nameOfTheSidecarFollowsTheVideoStem() {
        assertEquals("OCC_take.clip.json", clipSidecarName("OCC_take.mp4"))
    }

    @Test fun embeddedAudioRecordsItsInputPathAndEqualiser() {
        val curve = com.librestatic.opencinecam.camera.EqCurve(
            points = listOf(com.librestatic.opencinecam.camera.EqPoint.coerced(com.librestatic.opencinecam.camera.EqFilterType.HIGH_PASS, 80.0, 0.0, 0.7071)))
        val embedded = sdrGraph.copy(audioStorage = "EMBEDDED_AAC", audioContainer = null, audioSampleRateHz = null, audioBitDepth = null,
            audioInput = com.librestatic.opencinecam.camera.PcmInputEvidence("LOW_LATENCY", "AUDIO_RECORD", "external-input", 16, curve))
        val audio = assertValid(encoded(facts(embedded), context())).resolvedGraph.getValue("audio").jsonObject
        assertEquals("EMBEDDED_AAC", audio.getValue("storage").jsonPrimitive.content)
        assertEquals("LOW_LATENCY", audio.getValue("source").jsonPrimitive.content)
        assertEquals("AUDIO_RECORD", audio.getValue("inputPath").jsonPrimitive.content)
        assertEquals("external-input", audio.getValue("inputFallbackReason").jsonPrimitive.content)
        assertEquals(16, audio.getValue("captureBitDepth").jsonPrimitive.content.toInt())
        assertEquals(curve, com.librestatic.opencinecam.camera.decodeEqCurve(audio.getValue("equaliser").jsonObject))
        // A curve without a profile (a custom one) names none.
        assertFalse("equaliserProfileId" in audio)
        val profiled = embedded.copy(audioInput = embedded.audioInput!!.copy(equaliserProfileId = "bundled-blanc"))
        val named = assertValid(encoded(facts(profiled), context())).resolvedGraph.getValue("audio").jsonObject
        assertEquals("bundled-blanc", named.getValue("equaliserProfileId").jsonPrimitive.content)

        val flat = embedded.copy(audioInput = com.librestatic.opencinecam.camera.PcmInputEvidence("CAMCORDER", "AUDIO_RECORD", null, 16, null))
        val plain = assertValid(encoded(facts(flat), context())).resolvedGraph.getValue("audio").jsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, plain.getValue("inputFallbackReason"))
        assertEquals(kotlinx.serialization.json.JsonNull, plain.getValue("equaliser"))
        assertFalse("equaliserProfileId" in plain)
        // Without evidence (a separate file, or an engine that did not report) the block is unchanged.
        assertFalse("inputPath" in assertValid(encoded(facts(), context())).resolvedGraph.getValue("audio").jsonObject)
    }

    private fun summary(realtime: Boolean = true, synced: Boolean = true, estimated: Boolean = false, camm: Boolean = true,
        attached: Boolean = true, magnetometer: Boolean = false) = com.librestatic.opencinecam.motion.MotionTakeSummary(
        info = com.librestatic.opencinecam.motion.MotionTakeInfo(
            takeId = "take-1", videoFileName = "take.mp4", orientation = "yxz", readoutDirection = 0, frontFacing = false,
            sensorOrientationDegrees = 90, storedRotationDegrees = 0, cameraClockRealtime = realtime, clockOffsetNs = 0L,
            startedAtUnixMs = 1_700_000_000_000L, firmware = "test", route = com.librestatic.opencinecam.motion.MotionTakeRoute.GPU,
            captureFps = 30.0, gyroMaxHz = 500.0, accelerometer = true, accelMaxHz = 400.0, magnetometer = magnetometer,
            magMaxHz = if (magnetometer) 100.0 else null,
        ),
        result = com.librestatic.opencinecam.motion.MotionRenderResult(
            gcsvRows = 6_000, gyroSamples = 6_225, accelSamples = 4_980, magSamples = if (magnetometer) 1_250 else 0,
            measuredGyroHz = 498.23456, measuredAccelHz = 398.4, measuredMagHz = if (magnetometer) 99.81 else null,
            largestGyroGapNs = 4_180_999L, globalRateScale = 0.1250000004, fileStartUs = 0L, fileEndUs = 12_466_000L,
        ),
        synced = synced, estimated = estimated, cammAppended = camm, cammPackets = if (camm) 6_233 else 0,
        cammBinUs = if (camm) 2_000L else null, gcsvAttached = attached,
    )

    private fun motionOf(document: ClipSidecarDocument) = document.motion!!

    @Test fun motionBlockDescribesSensorsSyncCammAndLog() {
        val motion = clipMotion(summary(magnetometer = true), "take.gcsv")
        val document = assertValid(encoded(facts(), context().copy(motion = motion)))
        val block = motionOf(document)
        val sensors = block.getValue("sensors").jsonObject
        val gyro = sensors.getValue("gyroscope").jsonObject
        assertEquals("true", gyro.getValue("present").jsonPrimitive.content)
        assertEquals(500.0, gyro.getValue("nominalMaxHz").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(498.23, gyro.getValue("measuredHz").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(6_225, gyro.getValue("samples").jsonPrimitive.content.toInt())
        assertEquals(99.81, sensors.getValue("magnetometer").jsonObject.getValue("measuredHz").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("measured", block.getValue("sync").jsonPrimitive.content)
        assertEquals(0.125, block.getValue("rateScale").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(4_180L, block.getValue("largestGyroGapUs").jsonPrimitive.content.toLong())
        val camm = block.getValue("camm").jsonObject
        assertEquals("true", camm.getValue("present").jsonPrimitive.content)
        assertEquals(6_233, camm.getValue("packets").jsonPrimitive.content.toInt())
        assertEquals(2_000L, camm.getValue("binUs").jsonPrimitive.content.toLong())
        assertEquals("take.gcsv", block.getValue("log").jsonPrimitive.content)
        assertEquals(listOf("take.timing.json", "take.audio.json", "take.gcsv"),
            document.media!!.getValue("sidecars").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun motionBlockWritesUnknownsAsNullAndAbsentSensors() {
        val motion = clipMotion(summary(camm = false, attached = false), "take.gcsv")
        val document = assertValid(encoded(facts(), context().copy(motion = motion)))
        val block = motionOf(document)
        val mag = block.getValue("sensors").jsonObject.getValue("magnetometer").jsonObject
        assertEquals("false", mag.getValue("present").jsonPrimitive.content)
        assertEquals(kotlinx.serialization.json.JsonNull, mag.getValue("nominalMaxHz"))
        assertEquals(kotlinx.serialization.json.JsonNull, mag.getValue("measuredHz"))
        assertEquals(0, mag.getValue("samples").jsonPrimitive.content.toInt())
        val camm = block.getValue("camm").jsonObject
        assertEquals("false", camm.getValue("present").jsonPrimitive.content)
        assertEquals(0, camm.getValue("packets").jsonPrimitive.content.toInt())
        assertEquals(kotlinx.serialization.json.JsonNull, camm.getValue("binUs"))
        // A log that was not attached is not named, here or among the sidecars.
        assertEquals(kotlinx.serialization.json.JsonNull, block.getValue("log"))
        assertEquals(listOf("take.timing.json", "take.audio.json"), document.media!!.getValue("sidecars").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun motionSyncFollowsTheLogNote() {
        fun sync(summary: com.librestatic.opencinecam.motion.MotionTakeSummary) =
            motionOf(assertValid(encoded(facts(), context().copy(motion = clipMotion(summary, null))))).getValue("sync").jsonPrimitive.content
        assertEquals("measured", sync(summary()))
        assertEquals("estimated-clock", sync(summary(realtime = false, synced = false)))
        assertEquals("estimated-clock", sync(summary(realtime = false, synced = false, estimated = true)))
        assertEquals("estimated-frames", sync(summary(synced = false, estimated = true)))
    }

    @Test fun takeWithoutMotionHasNoBlock() {
        val json = encoded(facts(), context())
        assertFalse("\"motion\"" in json)
        assertNull(assertValid(json).motion)
    }

    @Test fun schemaExampleMatchesTheSchema() {
        val example = File("../docs/schemas/examples/clip-sidecar.example.json").readText()
        val document = assertValid(example)
        assertEquals("1.2.0", document.schemaVersion)
        assertEquals("measured", motionOf(document).getValue("sync").jsonPrimitive.content)
    }

    @Test fun schemaStillAcceptsOlderVersions() {
        val json = Json.parseToJsonElement(encoded(facts(), context())).jsonObject
        listOf("1.0.0", "1.1.0").forEach { version ->
            val older = JsonObject(json + ("schemaVersion" to JsonPrimitive(version)))
            assertEquals(emptyList<String>(), JsonSchemaSubset.violations(schema, older))
        }
    }
}
