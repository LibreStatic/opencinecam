/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.*
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.service.CaptureService
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real sequential Camera2 inputs and provider output; no physical continuous-exposure claim. */
class AccumulationServiceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun lightPublishesDecodedAccumulationAndActualFrameMetadata() = exercise(AccumulationMode.LIGHT)
    @Test fun waterPublishesDecodedAccumulationAndActualFrameMetadata() = exercise(AccumulationMode.WATER)
    @Test fun starsPublishesDecodedAccumulationAndActualFrameMetadata() = exercise(AccumulationMode.STARS)
    @Test fun bulbPublishesDecodedSimulatedIntegrationAndActualFrameMetadata() = exercise(AccumulationMode.BULB)
    @Test fun cancellingAfterOneRealFrameDiscardsThenAllowsFreshAccumulation() = exercise(AccumulationMode.LIGHT,cancelAfterOne=true)
    @Test fun finishAfterTwoRealFramesPublishesBeforeMaximumDuration() = exercise(AccumulationMode.BULB,finishEarly=true)
    @Test fun processingFailureDiscardsThenRecoversThroughPreviewReopen() = exercise(AccumulationMode.LIGHT,failAfterOne=true)
    @Test fun writerKeepsOwnershipAcrossDetachAndDefersSelectionAndQuality() = exercise(AccumulationMode.WATER,holdWriter=true)

    @Test fun customCropAppliesOnceToFinalAccumulationWithoutChangingFrameRelation() = exercise(AccumulationMode.WATER,aspect=PhotoAspectSelection(true,17,11))

    @Test fun operatorLutPreservesOriginalSequenceAndPublication() = withOperatorLutFixture { hash ->
        exercise(AccumulationMode.WATER,operatorHash=hash)
    }

    private fun exercise(mode:AccumulationMode,cancelAfterOne:Boolean=false,finishEarly:Boolean=false,holdWriter:Boolean=false,failAfterOne:Boolean=false,aspect:PhotoAspectSelection=PhotoAspectSelection(),operatorHash:String?=null) {
        val selection=AccumulationSelection(mode,if(finishEarly) 30000 else 5000,100,720,16)
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.CAMERA)
        val repository=SettingsRepositories.get(context);val before=repository.states.value
        val binder=AtomicReference<CaptureService.LocalBinder?>();val surface=AtomicReference<android.view.Surface?>()
        val release=CountDownLatch(1);val firstFrame=CountDownLatch(1)
        val engineExecutor=AtomicReference<java.util.concurrent.Executor?>()
        val storageWriter=AtomicReference<java.util.concurrent.ThreadPoolExecutor?>()
        val collections=listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        fun rows():Set<Uri> = collections.flatMap {collection -> requireNotNull(context.contentResolver.query(collection,arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",arrayOf(context.packageName),null)).use { c -> buildList {while(c.moveToNext()) add(ContentUris.withAppendedId(collection,c.getLong(0)))}}}.toSet()
        val initial=rows()
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName?,service:IBinder?) {binder.set(service as CaptureService.LocalBinder)}
            override fun onServiceDisconnected(name:ComponentName?) {binder.set(null)}
        }
        var bound=false
        try {
            compose.runOnUiThread {repository.set(CameraSettings(audioEnabled=false,accumulation=selection,photoQuality=73,photoAspect=aspect,
                exposure=ExposureSelection(ExposureMode.AUTO,iso=100,timeNs=10_000_000),
                operation=OperatorPreferences(startupMode=StartupMode.PHOTO,restoreExposureWhiteBalance=true,lockDuringTake=false)))}
            bound=context.bindService(Intent(context,CaptureService::class.java),connection,Context.BIND_AUTO_CREATE)
            assertTrue(bound);compose.waitUntil(10_000) {binder.get()!=null}
            val owner=requireNotNull(binder.get())
            compose.runOnUiThread {owner.prepare(640,480);owner.selectMode(CaptureMode.LIGHT_TRAIL,reopen=false)}
            val d=requireNotNull(owner.cameraStates.value.descriptor)
            val outer=owner.javaClass.getDeclaredField("this\$0").apply {isAccessible=true}.get(owner)
            val engine=CaptureService::class.java.getDeclaredMethod("getPreviewEngine").apply {isAccessible=true}.invoke(outer) as Camera2PreviewEngine
            val executor=Camera2PreviewEngine::class.java.getDeclaredField("cameraExecutor").apply {isAccessible=true}.get(engine) as java.util.concurrent.Executor
            val writer=CaptureService::class.java.getDeclaredField("storageExecutor").apply {isAccessible=true}.get(outer) as java.util.concurrent.ThreadPoolExecutor
            engineExecutor.set(executor);storageWriter.set(writer)
            compose.setContent {AndroidView(factory={ host -> SurfaceView(host).apply {
                holder.setFixedSize(d.previewSize.width,d.previewSize.height)
                holder.addCallback(object:SurfaceHolder.Callback {
                    override fun surfaceCreated(holder:SurfaceHolder) {surface.set(holder.surface)}
                    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int) {surface.set(holder.surface)}
                    override fun surfaceDestroyed(holder:SurfaceHolder) {surface.set(null)}
                })
            }},modifier=Modifier.fillMaxSize())}
            compose.waitUntil(10_000) {surface.get()?.isValid==true}
            compose.runOnUiThread {assertTrue(owner.attachPreview(requireNotNull(surface.get()),0))}
            compose.waitUntil(15_000) {owner.cameraStates.value.phase in setOf(CameraUiPhase.PREVIEWING,CameraUiPhase.ERROR)}
            assertEquals(owner.cameraStates.value.message,CameraUiPhase.PREVIEWING,owner.cameraStates.value.phase)
            if (operatorHash != null) {
                compose.waitUntil(15_000) { owner.cameraStates.value.operatorLutStatus.state == OperatorLutState.ACTIVE }
                assertEquals(operatorHash, owner.cameraStates.value.operatorLutStatus.hash)
                assertTrue(owner.cameraStates.value.gpuViewfinder)
            }
            if(cancelAfterOne || finishEarly || failAfterOne) {
                val installed=CountDownLatch(1)
                executor.execute {
                    val field=Camera2PreviewEngine::class.java.getDeclaredField("listener").apply {isAccessible=true}
                    val original=field.get(engine) as Camera2PreviewListener
                    field.set(engine,object:Camera2PreviewListener by original {
                        override fun onAccumulationProgress(completed:Int,elapsedMs:Long,targetMs:Long) {
                            original.onAccumulationProgress(completed,elapsedMs,targetMs)
                            if(completed==(if(cancelAfterOne || failAfterOne) 1 else 2) && firstFrame.count>0) {
                                firstFrame.countDown();check(release.await(25,TimeUnit.SECONDS))
                            }
                        }
                    });installed.countDown()
                }
                assertTrue(installed.await(5,TimeUnit.SECONDS))
            }
            if(holdWriter) {
                val entered=CountDownLatch(1);writer.execute {entered.countDown();check(release.await(35,TimeUnit.SECONDS))}
                assertTrue(entered.await(5,TimeUnit.SECONDS))
            }
            compose.runOnUiThread {
                assertTrue(owner.capturePrimary(false))
                assertTrue(owner.cameraStates.value.stillCapturePending)
                assertFalse("Duplicate service capture rejects",owner.capturePrimary(false))
                assertFalse("Direct JPEG cannot overlap accumulation",engine.captureJpeg())
                assertFalse("Direct burst cannot overlap accumulation",engine.captureBurst(3))
                assertFalse("Bracket cannot overlap accumulation",engine.captureBracket())
                assertFalse("Finish requires at least two frames",owner.finishAccumulationCapture())
            }
            if(cancelAfterOne) {
                assertTrue("First native image must reach progress",firstFrame.await(15,TimeUnit.SECONDS))
                compose.runOnUiThread {assertTrue(owner.cancelAccumulationCapture());assertFalse(owner.cancelAccumulationCapture())}
                release.countDown()
                val retired=CountDownLatch(1);executor.execute {retired.countDown()};assertTrue(retired.await(5,TimeUnit.SECONDS))
                assertFalse(owner.cameraStates.value.stillCapturePending);assertEquals(initial,rows())
                assertNull(owner.cameraStates.value.lastAccumulationPublication)
                android.util.Log.i("AccumulationProbe","cancelAfterNativeFrame=1 discarded=true artifacts=0")
                compose.runOnUiThread {assertTrue(owner.capturePrimary(false))}
            }
            if(failAfterOne) {
                assertTrue("First image before injected processing retirement",firstFrame.await(15,TimeUnit.SECONDS))
                val groupRef=Camera2PreviewEngine::class.java.getDeclaredField("accumulationRequest").apply {isAccessible=true}.get(engine) as AtomicReference<*>
                val group=requireNotNull(groupRef.get())
                val processor=group.javaClass.getDeclaredField("processor").apply {isAccessible=true}.get(group) as AutoCloseable
                processor.close() // Executor is held at completed-frame boundary, not racing an add().
                release.countDown()
                compose.waitUntil(15_000) {owner.cameraStates.value.phase==CameraUiPhase.ERROR}
                assertEquals("accumulation-image-processing-failed",owner.cameraStates.value.errorCode)
                assertFalse(owner.cameraStates.value.stillCapturePending)
                assertNull(owner.cameraStates.value.lastAccumulationPublication);assertEquals(initial,rows())
                compose.runOnUiThread {owner.detachPreview();assertTrue(owner.attachPreview(requireNotNull(surface.get()),0))}
                compose.waitUntil(15_000) {owner.cameraStates.value.phase==CameraUiPhase.PREVIEWING}
                android.util.Log.i("AccumulationProbe","processorRetiredAfterFrame1=true failureDiscarded=true artifacts=0 previewReopened=true")
                compose.runOnUiThread {assertTrue(owner.capturePrimary(false))}
            }
            if(finishEarly) {
                assertTrue("Two real images must reach progress",firstFrame.await(15,TimeUnit.SECONDS))
                compose.runOnUiThread {assertTrue(owner.finishAccumulationCapture());assertFalse(owner.finishAccumulationCapture())}
                release.countDown()
            }
            if(holdWriter) {
                compose.waitUntil(25_000) {owner.cameraStates.value.accumulationSaving || owner.cameraStates.value.phase==CameraUiPhase.ERROR}
                assertEquals(owner.cameraStates.value.message,CameraUiPhase.CAPTURING,owner.cameraStates.value.phase)
                assertTrue(owner.cameraStates.value.accumulationFrames>=2);assertEquals(1,writer.queue.size)
                compose.runOnUiThread {
                    assertFalse(owner.cancelAccumulationCapture());assertFalse(owner.finishAccumulationCapture())
                    owner.detachPreview();owner.prepare(640,480);owner.attachPreview(requireNotNull(surface.get()),0)
                    assertTrue(owner.cameraStates.value.captureControlsLocked);assertFalse(owner.capturePrimary(false))
                    repository.update {it.copy(accumulation=AccumulationSelection(AccumulationMode.STARS,5000,250,1080,32),photoQuality=81)}
                }
                compose.waitUntil(5_000) {owner.cameraStates.value.settingsPending}
                assertEquals(selection,owner.cameraStates.value.effectiveSettings?.accumulation)
                assertEquals(73,owner.cameraStates.value.effectiveSettings?.photoQuality);assertEquals(initial,rows())
            }
            release.countDown()
            compose.waitUntil(30_000) {owner.cameraStates.value.lastAccumulationPublication!=null || owner.cameraStates.value.phase==CameraUiPhase.ERROR}
            val publication=requireNotNull(owner.cameraStates.value.lastAccumulationPublication) {owner.cameraStates.value.message ?: "Missing accumulation publication"}
            assertFalse(owner.cameraStates.value.stillCapturePending);assertFalse(owner.cameraStates.value.accumulationSaving)
            assertEquals(2,(rows()-initial).size)
            fun bytes(uri:String)=requireNotNull(context.contentResolver.openInputStream(Uri.parse(uri))).use {it.readBytes()}
            fun published(uri:String,mime:String) {
                requireNotNull(context.contentResolver.query(Uri.parse(uri),arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.MIME_TYPE),null,null,null)).use {
                    assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0));assertEquals(mime,it.getString(1))
                }
            }
            published(publication.metadataUri,"application/json");published(publication.image.uri,"image/jpeg")
            val metadata=Json.parseToJsonElement(bytes(publication.metadataUri).toString(Charsets.UTF_8)).jsonObject
            assertEquals(publication.id,metadata.getValue("bundleId").jsonPrimitive.content)
            assertEquals("COMPLETE",metadata.getValue("result").jsonPrimitive.content)
            assertEquals("COMPUTATIONAL_ACCUMULATION_NOT_CONTINUOUS_EXPOSURE",metadata.getValue("output").jsonPrimitive.content)
            assertEquals(mode.name,metadata.getValue("mode").jsonPrimitive.content)
            assertEquals(finishEarly,metadata.getValue("completedByUser").jsonPrimitive.boolean)
            val selected=metadata.getValue("selection").jsonObject
            assertEquals(selection.durationMs,selected.getValue("durationMs").jsonPrimitive.long)
            assertEquals(selection.intervalMs,selected.getValue("intervalMs").jsonPrimitive.long)
            assertEquals(selection.maxEdge,selected.getValue("maxEdge").jsonPrimitive.int)
            val frames=metadata.getValue("frames").jsonArray
            assertTrue(frames.size>=2);assertEquals(frames.size,metadata.getValue("frameCount").jsonPrimitive.int)
            val timestamps=mutableListOf<Long>();val ids=mutableSetOf<Long>()
            frames.forEachIndexed { index, element ->
                val f=element.jsonObject;assertEquals(index,f.getValue("index").jsonPrimitive.int)
                val timestamp=f.getValue("sensorTimestampNs").jsonPrimitive.long;assertTrue(timestamp>0);timestamps+=timestamp
                assertTrue(ids.add(f.getValue("captureId").jsonPrimitive.long))
                assertTrue(f.getValue("exposureTimeNs").jsonPrimitive.long>0)
                assertTrue(f.getValue("sensitivityIso").jsonPrimitive.int>0)
            }
            assertTrue(timestamps.zipWithNext().all {(a,b)->a<b})
            val span=timestamps.last()-timestamps.first()
            assertEquals(span,metadata.getValue("timestampSpanNs").jsonPrimitive.long)
            if(finishEarly) assertTrue("Finish saved before30s maximum",span<selection.durationMs*1_000_000)
            val image=publication.image;val meta=metadata.getValue("image").jsonObject
            assertEquals(73,meta.getValue("quality").jsonPrimitive.int);assertEquals(0,meta.getValue("orientationDegrees").jsonPrimitive.int)
            assertEquals(image.uri,meta.getValue("uri").jsonPrimitive.content);assertEquals(image.sha256,meta.getValue("sha256").jsonPrimitive.content)
            val content=bytes(image.uri);assertEquals(image.bytes,content.size.toLong())
            assertEquals(image.sha256,MessageDigest.getInstance("SHA-256").digest(content).joinToString("") {"%02x".format(it.toInt() and 255)})
            val bitmap=requireNotNull(BitmapFactory.decodeByteArray(content,0,content.size))
            assertEquals(meta.getValue("width").jsonPrimitive.int,bitmap.width);assertEquals(meta.getValue("height").jsonPrimitive.int,bitmap.height)
            assertTrue(bitmap.width in 1..selection.maxEdge && bitmap.height in 1..selection.maxEdge)
            try {assertPhotoAspectImage(bitmap,meta,aspect)} finally {bitmap.recycle()}
            android.util.Log.i("AccumulationProbe","bundle=${publication.id} mode=$mode frames=${frames.size} spanNs=$span completedByUser=$finishEarly bytes=${image.bytes} sha256=${image.sha256} published=true decoded=true physicalContinuousExposure=false metadata=$metadata")
            if(holdWriter) {
                compose.waitUntil(15_000) {!owner.cameraStates.value.settingsPending && owner.cameraStates.value.phase==CameraUiPhase.PREVIEWING}
                assertEquals(81,owner.cameraStates.value.effectiveSettings?.photoQuality)
                assertEquals(AccumulationSelection(AccumulationMode.STARS,5000,250,1080,32),owner.cameraStates.value.effectiveSettings?.accumulation)
                android.util.Log.i("AccumulationProbe","detachedDuringPublication=true duplicateRejected=true pendingSettingsApplied=true previewRestored=true")
            }
        } finally {
            release.countDown()
            compose.runOnUiThread {binder.get()?.detachPreview();repository.set(before)}
            // First retire native callbacks; then drain every storage task they accepted.
            // Otherwise a failed held-writer test could publish after its cleanup scan.
            engineExecutor.get()?.let { executor ->
                val retired=CountDownLatch(1);executor.execute {retired.countDown()}
                assertTrue("Native cleanup barrier",retired.await(15,TimeUnit.SECONDS))
            }
            storageWriter.get()?.let { writer ->
                val drained=CountDownLatch(1);writer.execute {drained.countDown()}
                assertTrue("Accepted writer cleanup barrier",drained.await(15,TimeUnit.SECONDS))
            }
            if(bound) context.unbindService(connection)
            (rows()-initial).forEach {context.contentResolver.delete(it,null,null)}
        }
    }
}
