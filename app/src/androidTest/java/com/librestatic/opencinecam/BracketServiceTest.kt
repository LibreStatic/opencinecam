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

/** Native request/result/exposure evidence; not a physical illumination or HDR qualification. */
class BracketServiceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun fiveOneEvExposuresPublishExactOrderedMetadata() = exercise(BracketSelection(5,BracketStep.ONE_EV))
    @Test fun threeTwoEvExposuresKeepLegacySpacingWithoutClamping() = exercise(BracketSelection())
    @Test fun halfEvIsExactOrRejectedForActualCameraStep() = exercise(BracketSelection(3,BracketStep.HALF_EV))
    @Test fun manualExposureIsRejectedWithoutBeingOverwritten() = exercise(BracketSelection(3,BracketStep.ONE_EV),manual=true)
    @Test fun cancellingAfterOneFrameDiscardsItAndAllowsAFreshCompleteSequence() = exercise(BracketSelection(5,BracketStep.ONE_EV),cancelAfterOne=true)
    @Test fun completeBracketKeepsOwnershipThroughDetachAndPendingSettings() = exercise(BracketSelection(5,BracketStep.ONE_EV),holdWriter=true)

    @Test fun croppedBracketKeepsSameAspectAndExposureMetadataForEveryFrame() = exercise(BracketSelection(3,BracketStep.ONE_EV),aspect=PhotoAspectSelection(true,3,2))

    @Test fun operatorLutPreservesOriginalSequenceAndPublication() = withOperatorLutFixture { hash ->
        exercise(BracketSelection(3,BracketStep.ONE_EV),operatorHash=hash)
    }

    private fun exercise(selection:BracketSelection,manual:Boolean=false,cancelAfterOne:Boolean=false,holdWriter:Boolean=false,aspect:PhotoAspectSelection=PhotoAspectSelection(),operatorHash:String?=null) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.CAMERA)
        val repository=SettingsRepositories.get(context);val before=repository.states.value
        val binder=AtomicReference<CaptureService.LocalBinder?>();val surface=AtomicReference<android.view.Surface?>()
        val release=CountDownLatch(1);val firstFrame=CountDownLatch(1)
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
            compose.runOnUiThread {repository.set(CameraSettings(audioEnabled=false,bracket=selection,photoQuality=73,photoAspect=aspect,
                exposure=ExposureSelection(if(manual) ExposureMode.MANUAL else ExposureMode.AUTO,iso=100,timeNs=10_000_000),
                operation=OperatorPreferences(startupMode=StartupMode.PHOTO,restoreExposureWhiteBalance=true,lockDuringTake=false)))}
            bound=context.bindService(Intent(context,CaptureService::class.java),connection,Context.BIND_AUTO_CREATE)
            assertTrue(bound);compose.waitUntil(10_000) {binder.get()!=null}
            val owner=requireNotNull(binder.get())
            compose.runOnUiThread {owner.prepare(640,480);owner.selectMode(CaptureMode.BRACKET,reopen=false)}
            val d=requireNotNull(owner.cameraStates.value.descriptor)
            val outer=owner.javaClass.getDeclaredField("this\$0").apply {isAccessible=true}.get(owner)
            val engine=CaptureService::class.java.getDeclaredMethod("getPreviewEngine").apply {isAccessible=true}.invoke(outer) as Camera2PreviewEngine
            val executor=Camera2PreviewEngine::class.java.getDeclaredField("cameraExecutor").apply {isAccessible=true}.get(engine) as java.util.concurrent.Executor
            val writer=CaptureService::class.java.getDeclaredField("storageExecutor").apply {isAccessible=true}.get(outer) as java.util.concurrent.ThreadPoolExecutor
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
            val expected=selection.resolve(d.aeCompensationRange?.lower,d.aeCompensationRange?.upper,
                d.aeCompensationStepNumerator,d.aeCompensationStepDenominator,!manual && 1 in d.photoFlashCapabilities.aeModes)
            if(cancelAfterOne && expected is BracketResolution.Plan) {
                // Keep the executor at a real completed-frame boundary, not at a fabricated state.
                val installed=CountDownLatch(1)
                executor.execute {
                    val field=Camera2PreviewEngine::class.java.getDeclaredField("listener").apply {isAccessible=true}
                    val original=field.get(engine) as Camera2PreviewListener
                    field.set(engine,object:Camera2PreviewListener by original {
                        override fun onBracketProgress(completed:Int,total:Int) {
                            original.onBracketProgress(completed,total)
                            if(completed==1 && firstFrame.count>0) {firstFrame.countDown();check(release.await(25,TimeUnit.SECONDS))}
                        }
                    });installed.countDown()
                }
                assertTrue(installed.await(5,TimeUnit.SECONDS))
            }
            if(holdWriter && expected is BracketResolution.Plan) {
                val entered=CountDownLatch(1);writer.execute {entered.countDown();check(release.await(35,TimeUnit.SECONDS))}
                assertTrue(entered.await(5,TimeUnit.SECONDS))
            }
            compose.runOnUiThread {
                owner.capturePrimary(false)
                if(expected is BracketResolution.Plan) {
                    assertTrue(owner.cameraStates.value.stillCapturePending)
                    assertFalse("Duplicate service bracket must reject",owner.capturePrimary(false))
                    assertFalse("Direct JPEG cannot overlap sequence",engine.captureJpeg())
                    assertFalse("Direct burst cannot overlap sequence",engine.captureBurst(3))
                }
            }
            if(expected is BracketResolution.Rejected) {
                compose.waitUntil(15_000) {owner.cameraStates.value.phase==CameraUiPhase.ERROR}
                assertEquals(initial,rows());assertNull(owner.cameraStates.value.lastBracketPublication)
                assertFalse(owner.cameraStates.value.stillCapturePending)
                assertEquals(if(manual) "bracket-exposure-unsupported" else "bracket-rejected",owner.cameraStates.value.errorCode)
                assertEquals(if(manual) ExposureMode.MANUAL else ExposureMode.AUTO,repository.states.value.exposure.mode)
                android.util.Log.i("BracketProbe","selection=$selection result=REJECTED expectedReason=${expected.reason} actualCode=${owner.cameraStates.value.errorCode} artifacts=0 aeRange=${d.aeCompensationRange} aeStep=${d.aeCompensationStepNumerator}/${d.aeCompensationStepDenominator}")
                return
            }
            if(cancelAfterOne) {
                assertTrue("First native JPEG must reach the progress boundary",firstFrame.await(15,TimeUnit.SECONDS))
                compose.runOnUiThread {assertTrue(owner.cancelBracketCapture());assertFalse(owner.cancelBracketCapture())}
                release.countDown()
                val retired=CountDownLatch(1);executor.execute {retired.countDown()};assertTrue(retired.await(5,TimeUnit.SECONDS))
                assertFalse(owner.cameraStates.value.stillCapturePending);assertEquals(initial,rows())
                assertNull(owner.cameraStates.value.lastBracketPublication)
                assertEquals(1,owner.cameraStates.value.bracketCaptured)
                android.util.Log.i("BracketProbe","cancelAfterNativeFrame=1 discardedUnpublished=true artifacts=0")
                compose.runOnUiThread {assertTrue(owner.capturePrimary(false))}
            }
            if(holdWriter) {
                compose.waitUntil(25_000) {owner.cameraStates.value.bracketSaving || owner.cameraStates.value.phase==CameraUiPhase.ERROR}
                assertEquals(owner.cameraStates.value.message,CameraUiPhase.CAPTURING,owner.cameraStates.value.phase)
                assertEquals(selection.count,owner.cameraStates.value.bracketCaptured);assertEquals(1,writer.queue.size)
                compose.runOnUiThread {
                    assertFalse("Native publication delivery wins over cancellation",owner.cancelBracketCapture())
                    owner.detachPreview();owner.prepare(640,480);owner.attachPreview(requireNotNull(surface.get()),0)
                    assertTrue(owner.cameraStates.value.captureControlsLocked);assertFalse(owner.capturePrimary(false))
                    repository.update {it.copy(bracket=BracketSelection(3,BracketStep.ONE_EV),photoQuality=81)}
                }
                compose.waitUntil(5_000) {owner.cameraStates.value.settingsPending}
                assertEquals(selection,owner.cameraStates.value.effectiveSettings?.bracket)
                assertEquals(73,owner.cameraStates.value.effectiveSettings?.photoQuality);assertEquals(initial,rows())
            }
            release.countDown()
            compose.waitUntil(30_000) {owner.cameraStates.value.lastBracketPublication!=null || owner.cameraStates.value.phase==CameraUiPhase.ERROR}
            val publication=requireNotNull(owner.cameraStates.value.lastBracketPublication) {owner.cameraStates.value.message ?: "Missing bracket publication"}
            assertFalse(owner.cameraStates.value.stillCapturePending);assertFalse(owner.cameraStates.value.bracketSaving)
            assertEquals(selection.count,publication.images.size);assertEquals(selection.count+1,(rows()-initial).size)
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
            assertEquals("SEPARATE_EXPOSURES_NOT_HDR",metadata.getValue("output").jsonPrimitive.content)
            assertEquals(selection.count,metadata.getValue("selection").jsonObject.getValue("count").jsonPrimitive.int)
            assertEquals(selection.step.name,metadata.getValue("selection").jsonObject.getValue("step").jsonPrimitive.content)
            val frames=metadata.getValue("frames").jsonArray
            assertEquals(selection.count,frames.size)
            val timestamps=mutableListOf<Long>();val captureIds=mutableSetOf<Long>();val exposureProducts=mutableListOf<Double>()
            for((index,image) in publication.images.withIndex()) {
                published(image.uri,"image/jpeg")
                val frame=frames[index].jsonObject;val planned=(expected as BracketResolution.Plan).exposures[index]
                assertEquals(index,frame.getValue("index").jsonPrimitive.int)
                assertEquals(planned.ev,frame.getValue("requestedEv").jsonPrimitive.double,0.00001)
                assertEquals(planned.compensationIndex,frame.getValue("submittedCompensation").jsonPrimitive.int)
                assertEquals(planned.compensationIndex,frame.getValue("reportedCompensation").jsonPrimitive.int)
                assertEquals(73,frame.getValue("quality").jsonPrimitive.int)
                val timestamp=frame.getValue("sensorTimestampNs").jsonPrimitive.long;assertTrue(timestamp>0);timestamps+=timestamp
                assertTrue(captureIds.add(frame.getValue("captureId").jsonPrimitive.long))
                val exposure=frame.getValue("exposureTimeNs").jsonPrimitive.long;val iso=frame.getValue("sensitivityIso").jsonPrimitive.int
                assertTrue(exposure>0);assertTrue(iso>0);exposureProducts+=exposure.toDouble()*iso
                assertEquals(image.uri,frame.getValue("uri").jsonPrimitive.content)
                assertEquals(image.sha256,frame.getValue("sha256").jsonPrimitive.content)
                assertEquals("OCC_${publication.id}_${(index+1).toString().padStart(2,'0')}.jpg",image.displayName)
                val content=bytes(image.uri);assertEquals(image.bytes,content.size.toLong())
                assertEquals(image.sha256,MessageDigest.getInstance("SHA-256").digest(content).joinToString("") {"%02x".format(it.toInt() and 255)})
                val bitmap=requireNotNull(BitmapFactory.decodeByteArray(content,0,content.size));assertTrue(bitmap.width>0 && bitmap.height>0)
                try {assertPhotoAspectImage(bitmap,frame,aspect)} finally {bitmap.recycle()}
                android.util.Log.i("BracketProbe","bundle=${publication.id} index=$index requestedEv=${planned.ev} submitted=${planned.compensationIndex} reported=${frame.getValue("reportedCompensation")} sensor=$timestamp exposureNs=$exposure iso=$iso bytes=${image.bytes} sha256=${image.sha256} published=true decoded=true")
            }
            assertTrue(timestamps.zipWithNext().all {(a,b)->a<b})
            android.util.Log.i("BracketProbe","selection=$selection complete=true jpegCount=${publication.images.size} uniqueOrderedTimestamps=true reportedExposureProducts=$exposureProducts physicalExposureAccepted=false mergedHDR=false")
            if(holdWriter) {
                compose.waitUntil(15_000) {!owner.cameraStates.value.settingsPending && owner.cameraStates.value.phase==CameraUiPhase.PREVIEWING}
                assertEquals(81,owner.cameraStates.value.effectiveSettings?.photoQuality)
                assertEquals(BracketSelection(3,BracketStep.ONE_EV),owner.cameraStates.value.effectiveSettings?.bracket)
                android.util.Log.i("BracketProbe","detachedDuringPublication=true duplicateRejected=true pendingCountAndQualityApplied=true previewRestored=true")
            }
        } finally {
            release.countDown()
            compose.runOnUiThread {binder.get()?.detachPreview();repository.set(before)}
            if(bound) context.unbindService(connection)
            (rows()-initial).forEach {context.contentResolver.delete(it,null,null)}
        }
    }
}
