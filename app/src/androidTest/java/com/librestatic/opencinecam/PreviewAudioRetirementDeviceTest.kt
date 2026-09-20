/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.*
import android.media.AudioRecord
import android.os.IBinder
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.service.PreviewAudioMonitor
import com.librestatic.opencinecam.service.PreviewAudioMonitorCreationFailure
import com.librestatic.opencinecam.storage.AudioRetirementGate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real AudioRecord/Camera2 service. A latch retains one actual PCM callback, not a fake mic. */
class PreviewAudioRetirementDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val requested get() = CameraSettings(audioEnabled = true, audioSampleRateHz = 48000,
        audioBitDepth = AudioBitDepth.PCM_16, audioChannels = 1, audioSource = AudioSourceSelection.MIC,
        audioRecordingGain = DigitalRecordingGain(true, 0),
        operation = OperatorPreferences(startupMode = StartupMode.VIDEO, restoreExposureWhiteBalance = true))

    @Test fun heldNativeReaderKeepsResourcesDespiteCancelledOrForgedWaiter() {
        grant()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val monitor = PreviewAudioMonitor.create(context, requested, {
            entered.countDown(); check(release.await(30, TimeUnit.SECONDS))
        }, { throw AssertionError(it) })
        try {
            monitor.start(); assertTrue(entered.await(15, TimeUnit.SECONDS))
            lateinit var receipt: CompletableFuture<Unit>
            compose.runOnUiThread { receipt = monitor.closeAsync(); assertFalse(receipt.isDone) }
            assertTrue(receipt.cancel(true))
            assertTrue(monitor.closeAsync().complete(Unit))
            assertFalse(monitor.closeAsync().isDone)
            assertEquals(AudioRecord.STATE_INITIALIZED, record(monitor).state)
            try { AudioRetirementGate.requireIdle(); fail("Reader still owns microphone") } catch (_: IllegalStateException) { }
        } finally { release.countDown(); monitor.closeAsync().get(15, TimeUnit.SECONDS) }
        assertRetired(monitor)
    }

    @Test fun rejectedInputFactoryExposesActualCleanupReceipt() {
        grant()
        val failure = try {
            PreviewAudioMonitor.create(context, requested.copy(audioInputDeviceId = Int.MAX_VALUE), {}, {})
            throw AssertionError("Absent input must reject after acquiring its native record")
        } catch (failure: PreviewAudioMonitorCreationFailure) { failure }
        assertTrue(failure.cause?.message.orEmpty().contains("no longer connected"))
        failure.retirement.get(15, TimeUnit.SECONDS)
        AudioRetirementGate.requireIdle()
        val next = PreviewAudioMonitor.create(context, requested, {}, {})
        next.closeAsync().get(15, TimeUnit.SECONDS)
        assertRetired(next)
    }

    @Test fun captureCancellationRetainsMicUntilWorkerExitAndNeverDispatchesLate() = fixture { f ->
        val held = f.hold()
        try {
            compose.runOnUiThread {
                assertTrue(f.owner.capturePrimary(true))
                assertTrue(f.owner.cameraStates.value.audioRetirementPending)
                assertTrue(f.owner.cameraStates.value.capturePreparationCancelable)
                assertTrue(f.owner.cameraStates.value.structuralSettingsFrozen)
                assertNull(field(f.service, "previewAudioMonitor"))
                assertFalse(field(f.service, "transferCaptureDispatched") as Boolean)
                assertNull(field(f.service, "videoOutput"))
                assertTrue(f.owner.stopRecording())
                assertFalse(f.owner.cameraStates.value.audioRetirementPending)
            }
            assertFalse(held.monitor.closeAsync().isDone)
            assertEquals(AudioRecord.STATE_INITIALIZED, record(held.monitor).state)
            held.release.countDown()
            held.monitor.closeAsync().get(15, TimeUnit.SECONDS)
            val job = field(f.service, "transferPreparationJob") as Job
            runBlocking { withTimeout(15_000) { job.join() } }
            compose.waitUntil(15_000) {
                field(f.service, "previewAudioMonitor") != null && f.owner.cameraStates.value.audioMonitoringActive
            }
            assertRetired(held.monitor)
            assertNotSame(held.monitor, field(f.service, "previewAudioMonitor"))
            assertEquals(CameraUiPhase.PREVIEWING, f.owner.cameraStates.value.phase)
            assertFalse(field(f.service, "transferCaptureDispatched") as Boolean)
            assertNull(field(f.service, "videoOutput"))
            assertNull(f.owner.cameraStates.value.lastSavedUri)
        } finally { held.release.countDown(); held.monitor.closeAsync().get(15, TimeUnit.SECONDS) }
    }

    @Test fun repeatedSettingsWhileReaderIsHeldOpenOnlyLatestGainAfterActualRetirement() = fixture { f ->
        val held = f.hold()
        try {
            compose.runOnUiThread {
                f.owner.applySettings(requested.copy(audioRecordingGain = DigitalRecordingGain(true, -6)))
                f.owner.applySettings(requested.copy(audioRecordingGain = DigitalRecordingGain(true, 12)))
                assertNull(field(f.service, "previewAudioMonitor"))
                assertFalse(f.owner.cameraStates.value.audioMonitoringActive)
            }
            assertFalse(held.monitor.closeAsync().isDone)
            assertEquals(AudioRecord.STATE_INITIALIZED, record(held.monitor).state)
            held.release.countDown(); held.monitor.closeAsync().get(15, TimeUnit.SECONDS)
            compose.waitUntil(15_000) {
                f.owner.cameraStates.value.audioMonitoringActive &&
                    f.owner.cameraStates.value.audioLevels?.appliedRecordingGain == DigitalRecordingGain(true, 12)
            }
            assertRetired(held.monitor)
            assertNotSame(held.monitor, field(f.service, "previewAudioMonitor"))
        } finally { held.release.countDown(); held.monitor.closeAsync().get(15, TimeUnit.SECONDS) }
    }

    @Test fun detachedSurfaceInvalidatesPendingReplacementWithoutReleasingUnderReader() = fixture { f ->
        val held = f.hold()
        try {
            compose.runOnUiThread {
                f.owner.applySettings(requested.copy(audioRecordingGain = DigitalRecordingGain(true, -6)))
                f.owner.detachPreview()
                assertNull(field(f.service, "previewAudioMonitor"))
            }
            assertFalse(held.monitor.closeAsync().isDone)
            held.release.countDown(); held.monitor.closeAsync().get(15, TimeUnit.SECONDS)
            barrier(f.executor); instrumentation.waitForIdleSync()
            assertRetired(held.monitor)
            assertNull(field(f.service, "previewAudioMonitor"))
            assertFalse(f.owner.cameraStates.value.audioMonitoringActive)
            assertNull(f.owner.cameraStates.value.audioLevels)
        } finally { held.release.countDown(); held.monitor.closeAsync().get(15, TimeUnit.SECONDS) }
    }

    @Test fun listeningRequiresExplicitConnectAndLiveVolumeDoesNotReplaceTheMicrophone() = fixture { f ->
        val monitor = field(f.service, "previewAudioMonitor") as PreviewAudioMonitor
        val speaker = context.getSystemService(android.media.AudioManager::class.java)
            .getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .first { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val configured = requested.copy(audioListening = AudioListeningSettings(true, 40, AudioListeningOutput.SPEAKER),
            audioListeningOutputDeviceId = speaker.id)
        compose.runOnUiThread { f.owner.applySettings(configured) }
        compose.waitUntil(10_000) { f.owner.cameraStates.value.audioListeningStatus.phase == AudioListeningPhase.NEEDS_CONNECT }
        assertSame("Listening preferences cannot reopen AudioRecord", monitor, field(f.service, "previewAudioMonitor"))
        assertEquals(0, f.owner.cameraStates.value.audioListeningStatus.acceptedFrames)
        compose.runOnUiThread { f.owner.reconnectAudioListening() }
        compose.waitUntil(15_000) {
            val status = f.owner.cameraStates.value.audioListeningStatus
            status.phase == AudioListeningPhase.ACTIVE && status.effectiveDeviceId == speaker.id && status.acceptedFrames > 0
        }
        for (volume in listOf(0, 100, 17)) {
            compose.runOnUiThread { f.owner.applySettings(configured.copy(audioListening = configured.audioListening.copy(volumePercent = volume))) }
            assertSame(monitor, field(f.service, "previewAudioMonitor"))
            assertEquals(volume, f.owner.cameraStates.value.effectiveSettings?.audioListening?.volumePercent)
            assertEquals(DigitalRecordingGain(true, 0), f.owner.cameraStates.value.effectiveSettings?.audioRecordingGain)
        }
        compose.runOnUiThread { f.owner.applySettings(configured.copy(audioListening = configured.audioListening.copy(enabled = false))) }
        compose.waitUntil(10_000) { f.owner.cameraStates.value.audioListeningStatus.phase == AudioListeningPhase.DISABLED }
        assertSame(monitor, field(f.service, "previewAudioMonitor"))
        assertTrue(f.owner.cameraStates.value.audioMonitoringActive)
    }

    @Test fun liveMeterDisplayChangesKeepSamePcmProducerAndRecordingGain() = fixture { f ->
        val monitor = field(f.service, "previewAudioMonitor") as PreviewAudioMonitor
        val epoch = (field(f.service, "audioLevelEpoch") as java.util.concurrent.atomic.AtomicLong).get()
        compose.waitUntil(10_000) { f.owner.cameraStates.value.audioLevels?.channels?.all { it.vuDbfs != null && it.ppmDbfs != null } == true }
        for (mode in AudioMeterMode.entries) for (visible in listOf(false, true)) {
            val meters = AudioMeterSettings(visible, mode, -20, 2500, true)
            compose.runOnUiThread { f.owner.applySettings(requested.copy(audioMeter = meters)) }
            assertSame("Display preferences cannot replace the microphone", monitor, field(f.service, "previewAudioMonitor"))
            assertEquals(epoch, (field(f.service, "audioLevelEpoch") as java.util.concurrent.atomic.AtomicLong).get())
            assertEquals(meters, f.owner.cameraStates.value.effectiveSettings?.audioMeter)
            assertEquals(requested.audioRecordingGain, f.owner.cameraStates.value.effectiveSettings?.audioRecordingGain)
            assertTrue(f.owner.cameraStates.value.audioMonitoringActive)
        }
    }

    @Test fun editingProductionSlateNeverReplacesPreviewPcmOrChangesRecordingGain() = fixture { f ->
        val monitor = field(f.service, "previewAudioMonitor") as PreviewAudioMonitor
        val epoch = (field(f.service, "audioLevelEpoch") as java.util.concurrent.atomic.AtomicLong).get()
        for (scene in listOf("A", "A1", "A12", "A123")) {
            val slate = ProductionSlateSettings(project = "Shoot", scene = scene, takeNumber = 12)
            compose.runOnUiThread { f.owner.applySettings(requested.copy(productionSlate = slate)) }
            assertSame("Editorial settings cannot replace the microphone", monitor, field(f.service, "previewAudioMonitor"))
            assertEquals(epoch, (field(f.service, "audioLevelEpoch") as java.util.concurrent.atomic.AtomicLong).get())
            assertEquals(slate, f.owner.cameraStates.value.effectiveSettings?.productionSlate)
            assertEquals(requested.audioRecordingGain, f.owner.cameraStates.value.effectiveSettings?.audioRecordingGain)
            assertTrue(f.owner.cameraStates.value.audioMonitoringActive)
        }
    }

    @Test fun galleryPresentationEditsNeverReplacePreviewPcmOrRecordingIntent() = fixture { f ->
        val monitor = field(f.service, "previewAudioMonitor") as PreviewAudioMonitor
        val epoch = (field(f.service, "audioLevelEpoch") as java.util.concurrent.atomic.AtomicLong).get()
        for (kind in GalleryMediaKind.entries) {
            val gallery = GallerySettings(kind, newestFirst = false, goodTakesOnly = true,
                showSlate = false, showTechnical = true)
            compose.runOnUiThread { f.owner.applySettings(requested.copy(gallery = gallery)) }
            assertSame("Gallery preferences cannot replace the microphone", monitor, field(f.service, "previewAudioMonitor"))
            assertEquals(epoch, (field(f.service, "audioLevelEpoch") as java.util.concurrent.atomic.AtomicLong).get())
            assertEquals(gallery, f.owner.cameraStates.value.effectiveSettings?.gallery)
            assertEquals(requested.productionSlate, f.owner.cameraStates.value.effectiveSettings?.productionSlate)
            assertEquals(requested.audioRecordingGain, f.owner.cameraStates.value.effectiveSettings?.audioRecordingGain)
            assertTrue(f.owner.cameraStates.value.audioMonitoringActive)
        }
    }

    @Test fun sharingPreferencesNeverRestartPcmOrAlterTheCaptureSlate() = fixture { f ->
        val monitor = field(f.service, "previewAudioMonitor") as PreviewAudioMonitor
        val epoch = (field(f.service, "audioLevelEpoch") as java.util.concurrent.atomic.AtomicLong).get()
        for (content in MediaShareContent.entries) for (metadata in MediaShareMetadata.entries) {
            val sharing = MediaSharingSettings(content, metadata, includeReferencedLut = false)
            compose.runOnUiThread { f.owner.applySettings(requested.copy(mediaSharing = sharing)) }
            assertSame("Sharing choices cannot replace the microphone", monitor, field(f.service, "previewAudioMonitor"))
            assertEquals(epoch, (field(f.service, "audioLevelEpoch") as java.util.concurrent.atomic.AtomicLong).get())
            assertEquals(sharing, f.owner.cameraStates.value.effectiveSettings?.mediaSharing)
            assertEquals(requested.productionSlate, f.owner.cameraStates.value.effectiveSettings?.productionSlate)
            assertEquals(requested.audioRecordingGain, f.owner.cameraStates.value.effectiveSettings?.audioRecordingGain)
            assertTrue(f.owner.cameraStates.value.audioMonitoringActive)
        }
    }

    private class Held(val monitor: PreviewAudioMonitor, val release: CountDownLatch)
    private inner class Fixture(val owner: CaptureService.LocalBinder, val service: CaptureService, val executor: Executor) {
        fun hold(): Held {
            val monitor = field(service, "previewAudioMonitor") as PreviewAudioMonitor
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val once = AtomicBoolean()
            val callback = monitor.javaClass.getDeclaredField("onLevel").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val actual = callback.get(monitor) as (AudioLevelSnapshot) -> Unit
            callback.set(monitor, { snapshot: AudioLevelSnapshot ->
                if (once.compareAndSet(false, true)) {
                    entered.countDown(); check(release.await(30, TimeUnit.SECONDS))
                }
                actual(snapshot)
            })
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            return Held(monitor, release)
        }
    }

    private fun fixture(action: (Fixture) -> Unit) {
        grant()
        val repository = SettingsRepositories.get(context); val before = repository.states.value
        val binder = AtomicReference<CaptureService.LocalBinder?>(); val surface = AtomicReference<Surface?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder.set(service as CaptureService.LocalBinder) }
            override fun onServiceDisconnected(name: ComponentName?) { binder.set(null) }
        }
        var bound = false; var service: CaptureService? = null; var engine: Camera2PreviewEngine? = null
        try {
            compose.runOnUiThread { repository.set(requested) }
            bound = context.bindService(Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound); compose.waitUntil(10_000) { binder.get() != null }
            val owner = requireNotNull(binder.get())
            service = field(owner, "this\$0") as CaptureService
            engine = CaptureService::class.java.getDeclaredMethod("getPreviewEngine").apply { isAccessible = true }.invoke(service) as Camera2PreviewEngine
            val executor = field(engine, "cameraExecutor") as Executor
            compose.runOnUiThread { owner.prepare(640, 480); owner.selectMode(CaptureMode.VIDEO, reopen = false) }
            val descriptor = requireNotNull(owner.cameraStates.value.descriptor)
            compose.setContent { AndroidView(factory = { host -> SurfaceView(host).apply {
                holder.setFixedSize(descriptor.previewSize.width, descriptor.previewSize.height)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { surface.set(holder.surface) }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surface.set(holder.surface) }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { surface.set(null) }
                })
            } }, modifier = Modifier.fillMaxSize()) }
            compose.waitUntil(10_000) { surface.get()?.isValid == true }
            compose.runOnUiThread { assertTrue(owner.attachPreview(requireNotNull(surface.get()), 0)) }
            compose.waitUntil(15_000) { owner.cameraStates.value.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.ERROR) }
            assertEquals(owner.cameraStates.value.message, CameraUiPhase.PREVIEWING, owner.cameraStates.value.phase)
            barrier(field(service, "storageExecutor") as Executor); instrumentation.waitForIdleSync()
            compose.waitUntil(15_000) { owner.cameraStates.value.audioMonitoringActive && field(service, "previewAudioMonitor") != null }
            action(Fixture(owner, service, executor))
        } finally {
            try {
                compose.runOnUiThread { binder.get()?.detachPreview() }
                service?.let {
                    @Suppress("UNCHECKED_CAST") val receipt = field(it, "previewAudioRetirement") as CompletableFuture<Unit>
                    receipt.get(15, TimeUnit.SECONDS)
                }
                engine?.closeAsync()?.get(15, TimeUnit.SECONDS)
            } finally {
                if (bound) context.unbindService(connection)
                instrumentation.waitForIdleSync()
                service?.let {
                    @Suppress("UNCHECKED_CAST") val receipt = field(it, "audioListeningRetirement") as CompletableFuture<Unit>
                    receipt.get(15, TimeUnit.SECONDS)
                }
                compose.runOnUiThread { repository.set(before) }
            }
        }
    }
    private fun grant() {
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
    }
    private fun assertRetired(monitor: PreviewAudioMonitor) {
        assertEquals(AudioRecord.STATE_UNINITIALIZED, record(monitor).state)
        assertFalse((field(field(monitor, "lifecycle")!!, "worker") as Thread).isAlive)
        AudioRetirementGate.requireIdle()
    }
    private fun record(monitor: PreviewAudioMonitor) = field(monitor, "audioRecord") as AudioRecord
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun barrier(executor: Executor) {
        val done = CountDownLatch(1); executor.execute { done.countDown() }; assertTrue(done.await(15, TimeUnit.SECONDS))
    }
}
