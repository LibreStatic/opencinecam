/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.GallerySettings
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.ProxySettings
import com.librestatic.opencinecam.productionSlateJson
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

/** Abrupt process death during live proxy create, and backgrounded create; anchors stay synthetic. */
class ProxyDeathRecoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    /** Deterministic identity shared by the death phase and the verification phase of one run name. */
    private fun runToken() = "rev66-" + requireNotNull(InstrumentationRegistry.getArguments().getString("e17EvidenceRun"))
    private fun runId() = UUID.nameUUIDFromBytes(runToken().toByteArray()).toString()
    private fun deathAttemptId() = UUID.nameUUIDFromBytes((runToken() + "-attempt").toByteArray()).toString()

    /** The kill phase ends the instrumentation process; only the host harness may select a phase. */
    private fun explicitPhase(method: String) {
        assumeTrue("Requires explicit host-controlled proxy process-death invocation",
            InstrumentationRegistry.getArguments().getString("class") == "${javaClass.name}#$method")
    }

    @Test fun killPhaseParksWithLiveCandidateUntilForcedStop() = runBlocking<Unit> {
        explicitPhase("killPhaseParksWithLiveCandidateUntilForcedStop")
        val id = runId(); val token = runToken()
        val attemptId = deathAttemptId()
        val harness = DeathHarness(id, token)
        try {
            val take = harness.publishAndAwaitTake()
            val candidate = File(context.cacheDir, "proxy-$attemptId.mp4")
            val repository = MediaProxyRepository(context)
            kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async {
                runCatching { repository.create(take, ProxySettings(640, 1), attemptId) }
            }
            withTimeout(30000) {
                while (!candidate.exists() ||
                    Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith("proxy-transcoder-") }) {
                    SystemClock.sleep(5)
                }
            }
            // Readiness marker consumed by the host harness: PID + boundary, then abrupt self-death.
            val marker = File(context.noBackupFilesDir, "e66-death-ready.txt")
            marker.writeText("${android.os.Process.myPid()} PROXY_CREATE_LIVE $attemptId ${take.id}")
            android.util.Log.i("E17ProxyDeath", "marker-written pid=${android.os.Process.myPid()} attempt=$attemptId")
            SystemClock.sleep(800)
            android.util.Log.i("E17ProxyDeath", "self-kill-now attempt=$attemptId")
            android.os.Process.killProcess(android.os.Process.myPid())
        } finally {
            harness.close()
        }
    }

    @Test fun verifyPhaseAfterForcedStopKeepsTakeIntactAndSuccessorCreateWorks() = runBlocking<Unit> {
        explicitPhase("verifyPhaseAfterForcedStopKeepsTakeIntactAndSuccessorCreateWorks")
        val id = runId(); val token = runToken()
        val deathAttempt = deathAttemptId()
        val evidencePrefix = proxyTestEvidencePrefix()
        val harness = DeathHarness(id, token)
        var result: MediaProxyResult? = null
        var take: LocalMediaTake? = null
        try {
            // Locate the take published by the death phase; MediaStore rows survive force-stop.
            withTimeout(20000) {
                while (take == null) {
                    val entry = LocalMediaRepository(context).page(GallerySettings(), token).takes.singleOrNull()
                    if (entry?.relationStatus == LocalMediaRelationStatus.DECLARED &&
                        runCatching { LocalMediaRepository(context).freshSnapshot(entry) }.isSuccess) take = entry
                    else delay(100)
                }
            }
            take = requireNotNull(take)
            assertEquals("Death must not commit a proxy", null, MediaProxyRepository(context).existing(take))
            assertTrue("Death attempt must not appear in catalog",
                MediaProxyRepository(context).catalog().none { it.result.proxyId == deathAttempt })
            val staleCandidate = File(context.cacheDir, "proxy-$deathAttempt.mp4")
            val hashes = (take.originals + take.metadata).associate { it.uri to proxyHash(context, it.uri.toUri()) }
            for ((uri, hash) in hashes) assertEquals(hash, proxyHash(context, uri.toUri()))

            result = withTimeout(90_000) { MediaProxyRepository(context).create(take, ProxySettings(640, 1)) }
            val proxy = requireNotNull(result)
            assertEquals(proxy, MediaProxyRepository(context).existing(take))
            val entry = MediaProxyRepository(context).catalog().single { it.takeId == take.id }
            val after = probeProxyMedia(context, proxy.proxyUri.toUri())
            verifyProxyVideoCorrespondence(probeProxyMedia(context, Uri.parse(take.primary.uri)), after,
                VideoDisplayGeometry(96, 64))
            assertEquals("audio/mp4a-latm", after.audio!!.mime)
            val receipt = resolver.openInputStream(proxy.metadataUri.toUri())!!.use {
                Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
            assertEquals("opencinecam.proxy-sidecar.v2",
                receipt.getValue("sidecarSource").jsonObject.getValue("schema").jsonPrimitive.content)
            assertEquals(hashes, receipt.getValue("sidecarSource").jsonObject.getValue("sourceSha256")
                .jsonObject.mapValues { it.value.jsonPrimitive.content })

            val evidence = File(context.getExternalFilesDir(null), "$evidencePrefix-death-verify")
            check(evidence.mkdir()) { "Refusing to overwrite death-verify evidence" }
            resolver.openInputStream(proxy.proxyUri.toUri())!!.use { input ->
                File(evidence, "proxy.mp4").outputStream().use { input.copyTo(it) }
            }
            File(evidence, "receipt.json").writeText(receipt.toString())
            File(evidence, "recovery.json").writeText(
                """{"deathAttemptId":"$deathAttempt","staleCandidateExisted":${staleCandidate.exists()},"staleCandidateBytes":${staleCandidate.length()},"takeId":"${take.id}","successorCommitted":true}""")
            MediaProxyRepository(context).deleteProxy(entry) { _, _ -> }
            result = null
            assertEquals(null, MediaProxyRepository(context).existing(take))
        } finally {
            result?.let { proxy -> MediaProxyRepository(context).deleteProxy(requireNotNull(take), proxy) { _, _ -> } }
            harness.close()
        }
    }

    @Test fun proxyCreateCompletesWhileAppIsBackgrounded() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val evidencePrefix = proxyTestEvidencePrefix()
        val id = UUID.randomUUID().toString()
        val token = "rev66-bg-$id"
        val namespace = MediaNamespace(id, true)
        val owned = mutableListOf<Uri>()
        val directory = File(context.cacheDir, token).also { check(it.mkdir()) }
        val videoFile = createPreciseGopFixture(context)
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
        try {
            val fixture = ProxyPcmProbeDeviceTest()
            val pcm = fixture.pcm16(48_000, 1)
            val audioFile = File(directory, "source.wav")
            fixture.writeWav(audioFile, pcm, 48_000, 1)
            val video = publish(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "$token.mp4", "video/mp4", "DCIM", videoFile.readBytes())
            val audio = publish(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "$token.wav", "audio/wav", "Music", audioFile.readBytes())
            val docs = ConcurrentDocuments.documents(id, video, audio, 48_000L)
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
            val hashes = (published.originals + published.metadata).associate { it.uri to proxyHash(context, it.uri.toUri()) }
            val job = async(Dispatchers.Default) {
                runCatching { MediaProxyRepository(context).create(published, ProxySettings(640, 1)).also { result = it } }
            }
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(home)
            val outcome = withTimeout(120_000) { job.await() }
            result = outcome.getOrThrow()
            assertNotNull("Backgrounded create must complete", result)
            val proxy = requireNotNull(result)
            val receipt = resolver.openInputStream(proxy.metadataUri.toUri())!!.use {
                Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
            assertEquals(hashes, receipt.getValue("sidecarSource").jsonObject.getValue("sourceSha256")
                .jsonObject.mapValues { it.value.jsonPrimitive.content })
            val evidence = File(context.getExternalFilesDir(null), "$evidencePrefix-death-background")
            check(evidence.mkdir()) { "Refusing to overwrite background evidence" }
            resolver.openInputStream(proxy.proxyUri.toUri())!!.use { input ->
                File(evidence, "proxy.mp4").outputStream().use { input.copyTo(it) }
            }
            File(evidence, "receipt.json").writeText(receipt.toString())
            MediaProxyRepository(context).catalog().single { it.takeId == published.id }.let { entry ->
                MediaProxyRepository(context).deleteProxy(entry) { _, _ -> }
            }
            result = null
        } finally {
            result?.let { proxy -> MediaProxyRepository(context).deleteProxy(requireNotNull(take), proxy) { _, _ -> } }
            for (uri in owned.asReversed()) check(resolver.delete(uri, null, null) == 1)
            check(videoFile.delete()); check(directory.listFiles()!!.all { it.delete() }); check(directory.delete())
        }
    }
}

/** Shared publication machinery for the death-recovery phases; deterministic identity per run name. */
internal class DeathHarness(private val id: String, private val token: String) : AutoCloseable {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val namespace = MediaNamespace(id, true)
    private val owned = mutableListOf<Uri>()
    // A killed predecessor phase cannot clean up; tolerate its leftover cache directory.
    private val directory = File(context.cacheDir, token).apply { check(mkdirs() || isDirectory) }
    private val videoFile = createPreciseGopFixture(context)
    var publishedTake: LocalMediaTake? = null
        private set

    fun publishAndAwaitTake(): LocalMediaTake {
        val fixture = ProxyPcmProbeDeviceTest()
        val pcm = fixture.pcm16(48_000, 1)
        val audioFile = File(directory, "source.wav")
        fixture.writeWav(audioFile, pcm, 48_000, 1)
        fun publish(collection: Uri, name: String, mime: String, root: String, bytes: ByteArray): LocalMediaArtifact {
            val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
                put("_display_name", name); put("mime_type", mime); put("relative_path", namespace.path(root)); put("is_pending", 1)
            })).also(owned::add)
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            check(resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null) == 1)
            return LocalMediaArtifact(uri.toString(), name, mime, bytes.size.toLong(), 0)
        }
        val video = publish(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "$token.mp4", "video/mp4", "DCIM", videoFile.readBytes())
        val audio = publish(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "$token.wav", "audio/wav", "Music", audioFile.readBytes())
        val docs = ConcurrentDocuments.documents(id, video, audio, 48_000L)
        for ((index, doc) in docs.withIndex()) publish(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "$token-$index.json", "application/json", "Download", doc.toString().toByteArray())
        kotlinx.coroutines.runBlocking {
            withTimeout(20000) {
                while (publishedTake == null) {
                    val entry = LocalMediaRepository(context).page(GallerySettings(), token).takes.singleOrNull()
                    if (entry?.relationStatus == LocalMediaRelationStatus.DECLARED &&
                        runCatching { LocalMediaRepository(context).freshSnapshot(entry) }.isSuccess) publishedTake = entry
                    else delay(100)
                }
            }
        }
        return requireNotNull(publishedTake)
    }

    override fun close() {
        check(videoFile.delete())
        check(directory.listFiles()!!.all { it.delete() }); check(directory.delete())
    }
}

/** Document builder shared by Rev65/Rev66 test files for the simple S16 mono 48kHz take. */
internal object ConcurrentDocuments {
    fun documents(id: String, video: LocalMediaArtifact, audio: LocalMediaArtifact, frames: Long): List<JsonObject> {
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
