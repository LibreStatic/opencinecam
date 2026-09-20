/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class MediaPlaybackExactSurfaceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pts = listOf(0L, 400_000L, 1_100_000L, 1_700_000L)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun artifact(file: File) = LocalMediaArtifact(Uri.fromFile(file).toString(), file.name, "video/mp4", file.length(), file.lastModified() / 1000)
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    @Test fun exactNativeSessionRetiresPlayerOnDetachAndReattachesActualPositionWithoutAutoplay() {
        val file = createPrecisePlaybackFixture(context)
        val before = digest(file)
        val state = AtomicReference(PlaybackObservation())
        val sink = drainedSink()
        var session: MediaPlaybackSession? = null
        try {
            main {
                session = MediaPlaybackSession(context, artifact(file), PlaybackSettings(muted = true, loop = true), nativeSurfaceFrames = true) { state.set(it) }
                session!!.setSurface(sink.surface)
            }
            val first = await(state) { it.phase == PlaybackPhase.PAUSED && it.frameIndex == 0 && it.canPlay }
            assertEquals(pts, first.timeline!!.timestampsUs)
            assertEquals(0L, first.positionUs)
            assertNull(first.bitmap)
            assertNotNull("Exact Surface frame needs a confirmed render callback", first.renderedAtNs)
            assertTrue(first.renderedAtNs!! >= 0)
            main { assertNull("Initial exact native review must not allocate MediaPlayer", player(session!!)) }
            assertStable(state, PlaybackPhase.PAUSED, 300)
            main { session!!.seek(1_099_999L) }
            val floor = await(state) { it.phase == PlaybackPhase.PAUSED && it.frameIndex == 1 }
            assertEquals(400_000L, floor.positionUs)
            assertNull(floor.bitmap)
            main { session!!.play() }
            val playing = await(state) { it.phase == PlaybackPhase.PLAYING && it.positionUs >= 450_000L }
            assertNull(playing.frameIndex)
            assertNull(playing.bitmap)
            main { assertNotNull(player(session!!)) }
            lateinit var detached: PlaybackObservation
            main {
                session!!.setSurface(null)
                detached = state.get()
                assertFalse(detached.canPlay)
                assertNull("Surface loss retires the continuous producer", player(session!!))
                session!!.play()
                assertFalse(state.get().canPlay)
            }
            assertNotEquals(PlaybackPhase.ERROR, detached.phase)
            assertNotEquals(PlaybackPhase.PLAYING, detached.phase)
            assertNull(detached.frameIndex)
            assertNull(detached.renderedAtNs)
            val expectedIndex = first.timeline.indexAt(detached.positionUs)
            assertStable(state, detached.phase, 300)
            main { session!!.setSurface(sink.surface) }
            val attached = await(state) { it.phase == PlaybackPhase.PAUSED && it.frameIndex != null && it.canPlay }
            assertEquals(expectedIndex, attached.frameIndex)
            assertEquals(pts[expectedIndex], attached.positionUs)
            assertNotNull(attached.renderedAtNs)
            assertNull(attached.bitmap)
            main { assertNull("Reattachment must use exact decoder, not restart MediaPlayer", player(session!!)) }
            assertStable(state, PlaybackPhase.PAUSED, 500)
            // Suspend in the same main-thread turn as the seek, before its render callback.
            main {
                session!!.seek(1_100_000L)
                session!!.suspendOutput()
                assertNull(player(session!!))
                assertNull(state.get().frameIndex)
                assertNull(state.get().renderedAtNs)
                assertFalse(state.get().canPlay)
                session!!.play()
            }
            assertStable(state, PlaybackPhase.PAUSED, 300)
            main { session!!.resumeOutput() }
            val resumed = await(state) { it.phase == PlaybackPhase.PAUSED && it.frameIndex == 2 && it.canPlay }
            assertEquals(1_100_000L, resumed.positionUs)
            assertNotNull(resumed.renderedAtNs)
            assertNull(resumed.bitmap)
            main { assertNull(player(session!!)) }
            assertStable(state, PlaybackPhase.PAUSED, 300)
            // A holder recreation while suspended must retain the requested index, not the
            // previously displayed frame. This combines the two lifecycle transitions above.
            main {
                session!!.seek(400_000L); session!!.suspendOutput(); session!!.setSurface(null)
                assertEquals(400_000L, state.get().positionUs)
                assertNull(state.get().frameIndex)
                session!!.resumeOutput(); session!!.setSurface(sink.surface)
            }
            val recreated = await(state) { it.phase == PlaybackPhase.PAUSED && it.frameIndex == 1 && it.canPlay }
            assertEquals(400_000L, recreated.positionUs)
            assertNotNull(recreated.renderedAtNs)
            main { assertNull(player(session!!)) }
            assertStable(state, PlaybackPhase.PAUSED, 300)
            main { session!!.seek(1_700_000L); session!!.close(); session!!.close() }
            session!!.retirement.get(30, TimeUnit.SECONDS)
            val retired = state.get()
            main { session!!.setSurface(null); session!!.setSurface(sink.surface); session!!.play(); session!!.seek(0) }
            assertStable(state, retired.phase, 300)
            assertSame("Retirement fences already queued decoder callbacks", retired, state.get())
            assertEquals(before, digest(file))
            Log.i("E16ExactSurfaceStateProbe", "initialExactPtsUs=0 canPlayWithoutPlayer=true detachedActualPositionUs=${detached.positionUs} reattachedExactPtsUs=${attached.positionUs} autoStart=false suspendDuringSeekResumedExactPtsUs=${resumed.positionUs} recreatedPendingExactPtsUs=${recreated.positionUs} retirementCompleted=true originalUnchanged=true")
        } finally {
            main { session?.close() }
            session?.retirement?.get(30, TimeUnit.SECONDS)
            main { sink.setOnImageAvailableListener(null, null); sink.close() }
            assertTrue(file.delete())
        }
    }

    @Test fun invalidNativeMediaKeepsTerminalErrorAcrossSurfacesSettingsAndControls() {
        val file = File(context.cacheDir, "invalid-exact-surface-${UUID.randomUUID()}.mp4").apply { writeText("not an indexed movie") }
        val before = digest(file)
        val state = AtomicReference(PlaybackObservation())
        val observations = CopyOnWriteArrayList<PlaybackObservation>()
        val sink = drainedSink()
        var session: MediaPlaybackSession? = null
        try {
            main {
                session = MediaPlaybackSession(context, artifact(file), PlaybackSettings(muted = true), nativeSurfaceFrames = true) {
                    observations.add(it); state.set(it)
                }
                session!!.setSurface(sink.surface)
            }
            val failure = await(state, allowError = true) { it.phase == PlaybackPhase.ERROR }
            assertFalse(failure.detail.isNullOrBlank())
            assertFalse(failure.canPlay)
            val errorStart = observations.indexOf(failure)
            assertTrue(errorStart >= 0)
            main {
                session!!.setSurface(null)
                session!!.setSurface(sink.surface)
                session!!.update(PlaybackSettings(muted = false, loop = true, showFramePosition = false))
                session!!.play(); session!!.pause(); session!!.seek(0); session!!.step(1)
                session!!.suspendOutput(); session!!.resumeOutput()
                assertNull(player(session!!))
            }
            assertStable(state, PlaybackPhase.ERROR, 500)
            val after = state.get()
            assertEquals(failure.detail, after.detail)
            assertFalse(after.canPlay)
            assertNull(after.frameIndex)
            assertTrue("Surface/settings changes must never revive terminal failure",
                observations.drop(errorStart).all { it.phase == PlaybackPhase.ERROR && !it.canPlay })
            main { session!!.close() }
            session!!.retirement.get(30, TimeUnit.SECONDS)
            assertEquals(before, digest(file))
            Log.i("E16ExactSurfaceStateProbe", "invalidNativeMedia=ERROR surfacesAndControlsRemainTerminal=true retirementCompleted=true originalUnchanged=true")
        } finally {
            main { session?.close() }
            session?.retirement?.get(30, TimeUnit.SECONDS)
            main { sink.setOnImageAvailableListener(null, null); sink.close() }
            assertTrue(file.delete())
        }
    }

    /** Successful indexing proves only metadata/timeline access, never HDR decoding or display
     * acceptance. API30 separately preserves the explicit CPU preview version guard. */
    @Test fun pqHlgIndexingDoesNotRequireCpuPreviewAndApi30StillRejectsCpuFrames() {
        for (transfer in PreciseHdrTransfer.entries) {
            val file = createPreciseHdrPlaybackFixture(context, transfer)
            try {
                val before = digest(file)
                PreciseVideoFrames(context, Uri.fromFile(file).toString(), requireCpuPreview = false).use { reader ->
                    assertEquals(pts, reader.timeline.timestampsUs)
                    assertEquals(1, reader.timeline.indexAt(1_099_999L))
                    assertEquals(transfer, reader.hdrTransfer)
                    assertEquals(256, reader.displayWidth)
                    assertEquals(64, reader.displayHeight)
                    if (Build.VERSION.SDK_INT == 30) {
                        val failure = assertThrows(IllegalArgumentException::class.java) { reader.frame(0).bitmap.recycle() }
                        assertEquals("Precise HDR10 requires Android 13+ and a CPU-readable P010 decoder", failure.message)
                        Log.i("E16ExactSurfaceStateProbe", "api30CpuFrameRejected=${failure.message}")
                    }
                    assertEquals(pts, reader.timeline.timestampsUs)
                    Log.i("E16ExactSurfaceStateProbe", "api=${Build.VERSION.SDK_INT} transfer=$transfer indexedPtsUs=$pts hdrDecodeAcceptance=false")
                }
                assertEquals(before, digest(file))
            } finally { assertTrue(file.delete()) }
        }
    }

    private fun drainedSink(): ImageReader = ImageReader.newInstance(96, 64, ImageFormat.PRIVATE, 3).also { sink ->
        sink.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
    }
    private fun player(session: MediaPlaybackSession): MediaPlayer? = MediaPlaybackSession::class.java
        .getDeclaredField("player").apply { isAccessible = true }.get(session) as MediaPlayer?
    private fun await(state: AtomicReference<PlaybackObservation>, allowError: Boolean = false,
        predicate: (PlaybackObservation) -> Boolean): PlaybackObservation {
        // Observe the production 30s error as well as success; do not race its deadline.
        val deadline = System.nanoTime() + 35_000_000_000L
        while (System.nanoTime() - deadline < 0) {
            val value = state.get()
            if (!allowError) assertNotEquals(value.detail, PlaybackPhase.ERROR, value.phase)
            if (predicate(value)) return value
            Thread.sleep(20)
        }
        error("Native Surface session deadline: ${state.get()}")
    }
    private fun assertStable(state: AtomicReference<PlaybackObservation>, phase: PlaybackPhase, durationMs: Long) {
        val deadline = System.nanoTime() + durationMs * 1_000_000
        do {
            assertEquals("Unexpected restart or late callback: ${state.get()}", phase, state.get().phase)
            Thread.sleep(20)
        } while (System.nanoTime() - deadline < 0)
    }
}
