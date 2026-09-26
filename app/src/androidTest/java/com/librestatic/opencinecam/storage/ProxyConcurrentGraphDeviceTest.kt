/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.GallerySettings
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.ProxySettings
import com.librestatic.opencinecam.SubjectPreviewGpuTest
import com.librestatic.opencinecam.camera.CaptureEpochClock
import com.librestatic.opencinecam.camera.RecordingFrameSize
import com.librestatic.opencinecam.camera.RecordingGeometry
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.TimelapsePauseStatus
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.productionSlateJson
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Full simultaneous graph (GPU pipeline + real embedded-audio recording) with live proxy composition. */
class ProxyConcurrentGraphDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    @Test fun proxyCreateComposesWhileRecordingIsLive() = exercise(pauseDuringCreate = false, cancel = false)
    @Test fun proxyCreateDuringSharedPauseComposesAndRecordingResumes() = exercise(pauseDuringCreate = true, cancel = false)
    @Test fun proxyCancellationDuringLiveRecordingRetiresCandidateAndRecordingSurvives() =
        exercise(pauseDuringCreate = false, cancel = true)

    private fun workers() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("proxy-transcoder-") }.toSet()

    private fun exercise(pauseDuringCreate: Boolean, cancel: Boolean) = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val evidencePrefix = proxyTestEvidencePrefix()
        val case = when {
            cancel -> "cancel"
            pauseDuringCreate -> "pause"
            else -> "live"
        }
        val id = UUID.randomUUID().toString()
        val token = "rev65-$id"
        val namespace = MediaNamespace(id, true)
        val owned = mutableListOf<Uri>()
        val directory = File(context.cacheDir, token).also { check(it.mkdir()) }
        val videoFile = createPreciseGopFixture(context)
        val repository = MediaProxyRepository(context)
        val attemptId = UUID.randomUUID().toString()
        var result: MediaProxyResult? = null
        var take: LocalMediaTake? = null
        fun publish(collection: Uri, name: String, mime: String, root: String, bytes: ByteArray): LocalMediaArtifact {
            val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
                put("_display_name", name); put("mime_type", mime); put("relative_path", namespace.path(root)); put("is_pending", 1)
            })).also(owned::add)
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            check(resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null) == 1)
            return LocalMediaArtifact(uri.toString(), name, mime, bytes.size.toLong(), 0)
        }
        val recordingFile = File(context.cacheDir, "$token-live.mp4")
        var audioResultUri: Uri? = null
        try {
            val frames = 48_000
            val fixture = ProxyPcmProbeDeviceTest()
            val pcm = fixture.pcm16(frames, 1)
            val audioFile = File(directory, "source.wav")
            fixture.writeWav(audioFile, pcm, 48_000, 1)
            val video = publish(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "$token.mp4", "video/mp4", "DCIM", videoFile.readBytes())
            val audio = publish(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "$token.wav", "audio/wav", "Music", audioFile.readBytes())
            val docs = documents(id, video, audio, frames.toLong())
            for ((index, doc) in docs.withIndex()) publish(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "$token-$index.json", "application/json", "Download", doc.toString().toByteArray())
            withTimeout(20000) {
                while (take == null) {
                    val entry = LocalMediaRepository(context).page(GallerySettings(), token).takes.singleOrNull()
                    if (entry?.relationStatus == LocalMediaRelationStatus.DECLARED &&
                        runCatching { LocalMediaRepository(context).freshSnapshot(entry) }.isSuccess) take = entry
                    else delay(100)
                }
            }
            val published = requireNotNull(take)
            assertEquals(2, published.originals.size); assertEquals(2, published.metadata.size)
            val hashes = (published.originals + published.metadata).associate { it.uri to proxyHash(context, it.uri.toUri()) }

            val statuses = CopyOnWriteArrayList<TimelapsePauseStatus>()
            val stopped = CountDownLatch(1)
            val saved = AtomicBoolean(false)
            val levels = AtomicInteger()
            val settings = CameraSettings(audioSampleRateHz = 48_000, audioChannels = 1,
                audioBitDepth = AudioBitDepth.PCM_16, audioSource = AudioSourceSelection.MIC,
                automaticGainControlEnabled = false, noiseSuppressorEnabled = false, acousticEchoCancelerEnabled = false)
            // One shared epoch clock instance: the shared pause exists only while the audio
            // sidecar and the recording pipeline agree on the same clock.
            val clock = CaptureEpochClock(true, 48_000)
            val audioRecorder = WavAudioSidecarRecorder.create(context, "$token-live.mp4", settings, clock) { levels.incrementAndGet() }
            try {
                SubjectPreviewGpuTest.Fixture(embeddedAudio = true).use { fixture ->
                    val frame = RecordingFrameSize(128, 96)
                    val geometry = RecordingGeometry(RecordingGeometryMode.COMPATIBLE, 0, 0, 0, 0, 0, frame, frame, frame)
                    ParcelFileDescriptor.open(recordingFile,
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                        assertTrue(fixture.pipeline.startRecording(output, 2_000_000, geometry,
                            separateAudioClock = clock,
                            onTimelapsePauseChanged = { statuses += it },
                            onStarted = { audioRecorder.start() },
                            onStopped = { ok, _ -> saved.set(ok); stopped.countDown() }))
                    }
                fun feed(ms: Long) {
                    val end = SystemClock.elapsedRealtime() + ms
                    while (SystemClock.elapsedRealtime() < end) {
                        fixture.sourceFrame(SystemClock.elapsedRealtimeNanos()); SystemClock.sleep(34)
                    }
                }
                feed(1_200)
                val oldWorkers = workers()
                val job = async(Dispatchers.Default) {
                    runCatching { repository.create(published, ProxySettings(640, 1), attemptId).also { result = it } }
                }
                if (cancel) {
                    val candidate = File(context.cacheDir, "proxy-$attemptId.mp4")
                    // A fast host can finish the short fixture before (or while) the boundary is observed.
                    // That is not a cancellation failure: stop cleanly and skip; the finally removes the proxy.
                    suspend fun skipMissedBoundary() {
                        job.join()
                        assertTrue(fixture.pipeline.stopRecording())
                        assertTrue(stopped.await(10, TimeUnit.SECONDS))
                        assumeTrue("Create finished before live cancellation boundary; cancellation not exercised", false)
                    }
                    var reached = false
                    withTimeout(30000) {
                        while (!job.isCompleted) {
                            if (candidate.exists() && (workers() - oldWorkers).isNotEmpty()) { reached = true; break }
                            delay(1)
                        }
                    }
                    if (!reached) skipMissedBoundary()
                    job.cancelAndJoin()
                    if (!job.isCancelled) skipMissedBoundary()
                    assertFalse("Candidate retired after cancel", candidate.exists())
                    assertTrue("Retire adapter after cancel", (workers() - oldWorkers).isEmpty())
                    assertNull(repository.existing(published))
                } else {
                    if (pauseDuringCreate) {
                        val deadline = SystemClock.elapsedRealtime() + 3000
                        while (statuses.isEmpty() && SystemClock.elapsedRealtime() < deadline) feed(70)
                        assertTrue("Missing shared pause status: ${fixture.failures}", statuses.isNotEmpty())
                        val ack = CountDownLatch(1); val changed = AtomicBoolean()
                        assertTrue(fixture.pipeline.setTimelapsePaused(true) { changed.set(it); ack.countDown() })
                        assertTrue(ack.await(2, TimeUnit.SECONDS)); assertTrue(changed.get())
                    }
                    val outcome = withTimeout(150_000) { job.await() }
                    result = outcome.getOrThrow()
                    assertNotNull("Composition during live graph must succeed", result)
                    if (pauseDuringCreate) {
                        val ack = CountDownLatch(1); val changed = AtomicBoolean()
                        assertTrue(fixture.pipeline.setTimelapsePaused(false) { changed.set(it); ack.countDown() })
                        assertTrue(ack.await(2, TimeUnit.SECONDS)); assertTrue(changed.get())
                    }
                }
                feed(700)
                assertTrue(fixture.pipeline.stopRecording())
                assertTrue(stopped.await(10, TimeUnit.SECONDS))
                assertTrue(fixture.failures.toString(), saved.get())
                val audioResult = requireNotNull(audioRecorder.finish(true))
                assertTrue("Sidecar audio must capture during the live graph", audioResult.frames > 0)
                assertTrue(levels.get() > 0)
                audioResultUri = audioResult.uri
                if (pauseDuringCreate) {
                    assertTrue(statuses.any { it.paused }); assertFalse(statuses.last().paused)
                }
                val recording = probeProxyMedia(context, Uri.fromFile(recordingFile))
                assertTrue(recording.video.timestampsUs.isNotEmpty())
                assertTrue(recording.video.durationUs > 0)

                val proxy = requireNotNull(if (cancel) {
                    assertNull(repository.existing(published))
                    withTimeout(90_000) { repository.create(published, ProxySettings(640, 1)) }
                } else {
                    result
                })
                assertEquals(proxy, MediaProxyRepository(context).existing(published))
                val entry = repository.catalog().single { it.takeId == published.id }
                assertEquals(proxy, entry.result)
                val after = probeProxyMedia(context, proxy.proxyUri.toUri())
                verifyProxyVideoCorrespondence(probeProxyMedia(context, video.uri.toUri()), after, VideoDisplayGeometry(96, 64))
                assertEquals("audio/mp4a-latm", after.audio!!.mime)
                val receipt = resolver.openInputStream(proxy.metadataUri.toUri())!!.use {
                    Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
                assertEquals("opencinecam.proxy-sidecar.v2",
                    receipt.getValue("sidecarSource").jsonObject.getValue("schema").jsonPrimitive.content)
                assertEquals(hashes, receipt.getValue("sidecarSource").jsonObject.getValue("sourceSha256")
                    .jsonObject.mapValues { it.value.jsonPrimitive.content })
                for ((uri, hash) in hashes) assertEquals(hash, proxyHash(context, uri.toUri()))

                val evidence = File(context.getExternalFilesDir(null), "$evidencePrefix-concurrent-$case")
                check(evidence.mkdir()) { "Refusing to overwrite concurrent-graph evidence" }
                recordingFile.copyTo(File(evidence, "recording.mp4"))
                resolver.openInputStream(proxy.proxyUri.toUri())!!.use { input ->
                    File(evidence, "proxy.mp4").outputStream().use { input.copyTo(it) }
                }
                File(evidence, "receipt.json").writeText(receipt.toString())
                File(evidence, "statuses.json").writeText(
                    statuses.joinToString(prefix = "[", postfix = "]", separator = ",\n") { it.toString() })
                repository.deleteProxy(entry) { _, _ -> }
                result = null
                assertNull(repository.existing(published))
                }
            } finally {
                audioRecorder.discard()
                // Tolerant retirement of published sidecar rows, mirroring OwnedOutputRows:
                // collection+exact-ID deletion accepts 0..1 without owner-grant questions.
                audioResultUri?.let { row ->
                    val deleted = resolver.delete(ContentUris.removeId(row),
                        "${MediaStore.MediaColumns._ID} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                        arrayOf(ContentUris.parseId(row).toString(), context.packageName))
                    check(deleted in 0..1)
                }
                val metadataName = "$token-live.mp4".removeSuffix(".mp4") + ".audio.json"
                resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf("_id"),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(metadataName), null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                        val deleted = resolver.delete(ContentUris.removeId(row),
                            "${MediaStore.MediaColumns._ID} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                            arrayOf(ContentUris.parseId(row).toString(), context.packageName))
                        check(deleted in 0..1)
                    }
                }
            }
        } finally {
            result?.let { proxy -> repository.deleteProxy(requireNotNull(take), proxy) { _, _ -> } }
            for (uri in owned.asReversed()) check(resolver.delete(uri, null, null) == 1)
            check(recordingFile.delete())
            check(videoFile.delete()); check(directory.listFiles()!!.all { it.delete() }); check(directory.delete())
        }
    }

    private fun documents(id: String, video: LocalMediaArtifact, audio: LocalMediaArtifact, frames: Long): List<JsonObject> {
        val slate = ProductionSlateSettings(project = "Project", scene = "12")
        val audioZero = 1_000_000_000L
        val videoZero = 1_000_000_000L
        fun shared(videoReport: Boolean) = buildJsonObject {
            put("audioStorage", "SEPARATE_WAV"); put("sourceAudioMinusVideoNs", audioZero - videoZero)
            put("policy", "SHARED_BOOTTIME_CAPTURE_ANCHORS"); put("cameraRealtime", true)
            put("videoFrameZeroNs", videoZero); put("audioFrameZeroNs", audioZero); put("sharedOriginNs", minOf(audioZero, videoZero))
            put("audioTimestampBacked", true); put("audioMaxResidualNs", 0)
            put("videoEncoderFirstPtsUs", if (videoReport) JsonPrimitive(777_777) else JsonNull)
            for (key in listOf("audioEncoderFirstPtsUs", "audioEncoderDelayFrames", "aacCalibration", "audioSourceWindow", "codecInputFrames", "audioPresentationOffsetUs")) put(key, JsonNull)
            put("waveformAlignmentVerified", false); put("submittedPcmFrames", frames); put("encodedAudioPackets", 0)
            put("audioDrainPaddingFrames", 0); put("capturedPcmFrames", frames); put("sharedPause", JsonNull)
        }
        val capture = buildJsonObject {
            put("policy", "AUDIORECORD_BOOTTIME_FIXED_SOURCE_EPOCH_V1"); put("sampleRateHz", 48_000); put("frameBytes", 2)
            put("capturedFrames", frames); put("writtenFrames", frames); put("durationNs", frames * 1_000_000_000L / 48_000)
            put("frameZeroNs", audioZero); put("timestampBacked", true); put("maxResidualNs", 0)
            put("timestampObservations", 2); put("unavailableTimestamps", 0); put("firstTimestampFrame", 0); put("firstTimestampNs", audioZero)
            put("lastTimestampFrame", frames); put("lastTimestampNs", audioZero + frames * 1_000_000_000L / 48_000)
            put("videoAlignmentApplied", false); put("waveformAlignmentVerified", false)
        }
        val videoJson = buildJsonObject {
            put("schema", "opencinecam.av-timing.v1"); put("videoUri", video.uri); put("bundleId", id)
            put("productionSlate", productionSlateJson(slate)); put("avTiming", shared(true))
        }
        val audioJson = buildJsonObject {
            put("schema", "opencinecam-audio-sidecar-v1"); put("productionSlate", productionSlateJson(slate))
            put("audioUri", audio.uri); put("file", audio.name); put("container", "WAV"); put("encoding", "PCM_16")
            put("sampleRateHz", 48_000); put("channels", 1); put("derivedBitrateKbps", 48_000 * 2 * 8 / 1000)
            put("frames", frames); put("dataBytes", frames * 2)
            put("startedAtElapsedRealtimeNs", 999_000_000); put("stoppedAtElapsedRealtimeNs", 5_000_000_000L)
            put("captureTiming", capture); put("sharedTiming", shared(false)); put("source", "MIC")
            put("preferredInputDeviceId", JsonNull); put("routedInputDeviceId", JsonNull)
            put("noiseSuppressorEnabled", false); put("automaticGainControlEnabled", false); put("acousticEchoCancelerEnabled", false)
            putJsonObject("recordingGain") { put("enabled", false) }; put("audioEffects", JsonNull)
            put("disclosure", "Public AudioRecord state")
        }
        return listOf(videoJson, audioJson)
    }
}
