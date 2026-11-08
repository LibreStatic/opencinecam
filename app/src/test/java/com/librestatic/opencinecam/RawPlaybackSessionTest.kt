/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.media.ocraw.OcrawAudioChunk
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawFrameIndex
import com.librestatic.opencinecam.media.ocraw.OcrawFrameRead
import com.librestatic.opencinecam.media.ocraw.OcrawHeader
import com.librestatic.opencinecam.media.ocraw.OcrawMissingReason
import com.librestatic.opencinecam.media.ocraw.OcrawStreamFrame
import com.librestatic.opencinecam.media.ocraw.OcrawTimeBase
import com.librestatic.opencinecam.playback.RawDevelopParams
import com.librestatic.opencinecam.playback.RawDevelopQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

private const val INTERVAL_NS = 1_000_000_000L / 30
private const val MS = 1_000_000L

/** A serial loop on a virtual clock: tasks run in due order, and reads or renders move the clock. */
private class ManualLoop : RawPlaybackLoop {
    var now = 0L
    var quit = false
    private var sequence = 0L
    private class Task(val due: Long, val sequence: Long, val run: () -> Unit) { var cancelled = false }
    private val tasks = ArrayList<Task>()

    override fun post(task: () -> Unit) { if (!quit) tasks += Task(now, sequence++, task) }
    override fun postDelayed(delayNs: Long, task: () -> Unit): () -> Unit {
        val entry = Task(now + delayNs, sequence++, task)
        if (!quit) tasks += entry
        return { entry.cancelled = true }
    }
    override fun quit() { quit = true; tasks.clear() }

    fun runUntil(time: Long) {
        while (true) {
            val next = tasks.filter { !it.cancelled && it.due <= time }.minWithOrNull(compareBy<Task>({ it.due }, { it.sequence })) ?: break
            tasks.remove(next)
            if (next.due > now) now = next.due
            next.run()
        }
        tasks.removeAll { it.cancelled }
        if (time > now) now = time
    }
    fun idle() = runUntil(now)
    fun advance(ns: Long) = runUntil(now + ns)
}

private fun header(index: Int, timestampNs: Long, encoding: Int = OcrawFrameHeader.ENCODING_RAW10) =
    OcrawFrameHeader(width = 4, height = 2, rowBytes = 5, cropLeft = 0, cropTop = 0, sensorTimestampNs = timestampNs,
        frameNumber = index.toLong(), dataLength = 10, encoding = encoding)

private fun takeIndex(frames: Int, metadata: Map<String, String> = mapOf("fps" to "30/1"),
    audio: List<OcrawAudioChunk> = emptyList(),
    headerOf: (Int) -> OcrawFrameHeader? = { header(it, 5_000_000_000L + it * INTERVAL_NS) }) = OcrawFrameIndex(
    header = OcrawHeader(ByteArray(16)), fileSize = 0, validEnd = 0, tornTail = false,
    frames = List(frames) { OcrawStreamFrame(it.toLong(), it * 100L, it.toLong(), headerOf(it)) },
    metadata = metadata, frameMetadata = emptyMap(), timeBase = OcrawTimeBase(1, 1_000_000_000), hasEnd = true, errors = emptyList(),
    audio = audio)

private class FakeProvider(
    private val loop: ManualLoop,
    override val index: OcrawFrameIndex,
    private val events: MutableList<String>,
    var readNs: Long = 5 * MS,
    private val missing: Map<Int, OcrawMissingReason> = emptyMap(),
    private val audio: Map<Long, ByteArray> = emptyMap(),
) : RawFrameProvider {
    override fun readAudio(chunk: OcrawAudioChunk, dst: ByteBuffer): Int {
        check(!closed)
        val payload = audio[chunk.fileOffset] ?: throw java.io.IOException("audio CRC32C mismatch")
        dst.put(payload)
        return payload.size
    }
    private val pool = List(3) { RawFrameSlot(ByteBuffer.allocate(10), it) }
    val out = HashSet<RawFrameSlot>()
    val reads = ArrayList<Int>()
    var closed = false

    override fun acquire(): RawFrameSlot? {
        check(!closed)
        return pool.firstOrNull { it !in out }?.also { out += it }
    }
    override fun release(slot: RawFrameSlot) {
        check(out.remove(slot)) { "slot released twice" }
        events += "release"
    }
    override fun read(frameIndex: Int, slot: RawFrameSlot): OcrawFrameRead {
        check(!closed && slot in out)
        reads += frameIndex
        loop.now += readNs
        missing[frameIndex]?.let { return OcrawFrameRead.Missing(frameIndex, it) }
        return OcrawFrameRead.Frame(frameIndex, index.frames[frameIndex].header!!, 5, emptyMap(), null)
    }
    override fun close() { closed = true; events += "provider-close" }
}

private class FakeSink(private val loop: ManualLoop, private val events: MutableList<String>, var renderNs: Long = 10 * MS) : RawFrameSink {
    data class Render(val index: Int, val quality: RawDevelopQuality)
    val renders = ArrayList<Render>()
    /** Hold renders until [complete] instead of finishing them after [renderNs]. */
    var manual = false
    val held = ArrayList<() -> Unit>()
    val attached = ArrayList<Pair<Surface, Int>>()
    var detaches = 0
    var released = false
    val orientations = ArrayList<Int>()

    override fun attach(surface: Surface, width: Int, height: Int) { attached += surface to width }
    override fun detach() { detaches++ }
    override fun setOrientation(degrees: Int) { orientations += degrees }
    override fun render(buffer: ByteBuffer, header: OcrawFrameHeader, params: RawDevelopParams, quality: RawDevelopQuality,
        onDone: (Long, Boolean) -> Unit) {
        renders += Render(header.frameNumber.toInt(), quality)
        val time = renderNs
        if (manual) held += { onDone(time, true) } else loop.postDelayed(time) { onDone(time, true) }
    }
    fun complete() { val next = held.removeAt(0); next() }
    override fun release() { released = true; events += "sink-release" }
}


/**
 * An audio output on the virtual clock: it plays [speed] sample frames per take frame of loop time from
 * [play], never past what was written, and takes at most [capacityFrames] ahead of the played position.
 */
private class FakeAudio(private val loop: ManualLoop, private val events: MutableList<String>, val format: RawAudioFormat,
    private val speed: Double, private val capacityFrames: Long = 48_000) : RawAudioOutput {
    val samples = ArrayList<Short>()
    var playAt = -1L
    val mutes = ArrayList<Boolean>()
    var released = false
    private var written = 0L

    override fun write(pcm: ByteBuffer): Int {
        check(!released)
        val room = (capacityFrames - (written - playedFrames())) * format.frameBytes
        val count = minOf(room, pcm.remaining().toLong()).toInt() / format.frameBytes * format.frameBytes
        val view = pcm.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(count / 2) { samples += view.short }
        pcm.position(pcm.position() + count)
        written += count / format.frameBytes
        return count
    }
    override fun play() { check(!released); playAt = loop.now }
    override fun setMuted(muted: Boolean) { mutes += muted }
    override fun playedFrames(): Long {
        if (playAt < 0) return 0
        return minOf(((loop.now - playAt) * format.sampleRate * speed / 1e9).toLong(), written)
    }
    override fun release() { check(!released); released = true; events += "audio-release" }
}

private class FakeFocus(private val events: MutableList<String>) : RawAudioFocus {
    var grant = true
    var held = false
    var onLoss: (() -> Unit)? = null
    override fun request(onLoss: () -> Unit): Boolean {
        events += "focus-request"
        held = grant; if (grant) this.onLoss = onLoss
        return grant
    }
    override fun abandon() { events += "focus-abandon"; held = false; onLoss = null }
}

private const val RATE = 48_000
private const val CHUNK_FRAMES = 4_800 // 100 ms

/** Mono PCM whose every sample is its own sample index on the take's line (mod 2^15), so a position can be read back. */
private fun chunkPayload(startSample: Int, frames: Int = CHUNK_FRAMES): ByteArray {
    val buffer = ByteBuffer.allocate(frames * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    repeat(frames) { buffer.putShort(((startSample + it) and 0x7fff).toShort()) }
    return buffer.array()
}

/** 100 ms chunks from take time 0 (the first frame's chunk ticks are 0), [skip] left out as dropped audio. */
private fun audioChunks(count: Int, skip: Set<Int> = emptySet()): Pair<List<OcrawAudioChunk>, Map<Long, ByteArray>> {
    val chunks = (0 until count).filter { it !in skip }.map { OcrawAudioChunk(1000L + it, 1_000_000L + it, it * 100_000_000L, CHUNK_FRAMES * 2) }
    return chunks to chunks.associate { it.fileOffset to chunkPayload(((it.fileOffset - 1_000_000L) * CHUNK_FRAMES).toInt()) }
}

private val audioMetadata = mapOf("fps" to "30/1", "audioSampleRate" to "$RATE", "audioChannels" to "1", "audioSource" to "mic")

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RawPlaybackSessionTest {
    private val loop = ManualLoop()
    private val events = ArrayList<String>()
    private val observations = ArrayList<PlaybackObservation>()
    private val last get() = observations.last()
    private val surface by lazy { Surface(SurfaceTexture(0)) }
    private val audioOutputs = ArrayList<FakeAudio>()
    private var audioSpeed = 1.0
    private val focus = FakeFocus(events)

    private fun session(
        provider: FakeProvider,
        sink: FakeSink = FakeSink(loop, events),
        settings: PlaybackSettings = PlaybackSettings(),
        withSurface: Boolean = true,
    ): RawPlaybackSession {
        val session = RawPlaybackSession(settings, { provider }, { sink }, loop, { loop.now }, { it() },
            createAudio = { format -> FakeAudio(loop, events, format, audioSpeed).also { audioOutputs += it } }, audioFocus = focus) {
            observations += it
        }
        if (withSurface) { session.setSurface(surface); session.setSurfaceSize(1080, 2400) }
        loop.idle()
        loop.advance(50 * MS)
        return session
    }

    private fun provider(frames: Int = 120, readNs: Long = 5 * MS, missing: Map<Int, OcrawMissingReason> = emptyMap()) =
        FakeProvider(loop, takeIndex(frames), events, readNs, missing)

    @Test fun opensWithTheFirstFrameAtFullResolutionAndTheTakeGeometry() {
        val provider = provider(frames = 10)
        val sink = FakeSink(loop, events)
        session(provider, sink)
        assertEquals(listOf(FakeSink.Render(0, RawDevelopQuality.FULL)), sink.renders)
        assertEquals(PlaybackPhase.PAUSED, last.phase)
        assertEquals(0, last.frameIndex)
        assertEquals(10, last.timeline!!.timestampsUs.size)
        assertEquals(9 * INTERVAL_NS / 1000 + INTERVAL_NS / 1000, last.durationUs)
        assertEquals(4, last.videoWidth); assertEquals(2, last.videoHeight)
        assertEquals("RAW10", last.rawCodec)
        assertEquals(RawPlaybackRunMode.REALTIME, last.rawMode)
        assertNotNull(last.renderedAtNs)
        assertTrue(last.canPlay)
        assertEquals(1080, sink.attached.single().second)
    }

    @Test fun aTurnedTakeShowsItsUprightSizeAndTurnsTheSinkBeforeTheFirstFrame() {
        val sink = FakeSink(loop, events)
        session(FakeProvider(loop, takeIndex(10, mapOf("fps" to "30/1", "orientation" to "90")), events), sink)
        assertEquals(listOf(90), sink.orientations)
        assertEquals(2, last.videoWidth); assertEquals(4, last.videoHeight)
        assertEquals(listOf(FakeSink.Render(0, RawDevelopQuality.FULL)), sink.renders)
    }

    @Test fun aTakeWithoutOrientationIsShownAsStored() {
        val sink = FakeSink(loop, events)
        session(FakeProvider(loop, takeIndex(10, mapOf("fps" to "30/1", "orientation" to "sideways")), events), sink)
        assertEquals(listOf(0), sink.orientations)
        assertEquals(4, last.videoWidth); assertEquals(2, last.videoHeight)
    }

    @Test fun anEmptyTakeIsAnError() {
        session(FakeProvider(loop, takeIndex(0), events))
        assertEquals(PlaybackPhase.ERROR, last.phase)
        assertFalse(last.canPlay)
    }

    @Test fun aFastDeviceShowsEveryFrameAtHalfResolutionWithoutDrops() {
        val sink = FakeSink(loop, events)
        val session = session(provider(), sink)
        sink.renders.clear()
        session.play(); loop.advance(1_000 * MS)
        assertEquals(PlaybackPhase.PLAYING, last.phase)
        val shown = sink.renders.map { it.index }
        assertTrue(shown.size >= 29)
        assertEquals((0 until shown.size).toList(), shown)
        assertTrue(sink.renders.all { it.quality == RawDevelopQuality.HALF })
        assertEquals(0, last.droppedFrames)
        // The observation follows completed renders; the newest frame may still be on the GL stage.
        assertTrue(last.frameIndex!! in shown.last() - 1..shown.last())
    }

    @Test fun aSlowDeviceReadsTheLatestDueFrameAndCountsTheSkippedOnes() {
        val provider = provider(readNs = 45 * MS)
        val sink = FakeSink(loop, events, renderNs = 30 * MS)
        val session = session(provider, sink, PlaybackSettings(rawPlayback = RawPlaybackMode.FRAME_VIEWER))
        session.tryRealtime() // no automatic fall back, so the clock runs the whole second
        val before = provider.reads.size
        loop.advance(1_000 * MS)
        val reads = provider.reads.drop(before)
        assertTrue(reads.zipWithNext().all { (a, b) -> b > a })
        val skipped = reads.last() - reads.first() + 1 - reads.size
        assertTrue("expected drops, got $reads", skipped > 0)
        assertEquals(skipped, last.droppedFrames)
        // Never far behind the clock: the last frame read is at most two intervals behind due.
        val dueNow = ((loop.now - 50 * MS - 5 * MS) / INTERVAL_NS).toInt()
        assertTrue("read ${reads.last()} due $dueNow", reads.last() >= dueNow - 3)
        assertTrue(session.peakSlotsOut <= RawPlaybackSession.PIPELINE_DEPTH)
    }

    @Test fun thePipelineReadsOneFrameAheadWhileTheGlStageRendersAndNoMore() {
        val provider = provider()
        val sink = FakeSink(loop, events)
        val session = session(provider, sink)
        sink.manual = true
        session.play(); loop.idle()
        assertEquals(1, sink.held.size)
        assertEquals(2, provider.out.size)
        loop.advance(500 * MS)
        assertEquals(1, sink.held.size)
        assertEquals(2, provider.out.size)
        assertEquals(listOf(0, 0, 1), provider.reads)
        sink.complete(); loop.idle()
        assertEquals(2, provider.out.size)
        assertEquals(2, session.peakSlotsOut)
    }

    @Test fun playbackEndsOnTheLastFrameAndPlayStartsOverFromTheBeginning() {
        val sink = FakeSink(loop, events)
        val session = session(provider(frames = 10), sink)
        session.play(); loop.advance(2_000 * MS)
        assertEquals(PlaybackPhase.ENDED, last.phase)
        assertEquals(9, last.frameIndex)
        assertEquals(FakeSink.Render(9, RawDevelopQuality.FULL), sink.renders.last())
        assertTrue(last.canPlay)
        sink.renders.clear()
        session.play(); loop.advance(10 * MS)
        assertEquals(FakeSink.Render(0, RawDevelopQuality.HALF), sink.renders.first())
    }

    @Test fun loopWrapsToTheFirstFrame() {
        val sink = FakeSink(loop, events)
        val session = session(provider(frames = 10), sink, PlaybackSettings(loop = true))
        sink.renders.clear()
        session.play(); loop.advance(1_000 * MS)
        val shown = sink.renders.map { it.index }
        assertTrue(shown.count { it == 0 } >= 2)
        assertEquals(PlaybackPhase.PLAYING, last.phase)
        assertTrue(shown.zipWithNext().all { (a, b) -> b == a + 1 || a == 9 && b == 0 })
    }

    @Test fun pauseRedrawsTheShownFrameAtFullResolution() {
        val sink = FakeSink(loop, events)
        val session = session(provider(), sink)
        session.play(); loop.advance(300 * MS)
        val shownWhilePlaying = sink.renders.last { it.quality == RawDevelopQuality.HALF }.index
        session.pause(); loop.advance(100 * MS)
        assertEquals(FakeSink.Render(shownWhilePlaying, RawDevelopQuality.FULL), sink.renders.last())
        assertEquals(PlaybackPhase.PAUSED, last.phase)
        assertEquals(shownWhilePlaying, last.frameIndex)
    }

    @Test fun frameViewerSettingStartsFrameByFrameAndPlayDoesNothing() {
        val sink = FakeSink(loop, events)
        val session = session(provider(), sink, PlaybackSettings(rawPlayback = RawPlaybackMode.FRAME_VIEWER))
        assertEquals(RawPlaybackRunMode.FRAMES, last.rawMode)
        assertFalse(last.canPlay)
        assertFalse(last.rawFallback)
        session.play(); loop.advance(1_000 * MS)
        assertTrue(sink.renders.none { it.quality == RawDevelopQuality.HALF })
        assertEquals(PlaybackPhase.PAUSED, last.phase)
        session.step(1); loop.advance(50 * MS)
        assertEquals(FakeSink.Render(1, RawDevelopQuality.FULL), sink.renders.last())
        assertEquals(1, last.frameIndex)
    }

    @Test fun stepAndSeekMoveToTheNearestFrameAndClampAtTheEnds() {
        val session = session(provider(frames = 10), settings = PlaybackSettings(rawPlayback = RawPlaybackMode.FRAME_VIEWER))
        fun settle() = loop.advance(50 * MS)
        session.step(-1); settle(); assertEquals(0, last.frameIndex)
        session.seek(Long.MAX_VALUE / 4); settle(); assertEquals(9, last.frameIndex)
        session.step(1); settle(); assertEquals(9, last.frameIndex)
        session.seek(3 * INTERVAL_NS / 1000 + INTERVAL_NS * 6 / 10_000); settle(); assertEquals(4, last.frameIndex)
        session.seek(3 * INTERVAL_NS / 1000 + INTERVAL_NS * 4 / 10_000); settle(); assertEquals(3, last.frameIndex)
        session.seek(-5); settle(); assertEquals(0, last.frameIndex)
        session.step(3); settle(); assertEquals(3, last.frameIndex)
        assertEquals(last.timeline!!.timestampsUs[3], last.positionUs)
    }

    @Test fun aMissingFrameKeepsThePreviousImageButTheFrameNumberLandsOnIt() {
        val sink = FakeSink(loop, events)
        val session = session(provider(frames = 10, missing = mapOf(2 to OcrawMissingReason.CORRUPT, 3 to OcrawMissingReason.CODEC_UNAVAILABLE)),
            sink, PlaybackSettings(rawPlayback = RawPlaybackMode.FRAME_VIEWER))
        session.step(1); loop.advance(50 * MS)
        session.step(1); loop.advance(50 * MS)
        assertEquals(2, last.frameIndex)
        assertEquals(OcrawMissingReason.CORRUPT, last.missingFrame)
        assertEquals(FakeSink.Render(1, RawDevelopQuality.FULL), sink.renders.last())
        session.step(1); loop.advance(50 * MS)
        assertEquals(3, last.frameIndex)
        assertEquals(OcrawMissingReason.CODEC_UNAVAILABLE, last.missingFrame)
        session.step(1); loop.advance(50 * MS)
        assertEquals(4, last.frameIndex)
        assertNull(last.missingFrame)
        session.seek(last.timeline!!.timestampsUs[2]); loop.advance(50 * MS)
        assertEquals(2, last.frameIndex)
        assertEquals(OcrawMissingReason.CORRUPT, last.missingFrame)
        assertTrue(sink.renders.none { it.index == 2 || it.index == 3 })
    }

    @Test fun realTimePassesOverAMissingFrameWithoutDrawingIt() {
        val sink = FakeSink(loop, events)
        val session = session(provider(frames = 30, missing = mapOf(5 to OcrawMissingReason.DECODE_FAILED)), sink)
        session.play(); loop.advance(400 * MS)
        assertTrue(observations.any { it.frameIndex == 5 && it.missingFrame == OcrawMissingReason.DECODE_FAILED && it.phase == PlaybackPhase.PLAYING })
        assertTrue(sink.renders.none { it.index == 5 })
        assertTrue(last.frameIndex!! > 5)
        assertNull(last.missingFrame)
        assertEquals(0, last.droppedFrames)
    }

    @Test fun fallbackRuleNeedsTenSamplesAndJudgesTheMedianAndTheDropRatio() {
        val slow = List(9) { 100 * MS }
        assertFalse(rawPlaybackShouldFallBack(slow, INTERVAL_NS, 9, 90))
        assertTrue(rawPlaybackShouldFallBack(slow + 100 * MS, INTERVAL_NS, 10, 0))
        val fast = List(10) { 10 * MS }
        assertFalse(rawPlaybackShouldFallBack(fast, INTERVAL_NS, 60, 0))
        assertFalse(rawPlaybackShouldFallBack(fast, INTERVAL_NS, 45, 15)) // exactly a quarter
        assertTrue(rawPlaybackShouldFallBack(fast, INTERVAL_NS, 44, 16))
        // Median, not mean: a few slow frames do not decide it; most slow frames do.
        assertFalse(rawPlaybackShouldFallBack(List(7) { 10 * MS } + List(3) { 500 * MS }, INTERVAL_NS, 10, 0))
        assertTrue(rawPlaybackShouldFallBack(List(4) { 10 * MS } + List(6) { 40 * MS }, INTERVAL_NS, 10, 0))
        // Even count: the mean of the middle two.
        assertTrue(rawPlaybackShouldFallBack(List(5) { 30 * MS } + List(5) { 40 * MS }, INTERVAL_NS, 10, 0))
    }

    @Test fun aDeviceThatCannotKeepUpFallsBackToTheFrameViewer() {
        val sink = FakeSink(loop, events, renderNs = 40 * MS)
        val session = session(provider(readNs = 30 * MS), sink)
        session.play(); loop.advance(1_500 * MS)
        assertEquals(RawPlaybackRunMode.REALTIME, last.rawMode) // still inside the window
        loop.advance(2_000 * MS)
        assertEquals(RawPlaybackRunMode.FRAMES, last.rawMode)
        assertTrue(last.rawFallback)
        assertEquals(PlaybackPhase.PAUSED, last.phase)
        assertFalse(last.canPlay)
        assertEquals(RawDevelopQuality.FULL, sink.renders.last().quality)
        val count = sink.renders.size
        loop.advance(1_000 * MS)
        assertEquals(count, sink.renders.size)
    }

    @Test fun aDeviceThatKeepsUpStaysInRealTime() {
        val session = session(provider(frames = 150))
        session.play(); loop.advance(4_000 * MS)
        assertEquals(RawPlaybackRunMode.REALTIME, last.rawMode)
        assertFalse(last.rawFallback)
        assertEquals(PlaybackPhase.PLAYING, last.phase)
    }

    @Test fun tryRealtimePlaysAgainAndNeverFallsBackAutomatically() {
        val sink = FakeSink(loop, events, renderNs = 40 * MS)
        val session = session(provider(frames = 300, readNs = 30 * MS), sink)
        session.play(); loop.advance(3_500 * MS)
        assertTrue(last.rawFallback)
        session.tryRealtime(); loop.advance(10 * MS)
        assertEquals(RawPlaybackRunMode.REALTIME, last.rawMode)
        assertFalse(last.rawFallback)
        assertEquals(PlaybackPhase.PLAYING, last.phase)
        loop.advance(4_000 * MS)
        assertEquals(RawPlaybackRunMode.REALTIME, last.rawMode)
        assertEquals(PlaybackPhase.PLAYING, last.phase)
        assertTrue(last.droppedFrames > 0)
    }

    @Test fun tryRealtimeOverridesTheFrameViewerSetting() {
        val sink = FakeSink(loop, events)
        val session = session(provider(), sink, PlaybackSettings(rawPlayback = RawPlaybackMode.FRAME_VIEWER))
        session.tryRealtime(); loop.advance(200 * MS)
        assertEquals(RawPlaybackRunMode.REALTIME, last.rawMode)
        assertEquals(PlaybackPhase.PLAYING, last.phase)
        assertTrue(sink.renders.any { it.quality == RawDevelopQuality.HALF })
    }

    @Test fun switchingTheSettingToFrameViewerStopsRealTime() {
        val sink = FakeSink(loop, events)
        val session = session(provider(), sink)
        session.play(); loop.advance(200 * MS)
        session.update(PlaybackSettings(rawPlayback = RawPlaybackMode.FRAME_VIEWER)); loop.advance(50 * MS)
        assertEquals(RawPlaybackRunMode.FRAMES, last.rawMode)
        assertFalse(last.rawFallback)
        assertEquals(PlaybackPhase.PAUSED, last.phase)
        assertEquals(RawDevelopQuality.FULL, sink.renders.last().quality)
    }

    @Test fun losingTheSurfaceDetachesAtOnceAndPauses() {
        val sink = FakeSink(loop, events)
        val session = session(provider(), sink)
        session.play(); loop.advance(200 * MS)
        session.setSurface(null)
        assertEquals(1, sink.detaches) // before the loop runs: the SurfaceHolder may destroy it now
        loop.advance(50 * MS)
        assertEquals(PlaybackPhase.PAUSED, last.phase)
        assertFalse(last.canPlay)
        session.play(); loop.advance(50 * MS)
        assertEquals(PlaybackPhase.PAUSED, last.phase)
        session.setSurface(surface); loop.idle()
        assertEquals(2, sink.attached.size)
        assertTrue(last.canPlay)
    }

    @Test fun closeWaitsForTheGlStageThenReleasesSlotsSourceAndRendererInOrder() {
        val provider = provider()
        val sink = FakeSink(loop, events)
        val session = session(provider, sink)
        sink.manual = true
        session.play(); loop.idle()
        assertEquals(2, provider.out.size)
        val seen = observations.size
        events.clear()
        session.close(); loop.idle()
        assertEquals(listOf("release"), events) // the read-ahead slot; the rendering one is still the GL stage's
        assertFalse(provider.closed)
        assertFalse(session.retirement.isDone)
        sink.complete(); loop.idle()
        assertEquals(listOf("release", "release", "provider-close", "sink-release"), events)
        assertTrue(provider.out.isEmpty())
        assertTrue(session.retirement.isDone)
        assertTrue(loop.quit)
        assertEquals(seen, observations.size)
    }

    @Test fun closeDoesNotWaitForeverForALostRender() {
        val provider = provider()
        val sink = FakeSink(loop, events)
        val session = session(provider, sink)
        sink.manual = true
        session.play(); loop.idle()
        session.close(); loop.advance(RawPlaybackSession.TEARDOWN_TIMEOUT_NS + MS)
        assertTrue(provider.closed)
        assertTrue(sink.released)
        assertTrue(provider.out.isEmpty())
        assertTrue(session.retirement.isDone)
    }

    @Test fun closeBeforeTheTakeOpensNeverOpensIt() {
        var opened = false
        val session = RawPlaybackSession(PlaybackSettings(), { opened = true; provider() }, { FakeSink(loop, events) }, loop,
            { loop.now }, { it() }) { observations += it }
        session.close(); loop.idle()
        assertFalse(opened)
        assertTrue(session.retirement.isDone)
        assertTrue(observations.isEmpty())
    }


    // ---- Audio (PLAN-071 §7.1) ----

    private fun audioProvider(frames: Int = 120, chunks: Int = 40, skip: Set<Int> = emptySet()): FakeProvider {
        val (index, payloads) = audioChunks(chunks, skip)
        return FakeProvider(loop, takeIndex(frames, audioMetadata, index), events, audio = payloads)
    }

    @Test fun theAudioPlanPlacesChunksByTheirTicksRelativeToTheFirstFrame() {
        val boot = 7_000_000_000_000L // boot-time ticks: absolute values times 10^6 would overflow
        val index = takeIndex(3, audioMetadata, listOf(
            OcrawAudioChunk(9, 500, boot + 250_000_000L, 9_600), OcrawAudioChunk(8, 400, boot - 50_000_000L, 9_600)),
        ).let { it.copy(frames = it.frames.map { frame -> frame.copy(timestampTicks = boot + frame.timestampTicks) }) }
        val plan = rawAudioPlan(index, rawFrameTimesUs(index))!!
        assertEquals(RawAudioFormat(RATE, 1), plan.format)
        assertEquals(listOf(-2_400L, 12_000L), plan.chunks.map { it.startSample })
        assertEquals(listOf(4_800L, 4_800L), plan.chunks.map { it.frames })
        assertEquals(1, plan.firstChunkEndingAfter(2_400))
        // No keys, bad keys or no chunks: silent.
        assertNull(rawAudioPlan(index.copy(metadata = mapOf("fps" to "30/1")), rawFrameTimesUs(index)))
        assertNull(rawAudioFormat(mapOf("audioSampleRate" to "48000", "audioChannels" to "6")))
        assertNull(rawAudioPlan(index.copy(audio = emptyList()), rawFrameTimesUs(index)))
        assertEquals(RawAudioFormat(44_100, 2), rawAudioFormat(mapOf("audioSampleRate" to "44100", "audioChannels" to "2")))
    }

    @Test fun theFeederFillsGapsWithSilenceAndKeepsEveryChunkOnItsSample() {
        val (chunks, payloads) = audioChunks(5, skip = setOf(2))
        val plan = RawAudioPlan(RawAudioFormat(RATE, 1), chunks.map { RawAudioPlanChunk(it, (it.fileOffset - 1_000_000L) * CHUNK_FRAMES, CHUNK_FRAMES.toLong()) })
        val out = drain(RawAudioFeeder(plan, 0, 5L * CHUNK_FRAMES) { chunk, dst -> payloads.getValue(chunk.fileOffset).let { dst.put(it); it.size } })
        assertEquals(5 * CHUNK_FRAMES, out.size)
        for (sample in out.indices) {
            val expected = if (sample / CHUNK_FRAMES == 2) 0 else sample and 0x7fff
            assertEquals("sample $sample", expected, out[sample].toInt())
        }
    }

    @Test fun theFeederPlaysJitterOnButCutsAnOverlapAndSkipsADamagedChunk() {
        val format = RawAudioFormat(RATE, 1)
        // The second chunk lands 5 ms late (within the 20 ms tolerance), the third 50 ms early (an overlap).
        val starts = listOf(0L, 4_800L + 240, 9_600L - 2_400, 14_400L)
        val chunks = starts.mapIndexed { i, start -> RawAudioPlanChunk(OcrawAudioChunk(i.toLong(), i.toLong(), 0, 9_600), start, 4_800) }
        val feeder = RawAudioFeeder(RawAudioPlan(format, chunks), 0, 19_200) { chunk, dst ->
            if (chunk.fileOffset == 3L) throw java.io.IOException("crc")
            dst.put(chunkPayload(chunk.fileOffset.toInt() * 10_000)); 9_600
        }
        val out = drain(feeder)
        assertEquals(19_200, out.size)
        // Silence only for the gap before the last chunk (2,400) and the damaged chunk itself (4,800).
        assertEquals(7_200L, feeder.silenceFrames)
        assertEquals(10_000, out[4_800].toInt()) // the late chunk follows on with no silence before it
        // The early chunk's overlap (2,400 samples past the line's 9,600) is cut: it resumes 2,400 samples in.
        assertEquals((20_000 + 2_400) and 0x7fff, out[9_600].toInt())
        assertEquals(0, out[12_000].toInt())
        assertEquals(0, out[14_400].toInt())
    }

    private fun drain(feeder: RawAudioFeeder): List<Short> {
        val out = ArrayList<Short>()
        while (true) {
            val block = feeder.next()?.order(java.nio.ByteOrder.LITTLE_ENDIAN) ?: break
            while (block.remaining() >= 2) out += block.short
        }
        return out
    }

    @Test fun theAudioIsTheMasterClock() {
        audioSpeed = 0.5 // the output plays at half speed: the picture must follow it, not the wall clock
        val session = session(audioProvider())
        session.play(); loop.advance(1_000 * MS)
        assertEquals(1, audioOutputs.size)
        assertEquals(PlaybackPhase.PLAYING, last.phase)
        assertTrue("frame ${last.frameIndex}", last.frameIndex!! in 13..16)
        assertEquals(RATE, last.rawAudioSampleRate)
        session.close(); loop.idle()
    }

    @Test fun aTakeWithoutAudioRunsOnTheWallClock() {
        audioSpeed = 0.5
        val silent = session(provider())
        silent.play(); loop.advance(1_000 * MS)
        assertTrue(audioOutputs.isEmpty()) // never asked for an output
        assertNull(last.rawAudioSampleRate)
        assertTrue("frame ${last.frameIndex}", last.frameIndex!! in 28..30)
        silent.close(); loop.idle()
    }

    @Test fun anOutputThatHasNotStartedHoldsTheFirstFrame() {
        audioSpeed = 0.0
        val session = session(audioProvider())
        session.play(); loop.advance(500 * MS)
        assertEquals(0, last.frameIndex)
        assertEquals(PlaybackPhase.PLAYING, last.phase)
        session.close(); loop.idle()
    }

    @Test fun playStreamsFromTheShownFrameAndPauseReleasesTheOutputAndFocus() {
        val session = session(audioProvider())
        session.play(); loop.advance(300 * MS)
        val out = audioOutputs.single()
        assertEquals(listOf(false), out.mutes)
        assertTrue(focus.held)
        assertEquals(0, out.samples.first().toInt())
        // About a second is buffered ahead, every sample in order.
        assertTrue(out.samples.size >= RATE)
        out.samples.forEachIndexed { i, sample -> assertEquals((i and 0x7fff).toShort(), sample) }
        session.pause(); loop.idle()
        assertTrue(out.released)
        assertFalse(focus.held)
    }

    @Test fun aSeekWhilePlayingRestartsTheAudioAtTheRightSample() {
        val session = session(audioProvider())
        session.play(); loop.advance(300 * MS)
        val first = audioOutputs.single()
        val target = last.timeline!!.timestampsUs[45] // 1,499,999 µs
        session.seek(target); loop.advance(5 * MS)
        assertTrue(first.released)
        assertEquals(2, audioOutputs.size)
        val second = audioOutputs[1]
        val expected = Math.floorDiv(target * RATE, 1_000_000L).toInt() // 71,999
        assertEquals((expected and 0x7fff).toShort(), second.samples.first())
        assertEquals(PlaybackPhase.PLAYING, last.phase)
        loop.advance(200 * MS)
        assertTrue("frame ${last.frameIndex}", last.frameIndex!! in 49..51)
        session.close(); loop.idle()
    }

    @Test fun droppedAudioIsFilledWithSilenceSoTheClockRunsOn() {
        val session = session(audioProvider(skip = setOf(1, 2)))
        session.play(); loop.advance(1_000 * MS)
        val out = audioOutputs.single()
        assertTrue(out.samples.subList(CHUNK_FRAMES, 3 * CHUNK_FRAMES).all { it.toInt() == 0 })
        assertEquals((3 * CHUNK_FRAMES).toShort(), out.samples[3 * CHUNK_FRAMES])
        assertTrue("frame ${last.frameIndex}", last.frameIndex!! in 28..30)
        session.close(); loop.idle()
    }

    @Test fun mutingSilencesTheOutputWithoutRestartingItAndGivesUpTheFocus() {
        val session = session(audioProvider(), settings = PlaybackSettings(muted = true))
        session.play(); loop.advance(300 * MS)
        val out = audioOutputs.single()
        assertEquals(listOf(true), out.mutes)
        assertFalse("focus-request" in events)
        val before = last.frameIndex!!
        session.update(PlaybackSettings(muted = false)); loop.advance(300 * MS)
        assertEquals(listOf(true, false), out.mutes)
        assertTrue(focus.held)
        session.update(PlaybackSettings(muted = true)); loop.advance(300 * MS)
        assertEquals(listOf(true, false, true), out.mutes)
        assertFalse(focus.held)
        assertEquals(1, audioOutputs.size) // the same output all along: the sync carries on
        assertFalse(out.released)
        assertTrue("frame ${last.frameIndex}", last.frameIndex!! - before in 16..19)
        session.close(); loop.idle()
    }

    @Test fun aRefusedFocusDoesNotStartAndALostOnePauses() {
        focus.grant = false
        val session = session(audioProvider())
        session.play(); loop.advance(100 * MS)
        assertTrue(audioOutputs.isEmpty())
        assertEquals(PlaybackPhase.PAUSED, last.phase)
        focus.grant = true
        session.play(); loop.advance(100 * MS)
        assertEquals(PlaybackPhase.PLAYING, last.phase)
        focus.onLoss!!.invoke(); loop.advance(50 * MS)
        assertEquals(PlaybackPhase.PAUSED, last.phase)
        assertTrue(audioOutputs.single().released)
        session.close(); loop.idle()
    }

    @Test fun theFrameViewerIsSilent() {
        val session = session(audioProvider(), settings = PlaybackSettings(rawPlayback = RawPlaybackMode.FRAME_VIEWER))
        session.play(); session.step(1); loop.advance(300 * MS)
        assertTrue(audioOutputs.isEmpty())
        assertEquals(RATE, last.rawAudioSampleRate)
        session.close(); loop.idle()
    }

    @Test fun theAudioCarriesTheTakeToItsEndAndALoopRestartsItAtTheStart() {
        val session = session(audioProvider(frames = 30, chunks = 10))
        session.play(); loop.advance(1_500 * MS)
        assertEquals(PlaybackPhase.ENDED, last.phase)
        assertTrue(audioOutputs.single().released)
        observations.clear()

        val looping = session(audioProvider(frames = 30, chunks = 10), settings = PlaybackSettings(loop = true))
        looping.play(); loop.advance(1_200 * MS)
        assertEquals(PlaybackPhase.PLAYING, last.phase)
        assertEquals(3, audioOutputs.size)
        assertTrue(audioOutputs[1].released)
        assertEquals(0, audioOutputs[2].samples.first().toInt())
        assertTrue("frame ${last.frameIndex}", last.frameIndex!! in 4..7)
        looping.close(); loop.idle()
    }

    @Test fun closeStopsTheSoundAtOnceThenReleasesSlotsSourceAndRendererInOrder() {
        val provider = audioProvider()
        val sink = FakeSink(loop, events)
        val session = session(provider, sink)
        session.play(); loop.advance(100 * MS)
        sink.manual = true
        loop.advance(100 * MS)
        events.clear()
        session.close(); loop.idle()
        assertEquals("audio-release", events.first())
        assertTrue(audioOutputs.single().released)
        assertFalse(focus.held)
        assertFalse(provider.closed)
        while (sink.held.isNotEmpty()) sink.complete()
        loop.idle()
        assertEquals(listOf("provider-close", "sink-release"), events.filter { it == "provider-close" || it == "sink-release" })
        assertTrue(events.indexOf("audio-release") < events.indexOf("provider-close"))
        assertTrue(session.retirement.isDone)
    }

    @Test fun frameTimesComeFromSensorTimestampsAndBridgeAnUnreadableHeader() {
        val index = takeIndex(4, headerOf = { if (it == 2) null else header(it, 1_000_000_000L + it * 40_000_000L) })
        assertEquals(listOf(0L, 40_000L, 80_000L, 120_000L), rawFrameTimesUs(index).toList())
        assertEquals(INTERVAL_NS, rawFrameIntervalNs(mapOf("fps" to "30/1"), longArrayOf(0, 40_000)))
        assertEquals(40_000_000L, rawFrameIntervalNs(emptyMap(), longArrayOf(0, 40_000, 80_000)))
        val mixed = takeIndex(2, headerOf = {
            if (it == 0) header(0, 0) else OcrawFrameHeader(4, 2, 5, 0, 0, 1_000L, 1, 7, OcrawFrameHeader.ENCODING_LJ92)
        })
        assertEquals("RAW10+LJ92", rawCodecLabel(mixed))
        assertEquals("LJ92", rawCodecLabel(takeIndex(1, headerOf = { OcrawFrameHeader(4, 2, 5, 0, 0, 0, 0, 7, OcrawFrameHeader.ENCODING_LJ92) })))
        val quantised = takeIndex(2, headerOf = {
            OcrawFrameHeader(4, 2, 5, 0, 0, it * 1_000L, it.toLong(), 7,
                if (it == 0) OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED else OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK)
        })
        assertEquals("BBP+BBPQ", rawCodecLabel(quantised))
    }
}
