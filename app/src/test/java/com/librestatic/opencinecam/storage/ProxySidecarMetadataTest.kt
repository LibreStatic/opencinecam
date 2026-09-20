/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.productionSlateJson
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** JSON mirrors captureEpochJson/pcmSourceTimingJson and both real sidecar writers. No Android IO. */
class ProxySidecarMetadataTest {
    private val id = "893a9a57-cf12-4a0f-b92f-4b5d5b2f6c11"
    private data class Fixture(val take: LocalMediaTake, val docs: List<MetadataDocument>, val frames: Long, val first: Long) {
        fun select(rate: Int = 48_000, channels: Int = 1, decodedFrames: Long = frames) =
            selectProxySidecar(take, docs, rate, channels, decodedFrames, first, 2_000_000)
        fun text(index: Int, text: String): Fixture {
            val artifact = docs[index].artifact.copy(sizeBytes = text.toByteArray().size.toLong())
            val documents = docs.toMutableList().also { it[index] = MetadataDocument(artifact, text) }
            return copy(take = take.copy(metadata = documents.map { it.artifact }), docs = documents)
        }
        fun change(index: Int, vararg path: String, value: JsonElement): Fixture {
            fun update(node: JsonObject, offset: Int): JsonObject = JsonObject(node + (path[offset] to
                if (offset == path.lastIndex) value else update(node.getValue(path[offset]).jsonObject, offset + 1)))
            return text(index, update(Json.parseToJsonElement(docs[index].text!!).jsonObject, 0).toString())
        }
    }
    private fun fixture(flac: Boolean = false, paused: Boolean = false, audioZero: Long = 1_000_000_000,
        videoZero: Long = 1_000_000_000): Fixture {
        val frames = if (paused) 96_000L else 48_000L
        val captured = if (paused) 144_000L else frames
        val container = if (flac) "FLAC" else "WAV"
        val slate = ProductionSlateSettings(project = "Project", scene = "12")
        val video = LocalMediaArtifact("content://media/external_primary/video/media/1", "Take.mp4", "video/mp4", 12_000, 123)
        val audio = LocalMediaArtifact("content://media/external_primary/audio/media/2", if (flac) "Take.flac" else "Take.wav",
            if (flac) "audio/flac" else "audio/wav", if (flac) 8_000 else frames * 2 + 44, 123)
        val pause = if (!paused) JsonNull else buildJsonObject {
            put("policy", "SHARED_BOOTTIME_PCM_GRID_V1"); put("sampleRateHz", 48_000); put("audioFrameZeroNs", audioZero)
            put("stopFrame", captured); put("capturedPcmFrames", captured); put("retainedPcmFrames", frames)
            put("lastCommandNs", 3_000_000_000L); put("lastEffectiveBoundaryNs", 3_000_000_000L)
            putJsonArray("windows") { add(buildJsonObject { put("startFrame", 48_000); put("endFrame", 96_000) }) }
        }
        fun shared(videoReport: Boolean) = buildJsonObject {
            put("audioStorage", "SEPARATE_$container"); put("sourceAudioMinusVideoNs", audioZero - videoZero)
            put("policy", "SHARED_BOOTTIME_CAPTURE_ANCHORS"); put("cameraRealtime", true)
            put("videoFrameZeroNs", videoZero); put("audioFrameZeroNs", audioZero); put("sharedOriginNs", minOf(audioZero, videoZero))
            put("audioTimestampBacked", true); put("audioMaxResidualNs", 0)
            put("videoEncoderFirstPtsUs", if (videoReport) JsonPrimitive(777_777) else JsonNull)
            for (key in listOf("audioEncoderFirstPtsUs", "audioEncoderDelayFrames", "aacCalibration", "audioSourceWindow", "codecInputFrames", "audioPresentationOffsetUs")) put(key, JsonNull)
            put("waveformAlignmentVerified", false); put("submittedPcmFrames", frames); put("encodedAudioPackets", 0)
            put("audioDrainPaddingFrames", 0); put("capturedPcmFrames", captured); put("sharedPause", pause)
        }
        val capture = buildJsonObject {
            put("policy", "AUDIORECORD_BOOTTIME_FIXED_SOURCE_EPOCH_V1"); put("sampleRateHz", 48_000); put("frameBytes", 2)
            put("capturedFrames", captured); put("writtenFrames", frames); put("durationNs", frames * 1_000_000_000L / 48_000)
            put("frameZeroNs", audioZero); put("timestampBacked", true); put("maxResidualNs", 0)
            put("timestampObservations", 2); put("unavailableTimestamps", 0); put("firstTimestampFrame", 0); put("firstTimestampNs", audioZero)
            put("lastTimestampFrame", captured); put("lastTimestampNs", audioZero + captured * 1_000_000_000L / 48_000)
            put("videoAlignmentApplied", false); put("waveformAlignmentVerified", false)
        }
        val videoJson = buildJsonObject {
            put("schema", "opencinecam.av-timing.v1"); put("videoUri", video.uri); put("bundleId", id)
            put("productionSlate", productionSlateJson(slate)); put("avTiming", shared(true))
        }
        val audioJson = buildJsonObject {
            put("schema", "opencinecam-audio-sidecar-v1"); put("productionSlate", productionSlateJson(slate))
            put("audioUri", audio.uri); put("file", audio.name); put("container", container); put("encoding", "PCM_16")
            put("sampleRateHz", 48_000); put("channels", 1); put("derivedBitrateKbps", if (flac) 64 else 768)
            put("frames", frames); put("dataBytes", if (flac) 8_000 else frames * 2)
            put("startedAtElapsedRealtimeNs", 999_000_000); put("stoppedAtElapsedRealtimeNs", 5_000_000_000L)
            put("captureTiming", capture); put("sharedTiming", shared(false)); put("source", "MIC")
            put("preferredInputDeviceId", JsonNull); put("routedInputDeviceId", JsonNull)
            put("noiseSuppressorEnabled", false); put("automaticGainControlEnabled", false); put("acousticEchoCancelerEnabled", false)
            put("recordingGain", buildJsonObject { put("enabled", false) }); put("audioEffects", JsonNull)
            put("disclosure", "Public AudioRecord state")
        }
        val docs = listOf(videoJson, audioJson).mapIndexed { index, json ->
            val text = json.toString()
            MetadataDocument(LocalMediaArtifact("content://media/external_primary/downloads/${index + 3}",
                if (index == 0) "Take.timing.json" else "Take.audio.json", "application/json", text.toByteArray().size.toLong(), 123), text)
        }
        return Fixture(LocalMediaTake("take:$id", video, listOf(video, audio), docs.map { it.artifact }, LocalMediaKind.VIDEO,
            slate, LocalMediaRelationStatus.DECLARED), docs, frames, (videoZero - minOf(audioZero, videoZero)) / 1000)
    }

    @Test fun mediaProviderWavMimeAliasesRetainExactSelectionAndMetadataChecks() {
        for (mime in listOf("audio/x-wav", "audio/wave", "audio/vnd.wave")) {
            val f = fixture()
            val audio = f.take.originals[1].copy(mimeType = mime)
            val alias = f.copy(take = f.take.copy(originals = listOf(f.take.primary, audio)))
            assertEquals(audio, alias.select().audio)
            assertEquals(ProxySidecarContainer.WAV, alias.select().observation.container)
        }
    }

    @Test fun wavAndFlacProducerShapedMetadataSelectExactRelatedArtifacts() {
        for (flac in listOf(false, true)) {
            val f = fixture(flac)
            val selected = f.select()
            assertEquals(f.take.originals[1], selected.audio)
            assertEquals(f.docs[0].artifact, selected.videoMetadata); assertEquals(f.docs[1].artifact, selected.audioMetadata)
            assertEquals(48_000L, selected.timeline.pcmFrames); assertEquals(0L, selected.timeline.audioStartUs)
            assertEquals(2_000_000L, selected.timeline.outputEndUsCeiling)
            assertFalse(selected.timeline.waveformAlignmentVerified)
        }
    }
    @Test fun commandReceiptTimesNeverReplaceSourceOffsetAndCodecLocalFirstPtsMayDiffer() {
        val after = fixture(audioZero = 1_250_000_000)
        assertEquals(250_000L, after.select().timeline.audioStartUs)
        val changed = after.change(1, "startedAtElapsedRealtimeNs", value = JsonPrimitive(123))
            .change(1, "stoppedAtElapsedRealtimeNs", value = JsonPrimitive(Long.MAX_VALUE))
        assertEquals(after.select().timeline, changed.select().timeline)
        val before = fixture(videoZero = 1_250_000_000)
        assertEquals(250_000L, before.select().timeline.videoFirstPtsUs)
        assertEquals(0L, before.select().timeline.audioStartUs)
    }
    @Test fun sharedPauseMetadataValidatesRetainedFramesWithoutApplyingAnotherCut() {
        for (flac in listOf(false, true)) {
            val selected = fixture(flac, paused = true).select()
            assertEquals(96_000L, selected.timeline.pcmFrames); assertEquals(2_000_000L, selected.timeline.audioEndUsCeiling)
            assertEquals(0, selected.timeline.pauseCutsToApply)
        }
    }
    @Test fun foreignUriBundleFilenameAndSlateRejectRatherThanGuessAssociation() {
        val f = fixture()
        reject(f.change(1, "audioUri", value = JsonPrimitive("content://media/external_primary/audio/media/999")))
        reject(f.change(0, "videoUri", value = JsonPrimitive(f.take.originals[1].uri)))
        reject(f.change(0, "bundleId", value = JsonPrimitive("00000000-0000-0000-0000-000000000000")))
        reject(f.change(1, "file", value = JsonPrimitive("Other.wav")))
        reject(f.change(1, "productionSlate", "project", value = JsonPrimitive("Different")))
        reject(f.change(1, "videoUri", value = JsonPrimitive(f.take.primary.uri)))
    }
    @Test fun incompleteAmbiguousAndUnavailableSnapshotReject() {
        val f = fixture()
        reject(f.copy(take = f.take.copy(relationStatus = LocalMediaRelationStatus.INCOMPLETE)))
        reject(f.copy(take = f.take.copy(originals = f.take.originals + f.take.originals[1].copy(uri = "content://media/external_primary/audio/media/9"))))
        reject(f.copy(docs = listOf(f.docs[0], f.docs[0])))
        reject(f.copy(docs = listOf(f.docs[0], f.docs[1].copy(text = null))))
        reject(f.copy(docs = listOf(f.docs[0], f.docs[1].copy(disappeared = true))))
        reject(f.change(0, "schema", value = JsonPrimitive("opencinecam-audio-sidecar-v1")))
    }
    @Test fun duplicateEscapedKeysDepthAndByteBoundsReject() {
        val f = fixture()
        val audio = f.docs[1].text!!
        reject(f.text(1, audio.dropLast(1) + ",\"audioUri\":\"${f.take.originals[1].uri}\"}"))
        reject(f.text(1, audio.dropLast(1) + ",\"audio\\u0055ri\":\"${f.take.originals[1].uri}\"}"))
        reject(f.text(1, audio.dropLast(1) + ",\"nested\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}"))
        reject(f.text(1, " ".repeat(512 * 1024 + 1)))
        reject(f.copy(docs = listOf(f.docs[0], f.docs[1].copy(text = audio + " "))))
    }
    @Test fun stableVideoAudioAnchorsCountsAndPauseMustAgree() {
        val f = fixture(paused = true)
        reject(f.change(0, "avTiming", "videoFrameZeroNs", value = JsonPrimitive(1_000_001_000)))
        reject(f.change(0, "avTiming", "capturedPcmFrames", value = JsonPrimitive(144_001)))
        reject(f.change(0, "avTiming", "sharedPause", "retainedPcmFrames", value = JsonPrimitive(95_999)))
        reject(f.change(1, "sharedTiming", "sourceAudioMinusVideoNs", value = JsonPrimitive(1)))
        reject(f.change(1, "sharedTiming", "sharedPause", "stopFrame", value = JsonNull))
    }
    @Test fun independentlyDecodedRateChannelsFramesAndPcmGeometryMustAgree() {
        val f = fixture()
        assertThrows(Exception::class.java) { f.select(rate = 44_100) }
        assertThrows(Exception::class.java) { f.select(channels = 2) }
        assertThrows(Exception::class.java) { f.select(decodedFrames = 47_999) }
        reject(f.change(1, "channels", value = JsonPrimitive(2)))
        reject(f.change(1, "captureTiming", "frameBytes", value = JsonPrimitive(4)))
        reject(f.change(1, "frames", value = JsonPrimitive("48000")))
        reject(f.change(1, "captureTiming", "writtenFrames", value = JsonPrimitive(47_999)))
    }
    @Test fun missingOrInconsistentMeasuredTimestampsNeverFallBackToCommandTime() {
        val f = fixture()
        reject(f.change(1, "captureTiming", "frameZeroNs", value = JsonNull))
        reject(f.change(1, "captureTiming", "firstTimestampNs", value = JsonPrimitive(1_000_000_001)))
        reject(f.change(1, "captureTiming", "lastTimestampNs", value = JsonPrimitive(2_000_000_001)))
        reject(f.change(1, "captureTiming", "timestampBacked", value = JsonPrimitive(false)))
        reject(f.change(1, "sharedTiming", "cameraRealtime", value = JsonPrimitive(false)))
        reject(f.change(1, "sharedTiming", "waveformAlignmentVerified", value = JsonPrimitive(true)))
    }
    private fun reject(fixture: Fixture) { assertThrows(Exception::class.java) { fixture.select() } }
}
