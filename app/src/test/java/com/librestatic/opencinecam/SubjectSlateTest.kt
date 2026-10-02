/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.TimecodeRate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class SubjectSlateTest {
    private val slate = ProductionSlateSettings(project = "Lumen", camera = "B", scene = " 12A ", reel = "", takeNumber = 7)

    @Test fun slateLinesFollowCanonicalOrderAndShowOnlySelectedFields() {
        val lines = subjectSlateLines(slate, setOf(SubjectSlateField.TIMECODE, SubjectSlateField.TAKE, SubjectSlateField.SCENE), "01:00:00:00")
        assertEquals(listOf(SubjectSlateField.SCENE, SubjectSlateField.TAKE, SubjectSlateField.TIMECODE), lines.map { it.field })
        assertEquals(listOf("12A", "7", "01:00:00:00"), lines.map { it.value })
        assertEquals(emptyList<SubjectSlateLine>(), subjectSlateLines(slate, emptySet(), SLATE_TIMECODE_IDLE))
    }

    @Test fun emptyTextShowsPlaceholderAndTakeNumberIsUnchanged() {
        val all = subjectSlateLines(slate, SubjectSlateField.entries.toSet(), SLATE_TIMECODE_IDLE).associate { it.field to it.value }
        assertEquals(SLATE_EMPTY_VALUE, all[SubjectSlateField.REEL])
        assertEquals("Lumen", all[SubjectSlateField.PROJECT])
        assertEquals("B", all[SubjectSlateField.CAMERA])
        assertEquals("7", all[SubjectSlateField.TAKE])
        assertEquals(7, slate.takeNumber)
    }

    @Test fun rowsPairSceneWithTakeAndCameraWithReel() {
        val rows = subjectSlateRows(subjectSlateLines(slate, SubjectSlateField.entries.toSet(), SLATE_TIMECODE_IDLE))
        assertEquals(listOf(
            listOf(SubjectSlateField.PROJECT),
            listOf(SubjectSlateField.SCENE, SubjectSlateField.TAKE),
            listOf(SubjectSlateField.CAMERA, SubjectSlateField.REEL),
            listOf(SubjectSlateField.TIMECODE),
        ), rows.map { row -> row.map { it.field } })
        val partial = subjectSlateRows(subjectSlateLines(slate, setOf(SubjectSlateField.TAKE, SubjectSlateField.REEL), ""))
        assertEquals(listOf(listOf(SubjectSlateField.TAKE), listOf(SubjectSlateField.REEL)), partial.map { row -> row.map { it.field } })
    }

    @Test fun smpteLabelsParseOnlyCanonicalForms() {
        assertEquals("01:02:03:04", parseSmpteLabel("01:02:03:04")!!.format())
        assertTrue(parseSmpteLabel("00:10:00;02")!!.dropFrame)
        for (bad in listOf("1:02:03:04", "01:02:03", "25:00:00:00", "01:02:03:04 ", "aa:bb:cc:dd")) assertNull(bad, parseSmpteLabel(bad))
    }

    @Test fun idleOrUnpublishedTimecodeIsNotInvented() {
        assertEquals(SLATE_TIMECODE_IDLE, slateTimecodeLabel("01:00:00:00", TimecodeRate(25), recording = false, running = false, sinceUpdateNs = 0))
        assertEquals(SLATE_TIMECODE_IDLE, slateTimecodeLabel(null, TimecodeRate(25), recording = true, running = true, sinceUpdateNs = 0))
    }

    @Test fun runningTimecodeAdvancesAtRateAndStopsAtTheCap() {
        val rate = TimecodeRate(25)
        assertEquals("01:00:00:00", slateTimecodeLabel("01:00:00:00", rate, true, true, 0))
        assertEquals("01:00:00:05", slateTimecodeLabel("01:00:00:00", rate, true, true, 200_000_000))
        assertEquals("01:00:00:24", slateTimecodeLabel("01:00:00:00", rate, true, true, 999_999_999))
        assertEquals("01:00:01:00", slateTimecodeLabel("01:00:00:00", rate, true, true, 60_000_000_000))
        // Paused or finalizing: the published value is held, not extrapolated.
        assertEquals("01:00:00:00", slateTimecodeLabel("01:00:00:00", rate, true, false, 500_000_000))
        // Wraps at 24 h like the tracker.
        assertEquals("00:00:00:04", slateTimecodeLabel("23:59:59:24", rate, true, true, 200_000_000))
    }

    @Test fun dropFrameExtrapolationSkipsOmittedLabels() {
        assertEquals("00:01:00;02", slateTimecodeLabel("00:00:59;29", TimecodeRate(30, true), true, true, 34_000_000))
    }

    @Test fun labelsThatDoNotMatchTheRateAreShownAsPublished() {
        assertEquals("01:00:00:27", slateTimecodeLabel("01:00:00:27", TimecodeRate(25), true, true, 500_000_000))
        assertEquals("01:00:00;10", slateTimecodeLabel("01:00:00;10", TimecodeRate(30), true, true, 500_000_000))
        assertEquals("garbage", slateTimecodeLabel("garbage", TimecodeRate(30), true, true, 500_000_000))
        assertEquals("01:00:00:10", slateTimecodeLabel("01:00:00:10", null, true, true, 500_000_000))
    }

    @Test fun disabledTimecodeHasNoExtrapolationRate() {
        assertNull(CameraSettings(timecodeEnabled = false).slateTimecodeRate())
        assertEquals(TimecodeRate(30, true), CameraSettings(timecodeEnabled = true, timecodeNominalFps = 30, timecodeDropFrame = true).slateTimecodeRate())
    }
}

class SubjectSyncMarkerTest {
    private val armed = SubjectSyncArming(flash = true, beep = true, slatePresented = true, coverVisible = true)

    private fun record(beep: SubjectSyncMarkerOutcome = SubjectSyncMarkerOutcome.FIRED, failure: String? = null) = SubjectSyncMarkerRecord(
        SubjectSyncMarkerReport(2_000_500_000, SubjectSyncMarkerOutcome.FIRED, beep,
            if (beep == SubjectSyncMarkerOutcome.FIRED) 2_003_000_000 else null, failure),
        2_000_000_000,
    )

    @Test fun markerSerializesWithOffsetsAndRoundTrips() {
        val value = record()
        val json = subjectSyncMarkerJson(value)
        assertEquals(500L, json["offsetFromTakeStartUs"]!!.jsonPrimitive.content.toLong())
        assertEquals(3_000L, json["beepOffsetFromTakeStartUs"]!!.jsonPrimitive.content.toLong())
        assertEquals("true", json["markerFired"]!!.jsonPrimitive.content)
        assertEquals("false", json["outputTimeVerified"]!!.jsonPrimitive.content)
        assertEquals(value, parseSubjectSyncMarkerJson(Json.parseToJsonElement(json.toString()) as JsonObject))
    }

    @Test fun failedMarkerIsRecordedAsNotFired() {
        val failed = SubjectSyncMarkerRecord(SubjectSyncMarkerReport(5, SubjectSyncMarkerOutcome.NOT_SHOWN, SubjectSyncMarkerOutcome.FAILED,
            failure = boundedSyncFailure("Media volume is muted")), 1)
        val json = subjectSyncMarkerJson(failed)
        assertEquals("false", json["markerFired"]!!.jsonPrimitive.content)
        assertEquals("FAILED", json["beep"]!!.jsonPrimitive.content)
        assertEquals("Media volume is muted", json["failure"]!!.jsonPrimitive.content)
        assertEquals(failed, parseSubjectSyncMarkerJson(json))
    }

    @Test fun sidecarWithoutTheNodeStillParsesAsAbsent() {
        val legacy = Json.parseToJsonElement("""{"schema":"opencinecam.recording-timing.v1","timecode":{"enabled":true}}""") as JsonObject
        assertNull(subjectSyncMarkerFromSidecar(legacy))
        val withMarker = JsonObject(legacy + (SUBJECT_SYNC_MARKER_KEY to subjectSyncMarkerJson(record())))
        assertEquals(record(), subjectSyncMarkerFromSidecar(withMarker))
        // The rest of the sidecar is untouched by the additive node.
        assertEquals(legacy["timecode"], withMarker["timecode"])
    }

    @Test fun parserIgnoresAdditiveKeysAndRejectsOtherVersionsOrInconsistentNodes() {
        val json = subjectSyncMarkerJson(record())
        assertEquals(record(), parseSubjectSyncMarkerJson(JsonObject(json + ("futureField" to JsonPrimitive("x")))))
        assertNull(parseSubjectSyncMarkerJson(JsonObject(json + ("schemaVersion" to JsonPrimitive(2)))))
        assertNull(parseSubjectSyncMarkerJson(JsonObject(json + ("markerFired" to JsonPrimitive(false)))))
        assertNull(parseSubjectSyncMarkerJson(JsonObject(json + ("beep" to JsonPrimitive("NOT_SHOWN")))))
        assertNull(parseSubjectSyncMarkerJson(JsonObject(json + ("triggerElapsedRealtimeNanos" to JsonPrimitive("2000500000")))))
        assertNull(parseSubjectSyncMarkerJson(JsonObject(json - "flash")))
        assertNull(parseSubjectSyncMarkerJson(buildJsonObject { put("schemaVersion", 1) }))
    }

    @Test fun reportInvariantsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) { SubjectSyncMarkerReport(10, SubjectSyncMarkerOutcome.FIRED, SubjectSyncMarkerOutcome.FIRED) }
        assertThrows(IllegalArgumentException::class.java) { SubjectSyncMarkerReport(10, SubjectSyncMarkerOutcome.FIRED, SubjectSyncMarkerOutcome.FAILED, 11) }
        assertThrows(IllegalArgumentException::class.java) { SubjectSyncMarkerReport(10, SubjectSyncMarkerOutcome.FIRED, SubjectSyncMarkerOutcome.FIRED, 9) }
        assertThrows(IllegalArgumentException::class.java) { SubjectSyncMarkerRecord(SubjectSyncMarkerReport(10, SubjectSyncMarkerOutcome.FIRED, SubjectSyncMarkerOutcome.NOT_REQUESTED), 11) }
        assertEquals(200, boundedSyncFailure("x".repeat(500))!!.length)
        assertEquals("a b", boundedSyncFailure("a\nb"))
    }

    @Test fun firesOnceOnlyOnObservedTransitionIntoConfirmedRecording() {
        val policy = SubjectSyncMarkerPolicy()
        assertNull(policy.observe(CameraUiPhase.PREVIEWING, false, 0, armed))
        val decision = policy.observe(CameraUiPhase.RECORDING, false, 0, armed)
        assertEquals(SubjectSyncDecision(SubjectSyncMarkerOutcome.FIRED, beep = true), decision)
        // Further ticks, pauses and finalization of the same take never fire again.
        assertNull(policy.observe(CameraUiPhase.RECORDING, false, 500, armed))
        assertNull(policy.observe(CameraUiPhase.RECORDING, false, 0, armed))
        assertNull(policy.observe(CameraUiPhase.RECORDING, true, 900, armed))
        assertNull(policy.observe(CameraUiPhase.SAVED, false, 900, armed))
        assertNotNull(policy.observe(CameraUiPhase.RECORDING, false, 0, armed))
    }

    @Test fun neverFiresOnCountdownFinalizingOrAReconnectionMidTake() {
        val countdown = SubjectSyncMarkerPolicy()
        assertNull(countdown.observe(CameraUiPhase.PREVIEWING, false, 0, armed))
        assertNull(countdown.observe(CameraUiPhase.PREVIEWING, false, 0, armed))
        val reconnected = SubjectSyncMarkerPolicy()
        assertNull(reconnected.observe(CameraUiPhase.RECORDING, false, 0, armed))
        val late = SubjectSyncMarkerPolicy()
        late.observe(CameraUiPhase.PREVIEWING, false, 0, armed)
        assertNull(late.observe(CameraUiPhase.RECORDING, false, SUBJECT_SYNC_MAX_START_ELAPSED_MS + 1, armed))
        val finalizing = SubjectSyncMarkerPolicy()
        finalizing.observe(CameraUiPhase.PREVIEWING, false, 0, armed)
        assertNull(finalizing.observe(CameraUiPhase.RECORDING, true, 0, armed))
    }

    @Test fun armingDecidesWhatFires() {
        fun first(arming: SubjectSyncArming) = SubjectSyncMarkerPolicy().run {
            observe(CameraUiPhase.PREVIEWING, false, 0, arming)
            observe(CameraUiPhase.RECORDING, false, 0, arming)
        }
        assertNull(first(armed.copy(slatePresented = false)))
        assertNull(first(armed.copy(flash = false, beep = false)))
        assertEquals(SubjectSyncDecision(SubjectSyncMarkerOutcome.NOT_SHOWN, true), first(armed.copy(coverVisible = false)))
        assertEquals(SubjectSyncDecision(SubjectSyncMarkerOutcome.NOT_REQUESTED, true), first(armed.copy(flash = false)))
        assertEquals(SubjectSyncDecision(SubjectSyncMarkerOutcome.FIRED, false), first(armed.copy(beep = false)))
    }

    @Test fun slateAdmitsOneReportPerTakeAndDropsStaleOrLateOnes() {
        val slot = SubjectSyncMarkerSlot()
        val report = SubjectSyncMarkerReport(1_100, SubjectSyncMarkerOutcome.FIRED, SubjectSyncMarkerOutcome.NOT_REQUESTED)
        assertFalse("no take", slot.offer(report, 2_000))
        slot.begin(1_000)
        assertFalse("from an earlier take", slot.offer(report.copy(triggerElapsedRealtimeNanos = 999), 2_000))
        assertFalse("from the future", slot.offer(report.copy(triggerElapsedRealtimeNanos = 2_001), 2_000))
        assertTrue(slot.offer(report, 2_000))
        assertFalse("second report", slot.offer(report.copy(triggerElapsedRealtimeNanos = 1_200), 2_000))
        assertEquals(SubjectSyncMarkerRecord(report, 1_000), slot.consume())
        assertFalse("after finalization", slot.offer(report, 2_000))
        assertNull(slot.consume())
        slot.begin(3_000)
        assertNull("a new take starts empty", slot.consume())
    }

    @Test fun beepIsAOneKilohertzBurstWithSoftEdges() {
        val pcm = subjectSyncBeepPcm()
        assertEquals(SUBJECT_SYNC_BEEP_SAMPLE_RATE * SUBJECT_SYNC_BEEP_MS / 1000, pcm.size)
        assertEquals(0, pcm.first().toInt())
        assertEquals(0, pcm.last().toInt())
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue(peak in 16_000..16_384)
        // One cycle of 1 kHz is 48 samples: count rising zero crossings across the burst.
        val crossings = (1 until pcm.size).count { pcm[it - 1] < 0 && pcm[it] >= 0 }
        assertTrue(crossings in 98..100)
    }

    @Test fun serializedKeysMatchTheCanonicalSchema() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/schemas").isDirectory) dir = dir.parentFile
        val schema = Json.parseToJsonElement(File(requireNotNull(dir), "docs/schemas/subject-sync-marker.schema.json").readText()).jsonObject
        val keys = subjectSyncMarkerJson(record()).keys
        assertEquals(schema["properties"]!!.jsonObject.keys, keys)
        assertEquals((schema["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet(), keys)
    }
}
