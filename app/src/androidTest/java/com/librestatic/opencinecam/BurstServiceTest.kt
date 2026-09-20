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

/** Real sequential JPEG capture, grouped publication and crop; no fixed burst cadence claim. */
class BurstServiceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun threeCroppedFramesPublishCompleteOrderedRelationship() = exercise(3, PhotoAspectSelection(true,239,100))
    @Test fun maximumDefaultBurstPublishesTenUncroppedJpegs() = exercise(10)
    @Test fun cancellationAfterFirstCroppedFrameDiscardsAndAllowsFreshBurst() = exercise(3,PhotoAspectSelection(true,1,1),cancelAfterOne=true)
    @Test fun publicationOwnsBurstAcrossDetachAndDefersCountQualityAndCrop() = exercise(4,PhotoAspectSelection(true,9,16),holdWriter=true)
    @Test fun impossibleCropFailsWithoutPartialRowsAndNextBurstRecovers() = exercise(3,PhotoAspectSelection(true,10000,9999),rejectCrop=true)

    @Test fun operatorLutPreservesOriginalSequenceAndPublication() = withOperatorLutFixture { hash ->
        exercise(3,PhotoAspectSelection(true,3,2),operatorHash=hash)
    }

    @Test fun operatorLutSurvivesDetachedPublicationAndReopensThePhotographicGraph() = withOperatorLutFixture { hash ->
        exercise(4,PhotoAspectSelection(true,9,16),holdWriter=true,operatorHash=hash)
    }

    private fun exercise(count:Int,aspect:PhotoAspectSelection=PhotoAspectSelection(),cancelAfterOne:Boolean=false,holdWriter:Boolean=false,rejectCrop:Boolean=false,operatorHash:String?=null) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.CAMERA)
        val repository=SettingsRepositories.get(context);val before=repository.states.value
        val binder=AtomicReference<CaptureService.LocalBinder?>();val surface=AtomicReference<android.view.Surface?>()
        val release=CountDownLatch(1);val firstFrame=CountDownLatch(1)
        val delivered=AtomicReference<CapturedBurst?>()
        val collections=listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        fun rows():Set<Uri> = collections.flatMap {collection -> requireNotNull(context.contentResolver.query(collection,arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",arrayOf(context.packageName),null)).use { c -> buildList {while(c.moveToNext()) add(ContentUris.withAppendedId(collection,c.getLong(0)))}}}.toSet()
        val initial=rows()
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName?,service:IBinder?) {binder.set(service as CaptureService.LocalBinder)}
            override fun onServiceDisconnected(name:ComponentName?) {binder.set(null)}
        }
        var bound=false
        var cameraExecutor:java.util.concurrent.Executor?=null
        var storageWriter:java.util.concurrent.ThreadPoolExecutor?=null
        try {
            compose.runOnUiThread {repository.set(CameraSettings(audioEnabled=false,burstCount=count,photoQuality=73,photoAspect=aspect,
                operation=OperatorPreferences(startupMode=StartupMode.PHOTO,restoreExposureWhiteBalance=true,lockDuringTake=false)))}
            bound=context.bindService(Intent(context,CaptureService::class.java),connection,Context.BIND_AUTO_CREATE)
            assertTrue(bound);compose.waitUntil(10_000) {binder.get()!=null}
            val owner=requireNotNull(binder.get())
            compose.runOnUiThread {owner.prepare(640,480);owner.selectMode(CaptureMode.BURST,reopen=false)}
            val d=requireNotNull(owner.cameraStates.value.descriptor)
            val outer=owner.javaClass.getDeclaredField("this\$0").apply {isAccessible=true}.get(owner)
            val engine=CaptureService::class.java.getDeclaredMethod("getPreviewEngine").apply {isAccessible=true}.invoke(outer) as Camera2PreviewEngine
            val executor=Camera2PreviewEngine::class.java.getDeclaredField("cameraExecutor").apply {isAccessible=true}.get(engine) as java.util.concurrent.Executor
            val writer=CaptureService::class.java.getDeclaredField("storageExecutor").apply {isAccessible=true}.get(outer) as java.util.concurrent.ThreadPoolExecutor
            cameraExecutor=executor;storageWriter=writer
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
            fun installListenerProbe() {
                // Keep the executor at a real completed-frame boundary, not at a fabricated state.
                val installed=CountDownLatch(1)
                executor.execute {
                    val field=Camera2PreviewEngine::class.java.getDeclaredField("listener").apply {isAccessible=true}
                    val original=field.get(engine) as Camera2PreviewListener
                    field.set(engine,object:Camera2PreviewListener by original {
                        override fun onBurstCaptured(capture:CapturedBurst) {delivered.set(capture);original.onBurstCaptured(capture)}
                        override fun onBurstProgress(completed:Int,total:Int) {
                            original.onBurstProgress(completed,total)
                            if(cancelAfterOne && completed==1 && firstFrame.count>0) {firstFrame.countDown();check(release.await(25,TimeUnit.SECONDS))}
                        }
                    });installed.countDown()
                }
                assertTrue(installed.await(5,TimeUnit.SECONDS))
            }
            installListenerProbe()
            if(holdWriter) {
                val entered=CountDownLatch(1);writer.execute {entered.countDown();check(release.await(35,TimeUnit.SECONDS))}
                assertTrue(entered.await(5,TimeUnit.SECONDS))
            }
            compose.runOnUiThread {
                assertTrue(owner.capturePrimary(false))
                if(!rejectCrop) {
                    assertTrue(owner.cameraStates.value.stillCapturePending)
                    assertFalse("Duplicate service burst must reject",owner.capturePrimary(false))
                    assertFalse("Direct JPEG cannot overlap sequence",engine.captureJpeg())
                    assertFalse("Direct burst cannot overlap sequence",engine.captureBurst(3))
                }
            }
            if(rejectCrop) {
                compose.waitUntil(25_000) {owner.cameraStates.value.phase==CameraUiPhase.ERROR}
                assertEquals("burst-still-encode-failed",owner.cameraStates.value.errorCode)
                assertFalse(owner.cameraStates.value.stillCapturePending);assertNull(owner.cameraStates.value.lastBurstPublication)
                assertEquals(initial,rows());assertNull("Rejected group must never reach delivery",delivered.get())
                compose.runOnUiThread {
                    owner.detachPreview();repository.update {it.copy(photoAspect=PhotoAspectSelection())}
                }
                compose.waitUntil(5_000) {owner.cameraStates.value.effectiveSettings?.photoAspect==PhotoAspectSelection()}
                compose.runOnUiThread {assertTrue(owner.attachPreview(requireNotNull(surface.get()),0))}
                compose.waitUntil(15_000) {owner.cameraStates.value.phase==CameraUiPhase.PREVIEWING}
                // Reopening legitimately installs the service listener; observe the new generation.
                installListenerProbe()
                compose.runOnUiThread {assertTrue(owner.capturePrimary(false))}
                android.util.Log.i("BurstProbe","impossibleCropRejected=true artifactsBeforeRecovery=0 nextDefaultBurstAccepted=true")
            }
            if(cancelAfterOne) {
                assertTrue("First native JPEG must reach the progress boundary",firstFrame.await(15,TimeUnit.SECONDS))
                compose.runOnUiThread {assertTrue(owner.cancelBurstCapture());assertFalse(owner.cancelBurstCapture())}
                release.countDown()
                val retired=CountDownLatch(1);executor.execute {retired.countDown()};assertTrue(retired.await(5,TimeUnit.SECONDS))
                assertFalse(owner.cameraStates.value.stillCapturePending);assertEquals(initial,rows())
                assertNull(owner.cameraStates.value.lastBurstPublication)
                assertEquals(1,owner.cameraStates.value.burstCaptured)
                android.util.Log.i("BurstProbe","cancelAfterNativeFrame=1 discardedUnpublished=true artifacts=0")
                compose.runOnUiThread {assertTrue(owner.capturePrimary(false))}
            }
            if(holdWriter) {
                compose.waitUntil(25_000) {owner.cameraStates.value.burstSaving || owner.cameraStates.value.phase==CameraUiPhase.ERROR}
                assertEquals(owner.cameraStates.value.message,CameraUiPhase.CAPTURING,owner.cameraStates.value.phase)
                assertEquals(count,owner.cameraStates.value.burstCaptured);assertEquals(1,writer.queue.size)
                compose.runOnUiThread {
                    assertFalse("Native publication delivery wins over cancellation",owner.cancelBurstCapture())
                    owner.detachPreview();owner.prepare(640,480);owner.attachPreview(requireNotNull(surface.get()),0)
                    assertTrue(owner.cameraStates.value.captureControlsLocked);assertFalse(owner.capturePrimary(false))
                    repository.update {it.copy(burstCount=3,photoQuality=81,photoAspect=PhotoAspectSelection(true,17,11))}
                }
                compose.waitUntil(5_000) {owner.cameraStates.value.settingsPending}
                assertEquals(count,owner.cameraStates.value.effectiveSettings?.burstCount);assertEquals(aspect,owner.cameraStates.value.effectiveSettings?.photoAspect)
                assertEquals(73,owner.cameraStates.value.effectiveSettings?.photoQuality);assertEquals(initial,rows())
            }
            release.countDown()
            compose.waitUntil(30_000) {owner.cameraStates.value.lastBurstPublication!=null || owner.cameraStates.value.phase==CameraUiPhase.ERROR}
            val publication=requireNotNull(owner.cameraStates.value.lastBurstPublication) {owner.cameraStates.value.message ?: "Missing burst publication"}
            val native=requireNotNull(delivered.get())
            assertEquals(count,native.requestedCount);assertEquals(73,native.quality)
            assertEquals(if(rejectCrop) PhotoAspectSelection() else aspect,native.aspectSelection)
            assertFalse(owner.cameraStates.value.stillCapturePending);assertFalse(owner.cameraStates.value.burstSaving)
            assertEquals(count,publication.images.size);assertEquals(count+1,(rows()-initial).size)
            fun bytes(uri:String)=requireNotNull(context.contentResolver.openInputStream(Uri.parse(uri))).use {it.readBytes()}
            fun published(uri:String,mime:String) {
                requireNotNull(context.contentResolver.query(Uri.parse(uri),arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.MIME_TYPE),null,null,null)).use {
                    assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0));assertEquals(mime,it.getString(1))
                }
            }
            published(publication.metadataUri,"application/json")
            val metadata=Json.parseToJsonElement(bytes(publication.metadataUri).toString(Charsets.UTF_8)).jsonObject
            assertEquals(publication.id,metadata.getValue("bundleId").jsonPrimitive.content)
            assertEquals("COMPLETE",metadata.getValue("result").jsonPrimitive.content)
            assertEquals("SEQUENTIAL_JPEG_BURST",metadata.getValue("output").jsonPrimitive.content)
            assertEquals(count,metadata.getValue("requestedCount").jsonPrimitive.int)
            val frames=metadata.getValue("frames").jsonArray
            assertEquals(count,frames.size)
            val timestamps=mutableListOf<Long>();val captureIds=mutableSetOf<Long>()
            for((index,image) in publication.images.withIndex()) {
                published(image.uri,"image/jpeg")
                val frame=frames[index].jsonObject
                assertEquals(index,frame.getValue("index").jsonPrimitive.int)
                assertEquals(73,frame.getValue("quality").jsonPrimitive.int)
                val timestamp=frame.getValue("sensorTimestampNs").jsonPrimitive.long;assertTrue(timestamp>0);timestamps+=timestamp
                assertTrue(captureIds.add(frame.getValue("captureId").jsonPrimitive.long))
                val source=native.frames[index]
                assertEquals(source.capture.captureId,frame.getValue("captureId").jsonPrimitive.long)
                assertEquals(source.capture.sensorTimestampNs,timestamp)
                assertEquals(source.exposureTimeNs,frame.getValue("exposureTimeNs").jsonPrimitive.longOrNull)
                assertEquals(source.sensitivityIso,frame.getValue("sensitivityIso").jsonPrimitive.intOrNull)
                assertTrue(requireNotNull(source.exposureTimeNs)>0);assertTrue(requireNotNull(source.sensitivityIso)>0)
                assertEquals(image.uri,frame.getValue("uri").jsonPrimitive.content)
                assertEquals(image.sha256,frame.getValue("sha256").jsonPrimitive.content)
                assertEquals("OCC_${publication.id}_${(index+1).toString().padStart(2,'0')}.jpg",image.displayName)
                val content=bytes(image.uri);assertEquals(image.bytes,content.size.toLong())
                assertArrayEquals(source.capture.images.single().bytes,content)
                assertEquals(image.sha256,MessageDigest.getInstance("SHA-256").digest(content).joinToString("") {"%02x".format(it.toInt() and 255)})
                val bitmap=requireNotNull(BitmapFactory.decodeByteArray(content,0,content.size));assertTrue(bitmap.width>0 && bitmap.height>0)
                try {
                    assertPhotoAspectImage(bitmap,frame,if(rejectCrop) PhotoAspectSelection() else aspect)
                    if(!aspect.enabled || rejectCrop) assertEquals(JsonNull,frame["aspect"])
                } finally {bitmap.recycle()}
                android.util.Log.i("BurstProbe","bundle=${publication.id} index=$index sensor=$timestamp exposureNs=${frame["exposureTimeNs"]} iso=${frame["sensitivityIso"]} bytes=${image.bytes} sha256=${image.sha256} published=true decoded=true")
            }
            assertTrue(timestamps.zipWithNext().all {(a,b)->a<b})
            if(holdWriter) {
                compose.waitUntil(15_000) {!owner.cameraStates.value.settingsPending && owner.cameraStates.value.phase==CameraUiPhase.PREVIEWING}
                if (operatorHash != null) {
                    compose.waitUntil(15_000) { owner.cameraStates.value.operatorLutStatus.state == OperatorLutState.ACTIVE }
                    assertEquals(operatorHash, owner.cameraStates.value.operatorLutStatus.hash)
                    assertTrue(owner.cameraStates.value.gpuViewfinder)
                }
                assertEquals(81,owner.cameraStates.value.effectiveSettings?.photoQuality)
                assertEquals(3,owner.cameraStates.value.effectiveSettings?.burstCount);assertEquals(PhotoAspectSelection(true,17,11),owner.cameraStates.value.effectiveSettings?.photoAspect)
                android.util.Log.i("BurstProbe","detachedDuringPublication=true duplicateRejected=true pendingCountAndQualityApplied=true previewRestored=true")
            }
        } finally {
            release.countDown()
            compose.runOnUiThread {binder.get()?.detachPreview();repository.set(before)}
            cameraExecutor?.let {executor -> val drained=CountDownLatch(1);executor.execute {drained.countDown()};assertTrue(drained.await(20,TimeUnit.SECONDS))}
            storageWriter?.let {writer -> val drained=CountDownLatch(1);writer.execute {drained.countDown()};assertTrue(drained.await(25,TimeUnit.SECONDS))}
            compose.runOnUiThread {}
            if(bound) context.unbindService(connection)
            (rows()-initial).forEach {context.contentResolver.delete(it,null,null)}
        }
    }
}
