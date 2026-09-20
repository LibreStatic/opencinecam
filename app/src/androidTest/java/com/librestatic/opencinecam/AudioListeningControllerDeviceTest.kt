/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.PcmMeterEncoding
import com.librestatic.opencinecam.service.AudioListeningController
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Real AudioTrack route/write/retirement checks, not evidence of acoustic audibility or latency. */
class AudioListeningControllerDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val speaker get() = context.getSystemService(AudioManager::class.java)
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS).single { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    @Test fun persistedEnableNeedsExplicitConnectAndMissingExactDeviceNeverFallsBack() {
        val status = AtomicReference(AudioListeningStatus())
        val devices = AtomicReference<List<AudioListeningDevice>>(emptyList())
        val controller = AudioListeningController(context, status::set, devices::set)
        val pcm = pcm()
        try {
            controller.configure(AudioListeningSettings(true, 0, AudioListeningOutput.SPEAKER), Int.MAX_VALUE)
            await { status.get().phase == AudioListeningPhase.NEEDS_CONNECT }
            assertFalse(controller.offer(pcm, pcm.capacity(), PcmMeterEncoding.PCM_16, 48000, 1))
            assertNull(track(controller))
            controller.reconnect()
            // Device validation may reject before any PCM arrives; admission timing is not evidence.
            controller.offer(pcm, pcm.capacity(), PcmMeterEncoding.PCM_16, 48000, 1)
            await { status.get().phase == AudioListeningPhase.NO_OUTPUT }
            assertNull(status.get().effectiveDeviceId)
            assertEquals(0L, status.get().acceptedFrames)
            assertNull(track(controller))
            assertFalse(controller.offer(pcm, pcm.capacity(), PcmMeterEncoding.PCM_16, 48000, 1))
            await { devices.get().any { it.id == speaker.id && it.output == AudioListeningOutput.SPEAKER } }
        } finally { controller.closeAsync().get(15, TimeUnit.SECONDS) }
    }

    @Test fun explicitSpeakerWritesRealFramesVolumeChangesKeepTrackAndProducerChangeRetiresOldTrack() {
        val status = AtomicReference(AudioListeningStatus())
        val controller = AudioListeningController(context, status::set, {})
        val pcm = pcm(); val original = ByteArray(pcm.capacity()).also { pcm.duplicate().apply { clear() }.get(it) }
        val output = speaker
        try {
            controller.configure(AudioListeningSettings(true, 0, AudioListeningOutput.SPEAKER), output.id)
            controller.reconnect()
            feedUntilActive(controller, status, pcm)
            val first = requireNotNull(track(controller))
            assertEquals(output.id, first.routedDevice?.id)
            assertEquals(output.id, status.get().effectiveDeviceId)
            assertTrue(status.get().acceptedFrames > 0)
            controller.configure(AudioListeningSettings(true, 25, AudioListeningOutput.SPEAKER), output.id)
            await { (field(controller, "volume") as Int) == 25 }
            assertSame(first, track(controller))
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, first.playState)
            controller.configure(AudioListeningSettings(true, 0, AudioListeningOutput.SPEAKER), output.id)
            await { (field(controller, "volume") as Int) == 0 }
            controller.clearProducer()
            await { first.state == AudioTrack.STATE_UNINITIALIZED && status.get().phase == AudioListeningPhase.WAITING_PCM }
            assertNull(track(controller))
            feedUntilActive(controller, status, pcm)
            val second = requireNotNull(track(controller))
            assertNotSame(first, second)
            assertEquals(output.id, second.routedDevice?.id)
            assertArrayEquals(original, ByteArray(pcm.capacity()).also { pcm.duplicate().apply { clear() }.get(it) })
            val receipt = controller.closeAsync()
            // These are observation-only views, regardless of whether native cleanup wins this race.
            receipt.cancel(true)
            controller.closeAsync().complete(Unit)
            controller.closeAsync().get(15, TimeUnit.SECONDS)
            assertEquals(AudioTrack.STATE_UNINITIALIZED, second.state)
            assertFalse(controller.offer(pcm, pcm.capacity(), PcmMeterEncoding.PCM_16, 48000, 1))
        } finally { controller.closeAsync().get(15, TimeUnit.SECONDS) }
    }

    @Test fun changingOutputDisarmsAndNeverAutomaticallyMovesSpeakerPcmToAnotherDevice() {
        val status = AtomicReference(AudioListeningStatus())
        val controller = AudioListeningController(context, status::set, {})
        val pcm = pcm()
        try {
            controller.configure(AudioListeningSettings(true, 0, AudioListeningOutput.SPEAKER), speaker.id)
            controller.reconnect(); feedUntilActive(controller, status, pcm)
            val previous = requireNotNull(track(controller))
            controller.configure(AudioListeningSettings(true, 0, AudioListeningOutput.WIRED_USB), null)
            assertNull(boundDevice(controller))
            await { status.get().phase == AudioListeningPhase.NEEDS_CONNECT && previous.state == AudioTrack.STATE_UNINITIALIZED }
            assertNull(track(controller))
            assertFalse(controller.offer(pcm, pcm.capacity(), PcmMeterEncoding.PCM_16, 48000, 1))
            assertNull(status.get().effectiveDeviceId)
        } finally { controller.closeAsync().get(15, TimeUnit.SECONDS) }
    }

    @Test fun producerRetiredAfterListeningSnapshotCannotDeliverOldPacketUnderNewGeneration() {
        val status = AtomicReference(AudioListeningStatus())
        val controller = AudioListeningController(context, status::set, {})
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val accepted = AtomicBoolean(); val failure = AtomicReference<Throwable?>()
        val producer = Thread({
            try {
                accepted.set(controller.offer(pcm(), 960, PcmMeterEncoding.PCM_16, 48000, 1) {
                    entered.countDown(); check(resume.await(15, TimeUnit.SECONDS)); true
                })
            } catch (problem: Throwable) { failure.set(problem) }
        }, "ListeningOldProducerFixture")
        try {
            controller.configure(AudioListeningSettings(true, 0, AudioListeningOutput.SPEAKER), speaker.id)
            controller.reconnect(); producer.start()
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            controller.clearProducer()
            resume.countDown(); producer.join(15000)
            assertFalse(producer.isAlive); assertNull(failure.get()); assertTrue(accepted.get())
            await { (field(field(controller, "queue")!!, "reserved") as java.util.concurrent.atomic.AtomicInteger).get() == 0 }
            assertNull(track(controller)); assertEquals(0L, status.get().acceptedFrames)
            assertFalse(controller.offer(pcm(), 960, PcmMeterEncoding.PCM_16, 48000, 1) { false })
            assertNull(track(controller))
            feedUntilActive(controller, status, pcm())
            assertTrue(status.get().acceptedFrames > 0)
        } finally { resume.countDown(); producer.join(15000); controller.closeAsync().get(15, TimeUnit.SECONDS) }
    }

    @Test fun twoControllersCannotOwnTracksUntilFirstActuallyRetiresAndSecondReconnects() {
        val firstStatus = AtomicReference(AudioListeningStatus())
        val secondStatus = AtomicReference(AudioListeningStatus())
        val first = AudioListeningController(context, firstStatus::set, {})
        val second = AudioListeningController(context, secondStatus::set, {})
        val settings = AudioListeningSettings(true, 0, AudioListeningOutput.SPEAKER)
        val pcm = pcm()
        try {
            first.configure(settings, speaker.id); first.reconnect()
            feedUntilActive(first, firstStatus, pcm)
            val firstTrack = requireNotNull(track(first))
            second.configure(settings, speaker.id); second.reconnect()
            assertTrue(second.offer(pcm, pcm.capacity(), PcmMeterEncoding.PCM_16, 48000, 1))
            await { secondStatus.get().phase == AudioListeningPhase.FAILED }
            assertTrue(secondStatus.get().message.orEmpty().contains("active or still retiring"))
            assertNull(track(second)); assertEquals(0L, secondStatus.get().acceptedFrames)
            assertSame(firstTrack, track(first))
            first.closeAsync().get(15, TimeUnit.SECONDS)
            assertEquals(AudioTrack.STATE_UNINITIALIZED, firstTrack.state)
            assertFalse(second.offer(pcm, pcm.capacity(), PcmMeterEncoding.PCM_16, 48000, 1))
            assertNull(track(second))
            second.reconnect()
            await { secondStatus.get().phase == AudioListeningPhase.WAITING_PCM }
            feedUntilActive(second, secondStatus, pcm)
            assertTrue(secondStatus.get().acceptedFrames > 0)
        } finally {
            try { first.closeAsync().get(15, TimeUnit.SECONDS) }
            finally { second.closeAsync().get(15, TimeUnit.SECONDS) }
        }
    }

    @Test fun oldTrackActivePublicationCannotReviveAfterProducerClearOrReconnect() {
        val status = AtomicReference(AudioListeningStatus())
        val controller = AudioListeningController(context, status::set, {})
        val pcm = pcm()
        try {
            controller.configure(AudioListeningSettings(true, 0, AudioListeningOutput.SPEAKER), null)
            controller.reconnect(); feedUntilActive(controller, status, pcm)
            val oldStatus = status.get()
            val oldGeneration = field(controllerIntent(controller), "generation") as Long
            val emit = controller.javaClass.declaredMethods.single { it.name == "emit" && it.parameterCount == 3 }
                .apply { isAccessible = true }
            instrumentation.runOnMainSync {
                controller.clearProducer()
                assertFalse(controller.isCurrentStatus(oldStatus))
                // Delivers the old native write's already validated publication after invalidation.
                emit.invoke(controller, AudioListeningPhase.ACTIVE, null, oldGeneration)
                val latest = (field(controller, "lastStatus") as AtomicReference<*>).get() as AudioListeningStatus
                assertNotEquals(AudioListeningPhase.ACTIVE, latest.phase)
                assertNull(latest.effectiveDeviceId)
            }
            await { status.get().phase in setOf(AudioListeningPhase.RETIRING, AudioListeningPhase.WAITING_PCM) }
            instrumentation.runOnMainSync {
                val previousWaiting = (field(controller, "lastStatus") as AtomicReference<*>).get() as AudioListeningStatus
                controller.reconnect()
                assertFalse(controller.isCurrentStatus(previousWaiting))
                emit.invoke(controller, AudioListeningPhase.ACTIVE, null, oldGeneration)
                assertFalse(controller.isCurrentStatus(oldStatus))
                val latest = (field(controller, "lastStatus") as AtomicReference<*>).get() as AudioListeningStatus
                assertNotEquals(AudioListeningPhase.ACTIVE, latest.phase)
                assertNull(latest.effectiveDeviceId)
            }
            feedUntilActive(controller, status, pcm)
            await { controller.isCurrentStatus(status.get()) && status.get().phase == AudioListeningPhase.ACTIVE }
            assertEquals(speaker.id, status.get().effectiveDeviceId)
        } finally { controller.closeAsync().get(15, TimeUnit.SECONDS) }
    }

    @Test fun automaticCategoryBindsExactDeviceAcrossProducersAndRemovalDisarmsEvenWithoutTrack() {
        val status = AtomicReference(AudioListeningStatus())
        val controller = AudioListeningController(context, status::set, {})
        val pcm = pcm(); val output = speaker
        try {
            controller.configure(AudioListeningSettings(true, 0, AudioListeningOutput.SPEAKER), null)
            controller.reconnect(); feedUntilActive(controller, status, pcm)
            assertEquals(output.id, boundDevice(controller))
            val oldTrack = requireNotNull(track(controller))
            controller.configure(AudioListeningSettings(true, 10, AudioListeningOutput.SPEAKER), null)
            assertEquals(output.id, boundDevice(controller))
            controller.clearProducer()
            await { oldTrack.state == AudioTrack.STATE_UNINITIALIZED && status.get().phase == AudioListeningPhase.WAITING_PCM }
            assertNull(track(controller)); assertEquals(output.id, boundDevice(controller))
            // Exercise the real callback with this real device identity. This is a notification
            // fixture, not a claim that the emulator speaker was physically unplugged.
            val callback = field(controller, "deviceCallback") as AudioDeviceCallback
            instrumentation.runOnMainSync { callback.onAudioDevicesRemoved(arrayOf(output)) }
            await { status.get().phase == AudioListeningPhase.DISCONNECTED }
            assertFalse(controller.offer(pcm, pcm.capacity(), PcmMeterEncoding.PCM_16, 48000, 1))
            assertNull(track(controller)); assertEquals(output.id, boundDevice(controller))
            controller.reconnect()
            await { status.get().phase == AudioListeningPhase.WAITING_PCM }
            feedUntilActive(controller, status, pcm)
            assertEquals(output.id, boundDevice(controller))
        } finally { controller.closeAsync().get(15, TimeUnit.SECONDS) }
    }

    private fun controllerIntent(controller: AudioListeningController): Any =
        (field(controller, "intent") as AtomicReference<*>).get()!!
    private fun boundDevice(controller: AudioListeningController): Int? = field(controllerIntent(controller), "boundDeviceId") as Int?

    private fun feedUntilActive(controller: AudioListeningController, status: AtomicReference<AudioListeningStatus>, pcm: ByteBuffer) {
        await {
            val phase = status.get().phase
            check(phase !in setOf(AudioListeningPhase.FAILED, AudioListeningPhase.DISCONNECTED, AudioListeningPhase.NO_OUTPUT)) { status.get().toString() }
            controller.offer(pcm, pcm.capacity(), PcmMeterEncoding.PCM_16, 48000, 1)
            phase == AudioListeningPhase.ACTIVE && status.get().acceptedFrames > 0
        }
    }
    private fun pcm(): ByteBuffer = ByteBuffer.allocateDirect(960).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(480) { putShort(if (it % 48 < 24) 1000.toShort() else (-1000).toShort()) }
        position(4); mark(); limit(capacity() - 4)
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Listening state did not converge" }
            Thread.sleep(10)
        }
        instrumentation.waitForIdleSync()
    }
    private fun track(controller: AudioListeningController) = field(controller, "currentTrack") as AudioTrack?
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
