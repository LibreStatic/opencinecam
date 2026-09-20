/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class MediaPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun artifact(file: File) = LocalMediaArtifact(Uri.fromFile(file).toString(), file.name, "video/mp4", file.length(), file.lastModified()/1000)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitFor(state: AtomicReference<PlaybackObservation>, predicate: (PlaybackObservation) -> Boolean): PlaybackObservation {
        val deadline = System.nanoTime() + 20_000_000_000
        while (System.nanoTime() < deadline) {
            val value = state.get()
            assertNotEquals(value.detail, PlaybackPhase.ERROR, value.phase)
            if (predicate(value)) return value
            Thread.sleep(20)
        }
        error("Playback wait expired: ${state.get()}")
    }
    @Test fun realPlayerPauseSeekStepAndCompletionDistinguishEstimatedTimeFromExactPts() {
        val file = createPrecisePlaybackFixture(context)
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        val state = AtomicReference(PlaybackObservation())
        val sink = ImageReader.newInstance(96,64,ImageFormat.PRIVATE,3)
        sink.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        var session: MediaPlaybackSession? = null
        try {
            main { session = MediaPlaybackSession(context,artifact(file),PlaybackSettings(muted=true)) { state.set(it) }; session!!.setSurface(sink.surface) }
            val first = waitFor(state) { it.phase == PlaybackPhase.PAUSED && it.durationUs > 0 }
            assertEquals(0,first.frameIndex); assertEquals(0L,first.positionUs)
            main { session!!.seek(1_099_999) }
            val floor = waitFor(state) { it.phase == PlaybackPhase.PAUSED && it.frameIndex == 1 }
            assertEquals(400_000L,floor.positionUs)
            main { session!!.step(1) }
            assertEquals(1_100_000L, waitFor(state) { it.phase == PlaybackPhase.PAUSED && it.frameIndex == 2 }.positionUs)
            main { session!!.step(-1) }
            waitFor(state) { it.phase == PlaybackPhase.PAUSED && it.frameIndex == 1 }
            main { session!!.play() }
            val playing = waitFor(state) { it.phase == PlaybackPhase.PLAYING && it.positionUs > 450_000 }
            assertNull(playing.frameIndex); assertNull(playing.bitmap)
            main { session!!.pause() }
            val paused = waitFor(state) { it.phase == PlaybackPhase.PAUSED && it.frameIndex != null }
            assertEquals(paused.timeline!!.timestampsUs[paused.frameIndex!!], paused.positionUs)
            main { session!!.seek(Long.MAX_VALUE) }
            assertEquals(1_700_000L, waitFor(state) { it.phase == PlaybackPhase.PAUSED && it.frameIndex == 3 }.positionUs)
            main { session!!.play() }
            val ended = waitFor(state) { it.phase == PlaybackPhase.ENDED }
            assertEquals(3,ended.frameIndex); assertEquals(1_700_000L,ended.positionUs)
            assertArrayEquals(hash,MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally { main { session?.close() }; sink.close(); assertTrue(file.delete()) }
    }
    @Test fun surfaceRetirementCancelsPendingStartAndCloseFencesReaderCallbacks() {
        val file = createPrecisePlaybackFixture(context)
        val state = AtomicReference(PlaybackObservation())
        val sink = ImageReader.newInstance(96,64,ImageFormat.PRIVATE,3)
        var session: MediaPlaybackSession? = null
        try {
            main { session=MediaPlaybackSession(context,artifact(file),PlaybackSettings(muted=true)) { state.set(it) };session!!.setSurface(sink.surface) }
            waitFor(state) { it.phase == PlaybackPhase.PAUSED && it.durationUs > 0 }
            main { session!!.play(); assertFalse(state.get().canPlay); session!!.setSurface(null); session!!.play(); assertFalse(state.get().canPlay) }
            waitFor(state) { it.phase == PlaybackPhase.PAUSED }
            assertFalse(state.get().canPlay)
            main { session!!.setSurface(sink.surface) }
            waitFor(state) { it.phase == PlaybackPhase.PAUSED && it.canPlay }
            Thread.sleep(300)
            assertEquals(PlaybackPhase.PAUSED,state.get().phase)
            main { session!!.seek(1_100_000);session!!.close();session!!.close() }
            val retired = state.get()
            val field = MediaPlaybackSession::class.java.getDeclaredField("worker").apply { isAccessible = true }
            assertTrue((field.get(session) as java.util.concurrent.ExecutorService).awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(500)
            assertSame(retired,state.get())
        } finally { main { session?.close() };sink.close();assertTrue(file.delete()) }
    }
    @Test fun loopRestartsRotatedVideoWithoutChangingPausedGeometry() {
        val file = createPrecisePlaybackFixture(context, rotation=90)
        val state = AtomicReference(PlaybackObservation())
        val sink = ImageReader.newInstance(64,96,ImageFormat.PRIVATE,3)
        sink.setOnImageAvailableListener({ it.acquireLatestImage()?.close() },Handler(Looper.getMainLooper()))
        var session: MediaPlaybackSession? = null
        try {
            main { session=MediaPlaybackSession(context,artifact(file),PlaybackSettings(muted=true,loop=true)) { state.set(it) };session!!.setSurface(sink.surface) }
            val first=waitFor(state) { it.phase==PlaybackPhase.PAUSED && it.videoWidth>0 && it.durationUs>0 }
            assertEquals(64,first.bitmap!!.width);assertEquals(96,first.bitmap.height)
            assertEquals(first.bitmap.width,first.videoWidth);assertEquals(first.bitmap.height,first.videoHeight)
            main { session!!.play() }
            waitFor(state) { it.phase==PlaybackPhase.PLAYING && it.positionUs>1_100_000 }
            waitFor(state) { it.phase==PlaybackPhase.PLAYING && it.positionUs<400_000 }
            main { session!!.update(PlaybackSettings(muted=true,loop=false));session!!.pause() }
            val paused=waitFor(state) { it.phase==PlaybackPhase.PAUSED && it.frameIndex!=null }
            assertEquals(64,paused.bitmap!!.width);assertEquals(96,paused.bitmap.height)
            assertEquals(paused.timeline!!.timestampsUs[paused.frameIndex!!],paused.positionUs)
        } finally { main { session?.close() };sink.close();assertTrue(file.delete()) }
    }
    @Test fun actualPcmAudioPlaysSeeksAndPausesWithoutInventingVideoFrames() {
        val file=File(context.cacheDir,"review-audio-${java.util.UUID.randomUUID()}.wav")
        val pcm=ByteArray(48_000*2)
        val header=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36+pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(48_000).putInt(96_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size).array()
        file.outputStream().use { it.write(header);it.write(pcm) }
        val before=file.readBytes()
        val state=AtomicReference(PlaybackObservation())
        var session: MediaPlaybackSession?=null
        try {
            main { session=MediaPlaybackSession(context,artifact(file).copy(mimeType="audio/wav"),PlaybackSettings()) { state.set(it) } }
            val first=waitFor(state) { it.phase==PlaybackPhase.PAUSED && it.durationUs>0 }
            assertEquals(1_000_000L,first.durationUs);assertNull(first.timeline);assertNull(first.frameIndex)
            main { session!!.seek(400_000) }
            val sought=waitFor(state) { it.phase==PlaybackPhase.PAUSED && it.positionUs>=390_000 }
            assertTrue(sought.positionUs<=410_000)
            main { session!!.play() }
            waitFor(state) { it.phase==PlaybackPhase.PLAYING && it.positionUs>450_000 }
            main { session!!.pause() }
            val paused=waitFor(state) { it.phase==PlaybackPhase.PAUSED }
            assertNull(paused.frameIndex);assertNull(paused.bitmap)
            assertArrayEquals(before,file.readBytes())
        } finally { main { session?.close() };assertTrue(file.delete()) }
    }
    @Test fun invalidMediaErrorRemainsTerminalWhenOptionsChangeAndReaderRetires() {
        val file = File(context.cacheDir,"bad-review-${java.util.UUID.randomUUID()}.mp4").apply { writeText("not a movie") }
        val state = AtomicReference(PlaybackObservation())
        var session: MediaPlaybackSession? = null
        try {
            main { session=MediaPlaybackSession(context,artifact(file),PlaybackSettings()) { state.set(it) } }
            val deadline=System.nanoTime()+10_000_000_000
            while(state.get().phase != PlaybackPhase.ERROR && System.nanoTime()<deadline) Thread.sleep(20)
            assertEquals(PlaybackPhase.ERROR,state.get().phase)
            main { session!!.update(PlaybackSettings(muted=true,loop=true));session!!.play();session!!.seek(0) }
            Thread.sleep(300)
            assertEquals(PlaybackPhase.ERROR,state.get().phase)
            assertEquals("not a movie",file.readText())
        } finally { main { session?.close() };assertTrue(file.delete()) }
    }
}
