/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.JsonSchemaSubset
import com.librestatic.opencinecam.core.model.ClipSidecarDocument
import com.librestatic.opencinecam.core.model.decodeClipSidecar
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
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
        assertEquals("1.0.0", document.schemaVersion)
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

    @Test fun nameOfTheSidecarFollowsTheVideoStem() {
        assertEquals("OCC_take.clip.json", clipSidecarName("OCC_take.mp4"))
    }
}
