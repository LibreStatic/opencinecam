/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.*
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.StillPhotoFormat
import com.librestatic.opencinecam.service.CaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Force a concurrent preview-state update between the real method read and write. */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
class ProfessionalStateRaceDeviceTest {
    @Test fun applyingProfessionalControlsCannotOverwriteACompletedPreviewOrNewSettings() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.CAMERA)
        val binder=AtomicReference<CaptureService.LocalBinder?>();val boundSignal=CountDownLatch(1)
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName?,service:IBinder?) {binder.set(service as CaptureService.LocalBinder);boundSignal.countDown()}
            override fun onServiceDisconnected(name:ComponentName?) {binder.set(null)}
        }
        val release=CountDownLatch(1);val captured=CountDownLatch(1);val done=CountDownLatch(1)
        val threadFailure=AtomicReference<Throwable?>();val armed=AtomicBoolean(true)
        val probeThread=AtomicReference<Thread?>()
        var service:CaptureService?=null
        var stateField:java.lang.reflect.Field?=null
        var original:MutableStateFlow<CameraUiState>?=null
        var originalSnapshot:CameraUiState?=null
        val bound=context.bindService(Intent(context,CaptureService::class.java),connection,Context.BIND_AUTO_CREATE)
        assertTrue(bound)
        try {
            assertTrue(boundSignal.await(10,TimeUnit.SECONDS))
            val owner=requireNotNull(binder.get())
            instrumentation.runOnMainSync {owner.prepare(640,480)}
            service=owner.javaClass.getDeclaredField("this\$0").apply {isAccessible=true}.get(owner) as CaptureService
            stateField=CaptureService::class.java.getDeclaredField("cameraState").apply {isAccessible=true}
            @Suppress("UNCHECKED_CAST")
            val flow=stateField.get(service) as MutableStateFlow<CameraUiState>
            original=flow
            originalSnapshot=flow.value
            assertNotNull(flow.value.descriptor)
            val initial=flow.value.copy(phase=CameraUiPhase.OPENING,effectiveSettings=CameraSettings())
            val advanced=initial.copy(phase=CameraUiPhase.PREVIEWING,
                effectiveSettings=CameraSettings(photoFormat=StillPhotoFormat.RAW_JPEG),message="new-preview-state")
            flow.value=initial
            val probe=object:MutableStateFlow<CameraUiState> by flow {
                override var value:CameraUiState
                    get() {
                        val snapshot=flow.value
                        if(Thread.currentThread()===probeThread.get() && armed.compareAndSet(true,false)) {
                            captured.countDown();check(release.await(10,TimeUnit.SECONDS))
                        }
                        return snapshot
                    }
                    set(value) {flow.value=value}
            }
            stateField.set(service,probe)
            val apply=CaptureService::class.java.getDeclaredMethod("applyProfessionalSettings").apply {isAccessible=true}
            val worker=Thread({
                try {apply.invoke(service)} catch(failure:Throwable) {threadFailure.set(failure)} finally {done.countDown()}
            },"E7-professional-state-race")
            probeThread.set(worker);worker.start()
            assertTrue("Method must read the old snapshot",captured.await(5,TimeUnit.SECONDS))
            flow.value=advanced // The camera callback/settings update wins while controls are resolving.
            release.countDown();assertTrue(done.await(10,TimeUnit.SECONDS))
            threadFailure.get()?.let {throw AssertionError("Control application failed",it)}
            assertEquals("Do not restore OPENING over PREVIEWING",CameraUiPhase.PREVIEWING,flow.value.phase)
            assertEquals(StillPhotoFormat.RAW_JPEG,flow.value.effectiveSettings?.photoFormat)
            assertEquals("new-preview-state",flow.value.message)
            android.util.Log.i("PhotoFormatProbe","professionalStateRace=PASS concurrentPreviewAndFormatPreserved=true")
        } finally {
            release.countDown()
            if(probeThread.get()!=null) assertTrue(done.await(10,TimeUnit.SECONDS))
            if(original!=null) instrumentation.runOnMainSync {
                stateField?.set(service,original)
                originalSnapshot?.let { original?.value=it }
            }
            if(bound) context.unbindService(connection)
        }
    }

    @Test fun acceptedAudioProbeCannotApplySettingsAfterServiceDestruction() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.CAMERA)
        val binder=AtomicReference<CaptureService.LocalBinder?>();val ready=CountDownLatch(1)
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName?,service:IBinder?) {binder.set(service as CaptureService.LocalBinder);ready.countDown()}
            override fun onServiceDisconnected(name:ComponentName?) {binder.set(null)}
        }
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var bound=context.bindService(Intent(context,CaptureService::class.java),connection,Context.BIND_AUTO_CREATE)
        assertTrue(bound)
        var executor:java.util.concurrent.ThreadPoolExecutor?=null
        try {
            assertTrue(ready.await(10,TimeUnit.SECONDS))
            val owner=requireNotNull(binder.get())
            val service=owner.javaClass.getDeclaredField("this\$0").apply {isAccessible=true}.get(owner) as CaptureService
            val worker=CaptureService::class.java.getDeclaredField("storageExecutor").apply {isAccessible=true}.get(service) as java.util.concurrent.ThreadPoolExecutor
            executor=worker
            worker.execute {entered.countDown();check(release.await(20,TimeUnit.SECONDS))}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            instrumentation.runOnMainSync {owner.prepare(640,480)}
            assertTrue("The real capability probe is queued behind the held worker",worker.queue.isNotEmpty())
            val destroyed=CaptureService::class.java.getDeclaredField("serviceDestroyed").apply {isAccessible=true}
            context.unbindService(connection);bound=false
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(!destroyed.getBoolean(service) && System.nanoTime()<deadline) {
                instrumentation.waitForIdleSync();Thread.sleep(10)
            }
            assertTrue("Real service destruction must precede probe completion",destroyed.getBoolean(service))
            instrumentation.waitForIdleSync()
            @Suppress("UNCHECKED_CAST")
            val flow=CaptureService::class.java.getDeclaredField("cameraState").apply {isAccessible=true}.get(service) as MutableStateFlow<CameraUiState>
            val retired=flow.value
            release.countDown()
            assertTrue("Accepted worker drains after shutdown",worker.awaitTermination(15,TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertEquals("Late capabilities must not mutate or revive the retired service",retired,flow.value)
            android.util.Log.i("PhotoFormatProbe","lateAudioProbe=PASS realDestroyBeforeProbeCompletion=true retiredStatePreserved=true")
        } finally {
            release.countDown()
            if(bound) context.unbindService(connection)
            executor?.let { if(it.isShutdown) assertTrue(it.awaitTermination(15,TimeUnit.SECONDS)) }
        }
    }

}
