/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real MediaStore/source/proxy publication. Capture anchors are synthetic, not physical qualification. */
class ProxySidecarRepositoryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    @Test fun wavCreateReopensAndRetainsAllSourceBytes() = exercise(false, false)
    @Test fun flacCreateReopensAndRetainsAllSourceBytes() = exercise(true, false)
    @Test fun pausedWavPublishedGridIsNotCutAgain() = exercise(false, true)
    @Test fun pausedFlacPublishedGridIsNotCutAgain() = exercise(true, true)

    @Test fun wavCancellationRetiresCandidateAndNextCreateWorks() = exercise(false, false, "cancel")
    @Test fun flacCancellationRetiresCandidateAndNextCreateWorks() = exercise(true, false, "cancel")
    @Test fun wavMetadataChangeDuringCreateRetiresCandidateAndNextCreateWorks() = exercise(false, false, "metadata")
    @Test fun flacMetadataChangeDuringCreateRetiresCandidateAndNextCreateWorks() = exercise(true, false, "metadata")

    @Test fun wav24CreatePublishesExplicitNormalizedReceipt() = exercise(false, false, type = ProxyPcmSampleType.S24_LE)
    @Test fun wavFloatCreatePublishesExplicitNormalizedReceipt() = exercise(false, false, type = ProxyPcmSampleType.F32_LE)
    @Test fun flac24CreatePublishesExplicitNormalizedReceipt() = exercise(true, false, type = ProxyPcmSampleType.S24_LE)

    private fun workers() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("proxy-transcoder-") }.toSet()

    private fun exercise(flac: Boolean, paused: Boolean, interruption: String? = null, type: ProxyPcmSampleType = ProxyPcmSampleType.S16_LE) = runBlocking<Unit> {
        val evidencePrefix = proxyTestEvidencePrefix()
        val id = UUID.randomUUID().toString()
        val token = "rev63-$id"
        val namespace = MediaNamespace(id, true)
        val owned = mutableListOf<Uri>()
        val directory = File(context.cacheDir, token).also { check(it.mkdir()) }
        val videoFile = createPreciseGopFixture(context)
        var result: MediaProxyResult? = null
        var selected: LocalMediaTake? = null
        val repository = MediaProxyRepository(context)
        fun publish(collection: Uri, name: String, mime: String, root: String, bytes: ByteArray): LocalMediaArtifact {
            val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
                put("_display_name", name); put("mime_type", mime); put("relative_path", namespace.path(root)); put("is_pending", 1)
            })).also(owned::add)
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            check(resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null) == 1)
            return LocalMediaArtifact(uri.toString(), name, mime, bytes.size.toLong(), 0)
        }
        try {
            val frames = if (type != ProxyPcmSampleType.S16_LE) 48347L else if (paused) 96000L else 48000L
            val channels = if (type != ProxyPcmSampleType.S16_LE) 2 else 1
            val captured = if (paused) 144000L else frames
            val fixture = ProxyPcmProbeDeviceTest()
            // A real published cut, not merely a shortened frame count in metadata.
            val capturedPcm = if (type != ProxyPcmSampleType.S16_LE) {
                val signal = com.librestatic.opencinecam.camera.aacCalibrationSignal(frames.toInt(), 48000, channels)
                java.nio.ByteBuffer.allocate(signal.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .apply { signal.forEach { putShort(it) } }.array()
            } else if (paused) java.nio.ByteBuffer.allocate(captured.toInt() * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                    var phase = 0.0
                    repeat(captured.toInt()) { frame ->
                        phase += 2 * Math.PI * (220.0 + 2000.0 * frame / captured) / 48000.0
                        putShort((12000 * kotlin.math.sin(phase)).toInt().toShort())
                    }
                }.array() else fixture.pcm16(frames.toInt(), 1)
            val pcm = if (paused) capturedPcm.copyOfRange(0, 48000 * 2) +
                capturedPcm.copyOfRange(96000 * 2, capturedPcm.size) else capturedPcm
            assertEquals(frames * channels * 2, pcm.size.toLong())
            val audioFile = File(directory, if (flac) "source.flac" else "source.wav")
            if (type != ProxyPcmSampleType.S16_LE) writeProxyDepthFixture(audioFile, pcm, flac, type)
            else if (flac) fixture.encodeFlac(audioFile, pcm, 48000, 1) else fixture.writeWav(audioFile, pcm, 48000, 1)
            val video = publish(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "$token.mp4", "video/mp4", "DCIM", videoFile.readBytes())
            val audio = publish(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "$token.${if (flac) "flac" else "wav"}", if (flac) "audio/flac" else "audio/wav", "Music", audioFile.readBytes())
            val docs = documents(id, video, audio, frames, captured, flac, paused, type, channels)
            for ((index, doc) in docs.withIndex()) publish(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "$token-$index.json", "application/json", "Download", doc.toString().toByteArray())
            withTimeout(20000) {
                while (selected == null) {
                    val take = LocalMediaRepository(context).page(GallerySettings(), token).takes.singleOrNull()
                    if (take?.relationStatus == LocalMediaRelationStatus.DECLARED &&
                        runCatching { LocalMediaRepository(context).freshSnapshot(take) }.isSuccess) selected = take
                    else delay(100)
                }
            }
            var take = requireNotNull(selected)
            assertEquals(2, take.originals.size); assertEquals(2, take.metadata.size)
            val hashes = (take.originals + take.metadata).associate { it.uri to proxyHash(context, it.uri.toUri()) }
            if (interruption != null) {
                val attemptId = UUID.randomUUID().toString()
                val candidate = File(context.cacheDir, "proxy-$attemptId.mp4")
                val oldWorkers = workers()
                val metadataUri = take.metadata.first().uri.toUri()
                val metadataBytes = resolver.openInputStream(metadataUri)!!.use { it.readBytes() }
                var mutated = false
                val job = async(Dispatchers.Default) {
                    runCatching { repository.create(take, ProxySettings(640, 1), attemptId).also { result = it } }
                }
                try {
                    withTimeout(20000) {
                        while (!candidate.exists() || (workers() - oldWorkers).isEmpty()) {
                            check(!job.isCompleted) { "Create finished before live cancellation/mutation boundary" }
                            delay(1)
                        }
                    }
                    assertFalse(job.isCompleted)
                    if (interruption == "cancel") {
                        job.cancelAndJoin(); assertTrue(job.isCancelled)
                    } else {
                        // Change a prepared/hash-bound document while the Media3 looper is live.
                        // It is not decoder-input corruption and cannot be accepted as a new take.
                        mutated = true
                        resolver.openOutputStream(metadataUri, "rwt")!!.use { it.write(byteArrayOf(123, 125)) }
                        val failed = withTimeout(90000) { job.await() }
                        assertTrue("Changed source metadata must prevent commit", failed.isFailure)
                        assertFalse(failed.exceptionOrNull() is CancellationException)
                    }
                    assertNull(result)
                    assertFalse(candidate.exists())
                    assertTrue("Retire adapter before return/throw/cancel", (workers() - oldWorkers).isEmpty())
                    assertTrue(repository.catalog().none { it.result.proxyId == attemptId })
                    android.util.Log.i("E17SidecarRepository", "interruption=$interruption flac=$flac liveLooperObserved=true frameSubmissionNotClaimed=true candidateRetired=true noCommittedProxy=true")
                } finally {
                    job.cancelAndJoin()
                    if (mutated) resolver.openOutputStream(metadataUri, "rwt")!!.use { it.write(metadataBytes) }
                }
                for ((uri, hash) in hashes) assertEquals(hash, proxyHash(context, uri.toUri()))
                // Re-read provider timestamps after restoring only this test-owned metadata.
                take = withTimeout(20000) {
                    var fresh: LocalMediaTake? = null
                    while (fresh == null) {
                        val entry = LocalMediaRepository(context).page(GallerySettings(), token).takes.singleOrNull()
                        if (entry?.relationStatus == LocalMediaRelationStatus.DECLARED &&
                            runCatching { LocalMediaRepository(context).freshSnapshot(entry) }.isSuccess) fresh = entry
                        else delay(100)
                    }
                    requireNotNull(fresh)
                }
                selected = take
                assertNull(repository.existing(take))
            }
            val original = probeProxyMedia(context, video.uri.toUri())
            result = withTimeout(90000) { repository.create(take, ProxySettings(640, 1)) }
            val proxy = requireNotNull(result)
            assertEquals(proxy, MediaProxyRepository(context).existing(take))
            val entry = repository.catalog().single { it.takeId == take.id }
            assertEquals(proxy, entry.result)
            val after = probeProxyMedia(context, proxy.proxyUri.toUri())
            verifyProxyVideoCorrespondence(original, after, VideoDisplayGeometry(96, 64))
            assertEquals("audio/mp4a-latm", after.audio!!.mime)
            val json = resolver.openInputStream(proxy.metadataUri.toUri())!!.use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
            assertEquals("sidecar-pcm-normalized-calibrated-aac", json.getValue("audioPolicy").jsonPrimitive.content)
            val evidence = json.getValue("sidecarSource").jsonObject
            validateProxySidecarReceipt(evidence)
            assertEquals(frames, evidence.getValue("sourceFrames").jsonPrimitive.long)
            val observedPcm = probeProxyPcm(context, audio.uri.toUri())
            assertEquals("opencinecam.proxy-sidecar.v2", evidence.getValue("schema").jsonPrimitive.content)
            assertEquals(proxyPcmSampleType(observedPcm.pcmEncoding).name, evidence.getValue("decodedPcmEncoding").jsonPrimitive.content)
            assertEquals(observedPcm.decodedSha256, evidence.getValue("decodedPcmSha256").jsonPrimitive.content)
            assertEquals(observedPcm.exportPcm16Sha256, evidence.getValue("exportPcm16Sha256").jsonPrimitive.content)
            assertEquals(hashes, evidence.getValue("sourceSha256").jsonObject.mapValues { it.value.jsonPrimitive.content })
            for ((uri, hash) in hashes) assertEquals(hash, proxyHash(context, uri.toUri()))
            if (paused) {
                val evidenceDirectory = File(context.getExternalFilesDir(null), "$evidencePrefix-repository-${if (flac) "flac" else "wav"}-paused")
                check(evidenceDirectory.mkdir()) { "Refusing to overwrite pause evidence" }
                resolver.openInputStream(proxy.proxyUri.toUri())!!.use { input ->
                    File(evidenceDirectory, "proxy.mp4").outputStream().use { input.copyTo(it) }
                }
                File(evidenceDirectory, "captured.pcm").writeBytes(capturedPcm)
                File(evidenceDirectory, "published.pcm").writeBytes(pcm)
                audioFile.copyTo(File(evidenceDirectory, audioFile.name))
                File(evidenceDirectory, "receipt.json").writeText(json.toString())
                File(evidenceDirectory, "pause.json").writeText("{\"capturedFrames\":$captured,\"retainedFrames\":$frames,\"removedStartFrame\":48000,\"removedEndFrame\":96000,\"sampleRateHz\":48000,\"channels\":1,\"syntheticCaptureAnchors\":true}")
            }
            repository.deleteProxy(entry) { _, _ -> }
            result = null
            assertNull(repository.existing(take))
            for ((uri, hash) in hashes) assertEquals(hash, proxyHash(context, uri.toUri()))
        } finally {
            result?.let { proxy -> repository.deleteProxy(requireNotNull(selected), proxy) { _, _ -> } }
            for (uri in owned.asReversed()) check(resolver.delete(uri, null, null) == 1)
            check(videoFile.delete()); check(directory.listFiles()!!.all { it.delete() }); check(directory.delete())
        }
    }

    private fun documents(id: String, video: LocalMediaArtifact, audio: LocalMediaArtifact,
        frames: Long, captured: Long, flac: Boolean, paused: Boolean, type: ProxyPcmSampleType, channels: Int): List<JsonObject> {
        val container = if (flac) "FLAC" else "WAV"
        val slate = ProductionSlateSettings(project = "Project", scene = "12")
        val audioZero = 1000000000L
        val videoZero = 1000000000L
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
            put("policy", "AUDIORECORD_BOOTTIME_FIXED_SOURCE_EPOCH_V1"); put("sampleRateHz", 48_000); put("frameBytes", channels * type.bytes)
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
            put("audioUri", audio.uri); put("file", audio.name); put("container", container); put("encoding", when (type) { ProxyPcmSampleType.S24_LE -> "PCM_24"; ProxyPcmSampleType.F32_LE -> "PCM_FLOAT"; else -> "PCM_16" })
            put("sampleRateHz", 48_000); put("channels", channels); put("derivedBitrateKbps", if (flac) 64 else 48000 * channels * type.bytes * 8 / 1000)
            put("frames", frames); put("dataBytes", if (flac) audio.sizeBytes else frames * channels * type.bytes)
            put("startedAtElapsedRealtimeNs", 999_000_000); put("stoppedAtElapsedRealtimeNs", 5_000_000_000L)
            put("captureTiming", capture); put("sharedTiming", shared(false)); put("source", "MIC")
            put("preferredInputDeviceId", JsonNull); put("routedInputDeviceId", JsonNull)
            put("noiseSuppressorEnabled", false); put("automaticGainControlEnabled", false); put("acousticEchoCancelerEnabled", false)
            put("recordingGain", buildJsonObject { put("enabled", false) }); put("audioEffects", JsonNull)
            put("disclosure", "Public AudioRecord state")
        }
        return listOf(videoJson, audioJson)
    }
}
