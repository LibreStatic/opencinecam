/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentUris
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.util.AtomicFile
import android.util.Log
import androidx.core.net.toUri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DebugTraceUtil
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.GallerySettings
import com.librestatic.opencinecam.galleryMenuAction
import com.librestatic.opencinecam.MainActivity
import com.librestatic.opencinecam.ProxySettings
import com.librestatic.opencinecam.R
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule

/** kill/verify are host-only phases. The ordinary case cancels an actual encoder operation.
 * The long input repeats immutable AVC access units, not an artificial codec/queue implementation.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ProxyQueueProcessDeathTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val stateFile get() = File(context.noBackupFilesDir, "e17-proxy-death-state.json")
    private val readyFile get() = File(context.noBackupFilesDir, "e17-proxy-death-ready.txt")
    private val globalStore get() = ProxyJobStore(File(context.filesDir, "proxy-queue"))
    private val videoCollection get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val metadataCollection get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val settings = ProxySettings(640, 1)

    private data class Packet(val bytes: ByteArray, val ptsUs: Long, val flags: Int)
    private data class Fixture(val take: LocalMediaTake, val hash: String, val probe: ProxyMediaProbe)
    private data class Witness(val encoder: Int, val muxer: Int)

    private fun explicitPhase(method: String) {
        assumeTrue("Requires explicit host-controlled proxy process-death invocation",
            InstrumentationRegistry.getArguments().getString("class") == "${javaClass.name}#$method")
        assertEquals("FRAMES", InstrumentationRegistry.getArguments().getString("e17Boundary"))
    }

    @Test fun killPhase() = runBlocking<Unit> {
        explicitPhase("killPhase")
        check(!stateFile.exists() && !readyFile.exists()) { "Previous proxy death evidence must be resolved first" }
        check(globalStore.read().none { it.status in ACTIVE }) { "An existing proxy request owns this process" }
        val fixture = longFixture("e17-proxy-death-${UUID.randomUUID()}")
        val queue = MediaProxyQueue.get(context)
        var id: String? = null
        val previousTrace = DebugTraceUtil.enableTracing
        try {
            DebugTraceUtil.reset(); DebugTraceUtil.enableTracing = true
            id = queue.enqueue(fixture.take, settings)
            val state = fixtureState(fixture, id).put("encoderFrames", 0).put("muxerFrames", 0)
            writeSynced(stateFile, state.toString())
            val witness = awaitNativeFrames(queue, id)
            assertEquals(ProxyJobStatus.RUNNING, globalStore.read().single { it.id == id }.status)
            assertTrue(candidate(id).isFile && candidate(id).length() > 0)
            state.put("encoderFrames", witness.encoder).put("muxerFrames", witness.muxer)
            writeSynced(stateFile, state.toString())
            writeSynced(readyFile, "${Process.myPid()} FRAMES")
            Log.i(TAG, "READY pid=${Process.myPid()} jobId=$id encoderFrames=${witness.encoder} muxerFrames=${witness.muxer} totalFrames=$TOTAL_FRAMES candidateBytes=${candidate(id).length()}")
            withTimeout(120_000) {
                while (true) {
                    assertEquals("Export completed before host killed its live encoder", ProxyJobStatus.RUNNING,
                        queue.states.value.jobs.single { it.id == id }.status)
                    delay(25) // Observes the host boundary; never holds or sleeps a codec thread.
                }
            }
        } finally {
            // SIGKILL never executes this. A failed/missed host checkpoint retires before cleanup.
            withContext(NonCancellable) {
                queue.shutdown()
                id?.let { clean(fixture.take, it); globalStore.write(globalStore.read().filterNot { job -> job.id == it }) }
                if (id == null) cleanOriginal(fixture.take)
                stateFile.delete(); readyFile.delete()
                DebugTraceUtil.enableTracing = previousTrace
            }
        }
    }

    @Test fun verifyPhase() = runBlocking<Unit> {
        explicitPhase("verifyPhase")
        val state = JSONObject(stateFile.readText())
        val id = state.getString("jobId")
        assertTrue(canonicalMediaId(id)); assertEquals(TOTAL_FRAMES, state.getInt("totalFrames"))
        assertEquals("FRAMES", readyFile.readText().substringAfter(' '))
        val oldPid = readyFile.readText().substringBefore(' ').toInt()
        assertEquals(state.getInt("pid"), oldPid); assertNotEquals(oldPid, Process.myPid())
        assertTrue(state.getInt("encoderFrames") in 3 until TOTAL_FRAMES)
        assertTrue(state.getInt("muxerFrames") in 3 until TOTAL_FRAMES)
        val interrupted = globalStore.read().single { it.id == id }
        assertEquals(ProxyJobStatus.RUNNING, interrupted.status)
        assertEquals(1, interrupted.attempts); assertEquals(settings, interrupted.settings)
        assertEquals(state.getString("originalUri"), interrupted.take.primary.uri)
        assertEquals(state.getString("takeId"), interrupted.take.id)
        assertEquals(state.getString("originalSha256"), proxyHash(context, interrupted.take.primary.uri.toUri()))
        val sourceProbe = probeProxyMedia(context, interrupted.take.primary.uri.toUri())
        assertEquals(state.getString("ptsSha256"), timestampsHash(sourceProbe.video.timestampsUs))
        assertEquals(state.getLong("durationUs"), sourceProbe.video.durationUs)
        assertEquals(TOTAL_FRAMES, sourceProbe.video.timestampsUs.size)
        val fixture = Fixture(interrupted.take, state.getString("originalSha256"), sourceProbe)
        var scenario: ActivityScenario<MainActivity>? = null
        var queue: ProxyJobQueue? = null
        val previousTrace = DebugTraceUtil.enableTracing
        try {
            DebugTraceUtil.reset(); DebugTraceUtil.enableTracing = true
            // MainActivity.onCreate starts the durable singleton before this test obtains it.
            scenario = ActivityScenario.launch(MainActivity::class.java)
            queue = MediaProxyQueue.get(context)
            val restartedWitness = awaitNativeFrames(queue, id)
            openProxy(interrupted.take)
            assertRunningDialog(id)
            compose.onNodeWithTag("media-proxy-close", true).performClick()
            openProxy(interrupted.take)
            assertRunningDialog(id)
            val reopenedWitness = awaitNativeFrames(queue, id)
            assertTrue(reopenedWitness.encoder >= restartedWitness.encoder)
            assertEquals(2, globalStore.read().single { it.id == id }.attempts)
            Log.i(TAG, "UI_REOPEN_DURING_FRAMES jobId=$id encoderBefore=${restartedWitness.encoder} encoderAfter=${reopenedWitness.encoder} muxerAfter=${reopenedWitness.muxer} attempts=2")
            DebugTraceUtil.enableTracing = previousTrace
            val done = terminal(queue, id)
            assertEquals(done.error, ProxyJobStatus.SUCCEEDED, done.status)
            assertEquals(2, done.attempts); assertEquals(interrupted.settings, done.settings)
            val result = verifyResult(fixture, id)
            assertEquals(done, globalStore.read().single { it.id == id })
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("media-proxy-result", true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media-proxy-error", true).assertDoesNotExist()
            compose.onNodeWithTag("media-proxy-close", true).performClick()
            scenario.recreate()
            openProxy(interrupted.take)
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("media-proxy-result", true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media-proxy-job-id", true).assertTextContains(id, substring = true)
            compose.onNodeWithTag("media-proxy-create", true).assertDoesNotExist()
            compose.onNodeWithTag("media-proxy-close", true).performClick()
            assertEquals(id, queue.enqueue(fixture.take, ProxySettings(1920, 8)))
            assertEquals(done, queue.states.value.jobs.single { it.id == id })
            assertEquals(result, MediaProxyRepository(context).reconcile(fixture.take, id))
            assertEquals(result, MediaProxyRepository(context).existing(fixture.take))
            assertEquals(2, ownedRows(id).size)
            Log.i(TAG, "PASS death oldPid=$oldPid newPid=${Process.myPid()} jobId=$id attempts=${done.attempts} frames=${result.frames} durationUs=${result.durationUs} sourceHash=${fixture.hash} onePair=true activityRecreated=true idempotent=true")
        } finally {
            withContext(NonCancellable) {
                scenario?.close()
                queue?.shutdown()
                DebugTraceUtil.enableTracing = previousTrace
                clean(fixture.take, id)
                globalStore.write(globalStore.read().filterNot { it.id == id })
                stateFile.delete(); readyFile.delete()
            }
        }
    }

    @Test fun cancelDuringNativeFramesRetiresAndRetriesSameRequest() = runBlocking<Unit> {
        // Isolated durable directory avoids shutting down the application's singleton in ordinary
        // test discovery. Both engine methods remain the real production repository/transcoder.
        val token = "e17-proxy-cancel-${UUID.randomUUID()}"
        val directory = File(context.filesDir, token)
        val store = ProxyJobStore(directory)
        val repository = MediaProxyRepository(context)
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob) = repository.reconcile(job.take, job.id)
            override suspend fun create(job: ProxyJob) = repository.create(job.take, job.settings, job.id)
        })
        val fixture = longFixture(token)
        val beforeThreads = nativeThreads()
        val previousTrace = DebugTraceUtil.enableTracing
        var id: String? = null
        try {
            DebugTraceUtil.reset(); DebugTraceUtil.enableTracing = true
            id = queue.enqueue(fixture.take, settings)
            val witness = awaitNativeFrames(queue, id)
            assertTrue(nativeThreads().any { it !in beforeThreads })
            queue.cancel(id)
            val cancelled = terminal(queue, id)
            assertEquals(cancelled.error, ProxyJobStatus.CANCELLED, cancelled.status)
            assertEquals(1, cancelled.attempts)
            assertTrue(nativeThreads().all { it in beforeThreads })
            assertFalse(candidate(id).exists()); assertTrue(ownedRows(id).isEmpty())
            assertNull(repository.existing(fixture.take))
            assertEquals(fixture.hash, proxyHash(context, fixture.take.primary.uri.toUri()))
            DebugTraceUtil.enableTracing = previousTrace
            queue.retry(id)
            val done = terminal(queue, id)
            assertEquals(done.error, ProxyJobStatus.SUCCEEDED, done.status)
            assertEquals(2, done.attempts); assertEquals(settings, done.settings)
            val result = verifyResult(fixture, id)
            assertEquals(done, store.read().single())
            assertTrue(nativeThreads().all { it in beforeThreads })
            Log.i(TAG, "PASS cancel jobId=$id encoderFrames=${witness.encoder} muxerFrames=${witness.muxer} totalFrames=$TOTAL_FRAMES cancelledBeforeEos=true retiredBeforeRetry=true attempts=${done.attempts} finalFrames=${result.frames} finalDurationUs=${result.durationUs} sourceHash=${fixture.hash}")
        } finally {
            withContext(NonCancellable) {
                queue.shutdown(); DebugTraceUtil.enableTracing = previousTrace
                id?.let { clean(fixture.take, it) } ?: cleanOriginal(fixture.take)
                check(!directory.exists() || directory.deleteRecursively())
            }
        }
    }

    private fun openProxy(take: LocalMediaTake) {
        if (compose.onAllNodesWithTag("gallery-list", true).fetchSemanticsNodes().isEmpty()) {
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("media-action").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media-action").performClick()
        }
        compose.onNodeWithTag("gallery-list").performScrollToKey("controls")
        compose.onNodeWithTag("gallery-search").performTextReplacement(take.primary.name.removeSuffix(".mp4"))
        compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithTag("gallery-list").performScrollToKey(take.id) }.isSuccess
        }
        compose.galleryMenuAction(take.id, "proxy")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("media-proxy-job-id", true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertRunningDialog(id: String) {
        compose.onNodeWithTag("media-proxy-job-id", true).assertTextContains(id, substring = true)
        compose.onNodeWithTag("media-proxy-job-status", true).assertTextEquals(context.getString(R.string.proxy_running))
        compose.onNodeWithTag("media-proxy-cancel", true).assertIsEnabled()
        compose.onNodeWithTag("media-proxy-create", true).assertDoesNotExist()
        compose.onNodeWithTag("media-proxy-error", true).assertDoesNotExist()
    }

    private suspend fun awaitNativeFrames(queue: ProxyJobQueue, id: String): Witness = withTimeout(90_000) {
        while (true) {
            val state = queue.states.value
            check(state.error == null) { state.error.orEmpty() }
            val job = state.jobs.single { it.id == id }
            check(job.status in ACTIVE) { "Proxy became ${job.status} before native checkpoint: ${job.error}" }
            val trace = JSONObject(DebugTraceUtil.generateTraceSummary().substringBefore("--- End of summary---"))
            val encoder = trace.optJSONObject("VideoEncoder")?.optJSONObject("ProducedOutput")?.optInt("count") ?: 0
            val muxer = trace.optJSONObject("Muxer")?.optJSONObject("AcceptedInput")?.optInt("count") ?: 0
            if (encoder >= 3 && muxer >= 3) {
                assertTrue("Encoder finished before checkpoint", encoder < TOTAL_FRAMES)
                assertTrue("Muxer finished before checkpoint", muxer < TOTAL_FRAMES)
                assertEquals(ProxyJobStatus.RUNNING, job.status)
                assertTrue(candidate(id).isFile && candidate(id).length() > 0)
                Log.i(TAG, "NATIVE_FRAMES jobId=$id encoder=$encoder muxer=$muxer total=$TOTAL_FRAMES")
                return@withTimeout Witness(encoder, muxer)
            }
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE") error("Native checkpoint did not arrive")
    }

    private suspend fun terminal(queue: ProxyJobQueue, id: String): ProxyJob = withTimeout(600_000) {
        queue.states.first { state -> state.error != null || state.jobs.any { it.id == id && it.status !in ACTIVE } }
            .also { check(it.error == null) { it.error.orEmpty() } }.jobs.single { it.id == id }
    }

    private suspend fun verifyResult(fixture: Fixture, id: String): MediaProxyResult {
        val result = requireNotNull(MediaProxyRepository(context).existing(fixture.take))
        assertEquals(id, result.proxyId); assertEquals(TOTAL_FRAMES, result.frames)
        assertEquals(settings.videoBitrateMbps * 1_000_000, result.requestedBitrate)
        assertEquals(128, result.width); assertEquals(96, result.height)
        val output = probeProxyMedia(context, result.proxyUri.toUri())
        assertEquals(fixture.probe.video.timestampsUs.sorted(), output.video.timestampsUs.sorted())
        assertEquals(fixture.probe.video.durationUs, output.video.durationUs)
        assertEquals(fixture.probe.video.durationUs, result.durationUs)
        assertNull(output.audio)
        verifyProxyCorrespondence(fixture.probe, output, VideoDisplayGeometry(128, 96))
        assertEquals(fixture.hash, proxyHash(context, fixture.take.primary.uri.toUri()))
        assertFalse(candidate(id).exists())
        assertEquals(2, ownedRows(id).size)
        return result
    }

    private suspend fun longFixture(token: String): Fixture = withContext(Dispatchers.IO) {
        val seed = File(context.cacheDir, "$token-seed.mp4")
        val expanded = File(context.cacheDir, "$token-source.mp4")
        var original: Uri? = null
        try {
            val immutable = instrumentation.context.assets.open("e1-video.mp4").use { it.readBytes() }
            assertEquals(5404, immutable.size)
            assertEquals(SEED_SHA, digest(immutable)); seed.writeBytes(immutable)
            val extractor = MediaExtractor()
            val packets = mutableListOf<Packet>()
            val format: MediaFormat
            try {
                extractor.setDataSource(seed.path); assertEquals(1, extractor.trackCount)
                format = extractor.getTrackFormat(0)
                assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
                assertEquals(128, format.getInteger(MediaFormat.KEY_WIDTH)); assertEquals(96, format.getInteger(MediaFormat.KEY_HEIGHT))
                extractor.selectTrack(0)
                while (extractor.sampleTime >= 0) {
                    val bytes = ByteArray(extractor.sampleSize.toInt())
                    assertEquals(bytes.size, extractor.readSampleData(ByteBuffer.wrap(bytes), 0))
                    val flags = extractor.sampleFlags
                    assertTrue("Encrypted, partial or unknown source sample flags: $flags",
                        flags == 0 || flags == MediaExtractor.SAMPLE_FLAG_SYNC)
                    packets += Packet(bytes, extractor.sampleTime, flags)
                    if (!extractor.advance()) break
                }
            } finally { extractor.release() }
            assertEquals(12, packets.size); assertEquals(0L, packets.first().ptsUs)
            assertEquals("Each repeated GOP must start at a source sync sample", MediaExtractor.SAMPLE_FLAG_SYNC, packets.first().flags)
            Log.i(TAG, "SEED_NATIVE_FLAGS ${packets.map { it.flags }} ptsUs=${packets.map { it.ptsUs }}")
            assertTrue(packets.zipWithNext().all { (a, b) -> a.ptsUs < b.ptsUs })
            // Round the repeat interval upward to an integral millisecond (also exact at MP4's
            // 90kHz clock), preserving all original intra-cycle PTS and adding only boundary gap.
            val cycleUs = ((format.getLong(MediaFormat.KEY_DURATION) + 999) / 1000) * 1000
            assertTrue(cycleUs > packets.last().ptsUs)
            val muxer = MediaMuxer(expanded.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val track = muxer.addTrack(format); muxer.start()
                repeat(REPEATS) { cycle -> packets.forEach { packet ->
                    val codecFlags = if (packet.flags == MediaExtractor.SAMPLE_FLAG_SYNC) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    val info = MediaCodec.BufferInfo().apply { set(0, packet.bytes.size, cycle * cycleUs + packet.ptsUs, codecFlags) }
                    muxer.writeSampleData(track, ByteBuffer.wrap(packet.bytes), info)
                } }
                muxer.stop()
            } finally { muxer.release() }
            // Independent extraction proves packet/CSD identity; no additional encoder or decoder.
            val actual = MediaExtractor()
            try {
                actual.setDataSource(expanded.path); assertEquals(1, actual.trackCount)
                val outputFormat = actual.getTrackFormat(0)
                for (key in listOf("csd-0", "csd-1")) {
                    val a = requireNotNull(format.getByteBuffer(key)).duplicate()
                    val b = requireNotNull(outputFormat.getByteBuffer(key)).duplicate()
                    assertEquals(a, b)
                }
                actual.selectTrack(0)
                var index = 0; var previous = -1L
                while (actual.sampleTime >= 0) {
                    assertTrue(index < TOTAL_FRAMES)
                    val packet = packets[index % packets.size]
                    val bytes = ByteArray(actual.sampleSize.toInt())
                    assertEquals(bytes.size, actual.readSampleData(ByteBuffer.wrap(bytes), 0)); assertArrayEquals(packet.bytes, bytes)
                    assertEquals("Remux changed source sample flags at index=$index", packet.flags, actual.sampleFlags)
                    assertTrue(actual.sampleTime > previous)
                    val planned = index / packets.size * cycleUs + packet.ptsUs
                    assertTrue("MP4 clock rounding exceeds one 90kHz tick", kotlin.math.abs(actual.sampleTime - planned) <= 12)
                    previous = actual.sampleTime; index++
                    if (!actual.advance()) break
                }
                assertEquals(TOTAL_FRAMES, index)
                assertTrue(outputFormat.getLong(MediaFormat.KEY_DURATION) > previous)
            } finally { actual.release() }
            val uri = requireNotNull(resolver.insert(videoCollection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$token.mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/OpenCineCam/")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            })).also { original = it }
            resolver.openOutputStream(uri, "w")!!.use { output -> expanded.inputStream().use { it.copyTo(output) } }
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val take = withTimeout(10_000) {
                var found: LocalMediaTake? = null
                while (found == null) {
                    found = LocalMediaRepository(context).page(GallerySettings(), token).takes.singleOrNull()
                        ?.takeIf { it.primary.uri == uri.toString() && it.primary.sizeBytes == expanded.length() }
                    if (found == null) delay(25)
                }
                found
            }
            val probe = probeProxyMedia(context, uri)
            assertEquals(TOTAL_FRAMES, probe.video.timestampsUs.size)
            assertNull(probe.audio); assertEquals(VideoDisplayGeometry(128, 96), probe.geometry)
            val hash = proxyHash(context, uri)
            assertEquals(digest(expanded.readBytes()), hash)
            Log.i(TAG, "FIXTURE uri=$uri bytes=${expanded.length()} sha256=$hash seed=$SEED_SHA repeatedPackets=$TOTAL_FRAMES cycleUs=$cycleUs durationUs=${probe.video.durationUs} ptsSha256=${timestampsHash(probe.video.timestampsUs)} csdAndEveryPacketExact=true")
            Fixture(take, hash, probe)
        } catch (failure: Throwable) {
            original?.let { deleteOwned(it) }; throw failure
        } finally {
            check(!seed.exists() || seed.delete()); check(!expanded.exists() || expanded.delete())
        }
    }

    private fun fixtureState(fixture: Fixture, id: String) = JSONObject()
        .put("jobId", id).put("pid", Process.myPid()).put("boundary", "FRAMES")
        .put("originalUri", fixture.take.primary.uri).put("takeId", fixture.take.id)
        .put("originalSha256", fixture.hash).put("originalBytes", fixture.take.primary.sizeBytes)
        .put("totalFrames", TOTAL_FRAMES).put("durationUs", fixture.probe.video.durationUs)
        .put("ptsSha256", timestampsHash(fixture.probe.video.timestampsUs))
        .put("maxLongEdge", settings.maxLongEdge).put("videoBitrateMbps", settings.videoBitrateMbps)

    private fun ownedRows(id: String): List<Uri> {
        require(canonicalMediaId(id))
        return listOf(videoCollection to "Movies", metadataCollection to "Download").flatMap { (collection, root) ->
            @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
            resolver.query(all, arrayOf("_id"), "relative_path = ? AND owner_package_name = ?",
                arrayOf("$root/OpenCineCamProxies/$id/", context.packageName), null)!!.use { cursor ->
                assertTrue(cursor.count <= 1)
                buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
            }
        }
    }
    private fun clean(take: LocalMediaTake, id: String) {
        ownedRows(id).forEach(::deleteOwned); assertTrue(ownedRows(id).isEmpty())
        cleanOriginal(take)
        AtomicFile(File(context.filesDir, "media-proxies/${digest(take.id.toByteArray())}.json")).delete()
        check(!candidate(id).exists() || candidate(id).delete())
    }
    private fun cleanOriginal(take: LocalMediaTake) = deleteOwned(take.primary.uri.toUri())
    private fun deleteOwned(uri: Uri) {
        val identity = requireNotNull(mediaDeleteIdentity(uri.toString()))
        val collection = if (identity.collection == MediaDeleteCollection.VIDEO) videoCollection else metadataCollection
        val deleted = resolver.delete(collection, "_id = ? AND owner_package_name = ?", arrayOf(identity.id.toString(), context.packageName))
        assertTrue(deleted in 0..1)
    }
    private fun candidate(id: String) = File(context.cacheDir, "proxy-$id.mp4")
    private fun nativeThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("proxy-transcoder-") }.map { it.id }.toSet()
    private fun writeSynced(file: File, text: String) = file.outputStream().use { it.write(text.toByteArray()); it.fd.sync() }
    private fun timestampsHash(times: List<Long>) = digest(times.joinToString(",").toByteArray())
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).proxyHex()

    private companion object {
        const val TAG = "E17ProxyDeath"
        const val SEED_SHA = "a32963539eefe4fc0d14e76770d51e49c25108a31b9eda9176a022a94a9a99b6"
        const val REPEATS = 1000
        const val TOTAL_FRAMES = 12 * REPEATS
        val ACTIVE = setOf(ProxyJobStatus.QUEUED, ProxyJobStatus.RUNNING, ProxyJobStatus.CANCELLING)
    }
}
