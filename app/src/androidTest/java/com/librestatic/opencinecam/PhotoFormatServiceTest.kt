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
import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import com.librestatic.opencinecam.storage.*
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

/** Actual Camera2 and MediaStore. Missing advertised formats prove rejection, not encoding. */
class PhotoFormatServiceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun geotagIsFrozenAtServiceAdmissionDespiteOptOutBeforePublication() = exercise(StillPhotoFormat.JPEG,holdWriter=true,geotag=true)
    @Test fun publishedDngOpensFromCatalogAsOrientedReadonlyPhotoAndRetires() = exercise(StillPhotoFormat.DNG, reviewPhoto=true)
    @Test fun publishedRawJpegPairReviewsBothActualMembersWithoutChangingRelations() = exercise(StillPhotoFormat.RAW_JPEG, reviewPhoto=true)
    @Test fun jpegPublishesQualityAndCaptureRelation() = exercise(StillPhotoFormat.JPEG)
    @Test fun rawAndJpegAreOneExposureOrRejectMissingRawCapability() = exercise(StillPhotoFormat.RAW_JPEG)
    @Test fun rawModePublishesDngAndRelationOrRejectsMissingCapability() = exercise(StillPhotoFormat.DNG)
    @Test fun heicIsNativelyEncodedOrRejectedWithoutJpegFallback() = exercise(StillPhotoFormat.HEIC)
    @Test fun publicationOwnerSurvivesDetachAndRejectsSecondCaptureUntilWriterFinishes() = exercise(StillPhotoFormat.JPEG, holdWriter=true)
    @Test fun orderlyDestructionDrainsAnAlreadyAcceptedPublication() = exercise(StillPhotoFormat.JPEG, holdWriter=true, destroy=true)
    @Test fun cancelledQueuedPairPublishesNeitherMember() = exercise(StillPhotoFormat.RAW_JPEG, cancel=true)

    @Test fun customWideAspectIsAppliedToJpegPixelsAndRelatedMetadata() = exercise(StillPhotoFormat.JPEG,aspect=PhotoAspectSelection(true,239,100))
    @Test fun portraitCropChangesOnlyJpegInRawPair() = exercise(StillPhotoFormat.RAW_JPEG,aspect=PhotoAspectSelection(true,9,16))
    @Test fun rawModeRetainsNativeRasterAndDeclaresUnappliedRequestedAspect() = exercise(StillPhotoFormat.DNG,aspect=PhotoAspectSelection(true,1,1))
    @Test fun cropStaysFrozenUntilHeldWriterPublishesAndThenPendingRatioApplies() = exercise(StillPhotoFormat.JPEG,holdWriter=true,aspect=PhotoAspectSelection(true,1,1))
    @Test fun croppedPairCancelledBeforeSubmissionPublishesNeitherMember() = exercise(StillPhotoFormat.RAW_JPEG,cancel=true,aspect=PhotoAspectSelection(true,1,1))
    @Test fun unrepresentableCropDiscardsAndRecoversWithOriginalRaster() = exercise(StillPhotoFormat.JPEG,aspect=PhotoAspectSelection(true,10000,9999),rejectCrop=true)
    @Test fun heicCropPreservesFormatOrRejectsMissingAdvertisedCapability() = exercise(StillPhotoFormat.HEIC,aspect=PhotoAspectSelection(true,1,1))

    @Test fun productionSlatePhotoCommitsThenIncrementsExactlyOnceDespiteDuplicateAdmission() = exercise(
        StillPhotoFormat.JPEG, productionSlate = ProductionSlateSettings(project="Photo slate", scene="12B",
            takeNumber=7, location=ProductionSlateLocation.INTERIOR, timeOfDay=ProductionSlateTimeOfDay.DAY,
            goodTake=true, autoIncrementTake=true))

    @Test fun heldPhotoWriterRetainsFrozenSlateAndDoesNotOverwriteNextEditorialIntent() = exercise(
        StillPhotoFormat.JPEG, holdWriter=true,
        productionSlate=ProductionSlateSettings(project="Original", takeNumber=7, autoIncrementTake=true),
        nextProductionSlate=ProductionSlateSettings(project="Next scene", scene="21", takeNumber=90, autoIncrementTake=true))

    @Test fun cancelledQueuedPhotoDoesNotIncrementProductionSlate() = exercise(
        StillPhotoFormat.JPEG, cancel=true,
        productionSlate=ProductionSlateSettings(project="Cancelled", takeNumber=7, autoIncrementTake=true))

    @Test fun immediateRepositorySlateUpdateIsFrozenWithoutWaitingForSettingsCollector() = exercise(
        StillPhotoFormat.JPEG, updateSlateAtAdmission=true,
        productionSlate=ProductionSlateSettings(project="Same UI turn", scene="New scene", takeNumber=31, autoIncrementTake=true))

    @Test fun committedPhotoAdvancesFrozenTakeAfterServiceDestructionWithoutUiCallback() = exercise(
        StillPhotoFormat.JPEG, holdWriter=true, destroy=true,
        productionSlate=ProductionSlateSettings(project="Destroyed service", takeNumber=51, autoIncrementTake=true))

    @Test fun configuredPhotoNamesShareOneStemWithMetadataAndKeepUuidNamespace() = exercise(
        StillPhotoFormat.JPEG, productionSlate=ProductionSlateSettings(project="Name Ñ",scene="A/2",takeNumber=7,autoIncrementTake=true),
        captureNaming=CaptureNamingSettings(true,"{project}_{scene}_T{take}"))
    @Test fun heldPhotoWriterKeepsAdmittedNamesWhenNextTemplateAndSlateChange() = exercise(
        StillPhotoFormat.JPEG,holdWriter=true,
        productionSlate=ProductionSlateSettings(project="Original",scene="A",takeNumber=7,autoIncrementTake=true),
        nextProductionSlate=ProductionSlateSettings(project="Next",scene="B",takeNumber=90,autoIncrementTake=true),
        captureNaming=CaptureNamingSettings(true,"{project}_T{take}"),nextCaptureNaming=CaptureNamingSettings(true,"NEXT_{scene}"))
    @Test fun immediateNamingRepositoryUpdateIsFrozenWithoutWaitingForCollector() = exercise(
        StillPhotoFormat.JPEG,updateSlateAtAdmission=true,
        productionSlate=ProductionSlateSettings(project="Immediate",scene="C",takeNumber=31,autoIncrementTake=true),
        captureNaming=CaptureNamingSettings(true,"{project}_{scene}_{take}"))

    private fun exercise(format:StillPhotoFormat,holdWriter:Boolean=false,cancel:Boolean=false,destroy:Boolean=false,aspect:PhotoAspectSelection=PhotoAspectSelection(),rejectCrop:Boolean=false,
        productionSlate:ProductionSlateSettings?=null,nextProductionSlate:ProductionSlateSettings?=null,updateSlateAtAdmission:Boolean=false, captureNaming:CaptureNamingSettings?=null,nextCaptureNaming:CaptureNamingSettings?=null,reviewPhoto:Boolean=false,geotag:Boolean=false) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.CAMERA)
        val repository=SettingsRepositories.get(context);val before=repository.states.value
        val binder=AtomicReference<CaptureService.LocalBinder?>();val surface=AtomicReference<android.view.Surface?>()
        val release=CountDownLatch(1)
        val engineExecutor=AtomicReference<java.util.concurrent.Executor?>()
        val cameraEngine=AtomicReference<Camera2PreviewEngine?>()
        val storageWriter=AtomicReference<java.util.concurrent.ThreadPoolExecutor?>()
        var expectedAspect=aspect
        val collections=listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        fun rows(collection:Uri):Set<Uri> = requireNotNull(context.contentResolver.query(collection,arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",arrayOf(context.packageName),null)).use { c -> buildSet { while(c.moveToNext()) add(ContentUris.withAppendedId(collection,c.getLong(0))) } }
        fun rows()=collections.flatMap(::rows).toSet()
        val initial=rows()
        val reviewSelection=mutableStateOf<MediaReviewSelection?>(null)
        var locationProvider:GeotagTestProvider?=null
        var admittedLocation:CaptureLocationSnapshot?=null
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName?,service:IBinder?) {binder.set(service as CaptureService.LocalBinder)}
            override fun onServiceDisconnected(name:ComponentName?) {binder.set(null)}
        }
        var bound=false
        try {
            // Open a valid baseline JPEG graph first, including tests of unavailable formats.
            compose.runOnUiThread {repository.set(CameraSettings(audioEnabled=false,photoQuality=73,photoAspect=aspect,
                captureNaming=if(updateSlateAtAdmission) CaptureNamingSettings() else captureNaming ?: CaptureNamingSettings(),
                productionSlate=if(updateSlateAtAdmission) ProductionSlateSettings(project="Before same-turn update",takeNumber=2)
                    else productionSlate ?: ProductionSlateSettings(),
                operation=OperatorPreferences(startupMode=StartupMode.PHOTO,lockDuringTake=false)))}
            bound=context.bindService(Intent(context,CaptureService::class.java),connection,Context.BIND_AUTO_CREATE)
            assertTrue(bound);compose.waitUntil(10_000) {binder.get()!=null}
            val owner=requireNotNull(binder.get())
            compose.runOnUiThread {owner.prepare(640,480);owner.selectMode(CaptureMode.PHOTO,reopen=false)}
            val d=requireNotNull(owner.cameraStates.value.descriptor)
            val outer=owner.javaClass.getDeclaredField("this\$0").apply {isAccessible=true}.get(owner)
            val engine=CaptureService::class.java.getDeclaredMethod("getPreviewEngine").apply {isAccessible=true}.invoke(outer) as Camera2PreviewEngine
            cameraEngine.set(engine)
            engineExecutor.set(Camera2PreviewEngine::class.java.getDeclaredField("cameraExecutor").apply {isAccessible=true}.get(engine) as java.util.concurrent.Executor)
            storageWriter.set(CaptureService::class.java.getDeclaredField("storageExecutor").apply {isAccessible=true}.get(outer) as java.util.concurrent.ThreadPoolExecutor)
            val reviewPlayback=repository.states.value.playback
            compose.setContent {MaterialTheme {AndroidView(factory={ host -> SurfaceView(host).apply {
                holder.setFixedSize(d.previewSize.width,d.previewSize.height)
                holder.addCallback(object:SurfaceHolder.Callback {
                    override fun surfaceCreated(holder:SurfaceHolder) {surface.set(holder.surface)}
                    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int) {surface.set(holder.surface)}
                    override fun surfaceDestroyed(holder:SurfaceHolder) {surface.set(null)}
                })
            }},modifier=Modifier.fillMaxSize())
                reviewSelection.value?.let { selection -> MediaPlaybackDialog(selection,reviewPlayback,
                    { error("Readonly photo review must not write preferences") }, {reviewSelection.value=null},
                    { error("This published capture has no next page") }) }
            }}
            compose.waitUntil(10_000) {surface.get()?.isValid==true}
            compose.runOnUiThread {assertTrue(owner.attachPreview(requireNotNull(surface.get()),0))}
            compose.waitUntil(15_000) {owner.cameraStates.value.phase in setOf(CameraUiPhase.PREVIEWING,CameraUiPhase.ERROR)}
            assertEquals(owner.cameraStates.value.message,CameraUiPhase.PREVIEWING,owner.cameraStates.value.phase)
            val supported=when(format) {
                StillPhotoFormat.JPEG -> true
                StillPhotoFormat.HEIC -> d.heicSize!=null
                StillPhotoFormat.RAW_JPEG,StillPhotoFormat.DNG -> d.supportsRaw && d.rawSize!=null
            }
            if(reviewPhoto) assertTrue("Positive DNG review requires advertised RAW capture",supported)
            if(!supported) {
                compose.runOnUiThread {
                    assertFalse("Unadvertised $format must reject, not fall back",engine.captureStill(format,quality=73,aspect=aspect))
                    if(format==StillPhotoFormat.DNG) {
                        owner.selectMode(CaptureMode.RAW_PHOTO)
                        assertEquals(CaptureMode.PHOTO,owner.cameraStates.value.selectedMode)
                    } else repository.update {it.copy(photoFormat=format)}
                }
                if(format!=StillPhotoFormat.DNG) {
                    compose.waitUntil(15_000) {owner.cameraStates.value.effectiveSettings?.photoFormat==format &&
                        owner.cameraStates.value.phase in setOf(CameraUiPhase.ERROR,CameraUiPhase.PREVIEWING)}
                    if(owner.cameraStates.value.phase==CameraUiPhase.PREVIEWING) compose.runOnUiThread {
                        assertFalse("Missing RAW output must reject service capture",owner.capturePrimary(false))
                    }
                    assertEquals(CameraUiPhase.ERROR,owner.cameraStates.value.phase)
                    assertFalse(owner.cameraStates.value.stillCapturePending)
                    assertEquals(format,repository.states.value.photoFormat)
                }
                val retired=CountDownLatch(1)
                (Camera2PreviewEngine::class.java.getDeclaredField("cameraExecutor").apply {isAccessible=true}.get(engine) as java.util.concurrent.Executor).execute {retired.countDown()}
                assertTrue(retired.await(5,TimeUnit.SECONDS));assertEquals(initial,rows())
                assertNull(owner.cameraStates.value.lastStillPublication)
                android.util.Log.i("PhotoFormatProbe","format=$format capability=ABSENT nativeEncodingAccepted=false fallback=false artifacts=0 raw=${d.rawSize} heic=${d.heicSize}")
                return
            }
            if(format!=StillPhotoFormat.JPEG) {
                compose.runOnUiThread {
                    if(format==StillPhotoFormat.DNG) owner.selectMode(CaptureMode.RAW_PHOTO)
                    else repository.update {it.copy(photoFormat=format)}
                }
                val observed=mutableListOf<String>()
                try {
                    compose.waitUntil(15_000) {
                        val state=owner.cameraStates.value
                        val snapshot="${state.phase}/${state.effectiveSettings?.photoFormat}/pending=${state.settingsPending}/still=${state.stillCapturePending}/error=${state.errorCode}"
                        if(observed.lastOrNull()!=snapshot && observed.size<32) observed+=snapshot
                        state.effectiveSettings?.photoFormat==(if(format==StillPhotoFormat.DNG) StillPhotoFormat.JPEG else format) &&
                            state.phase in setOf(CameraUiPhase.PREVIEWING,CameraUiPhase.ERROR)
                    }
                } finally {
                    android.util.Log.i("PhotoFormatProbe","formatSwitchTarget=$format observed=$observed repositoryFormat=${repository.states.value.photoFormat}")
                }
                assertEquals(owner.cameraStates.value.message,CameraUiPhase.PREVIEWING,owner.cameraStates.value.phase)
            }
            if(geotag) {
                val provider=GeotagTestProvider(context);locationProvider=provider
                compose.runOnUiThread {repository.update {it.copy(geotaggingEnabled=true)}}
                provider.foreground(true);provider.publish()
                admittedLocation=provider.waitFor {it?.status==CaptureLocationStatus.AVAILABLE}
            }
            if(holdWriter || cancel) {
                val entered=CountDownLatch(1)
                val executor=if(holdWriter) CaptureService::class.java.getDeclaredField("storageExecutor").apply {isAccessible=true}.get(outer)
                    else Camera2PreviewEngine::class.java.getDeclaredField("cameraExecutor").apply {isAccessible=true}.get(engine)
                (executor as java.util.concurrent.Executor).execute {entered.countDown();check(release.await(25,TimeUnit.SECONDS))}
                assertTrue(entered.await(5,TimeUnit.SECONDS))
            }
            compose.runOnUiThread {
                if(updateSlateAtAdmission) {
                    val latest=requireNotNull(productionSlate)
                    assertNotEquals(latest,owner.cameraStates.value.effectiveSettings?.productionSlate)
                    repository.update {it.copy(productionSlate=latest,captureNaming=captureNaming ?: it.captureNaming)}
                    // No main-loop yield/collector wait between the edit and capture admission.
                }
                productionSlate?.let { assertEquals("Before admission no capture has committed", it, repository.states.value.productionSlate) }
                assertTrue(owner.capturePrimary(false))
                assertTrue(owner.cameraStates.value.stillCapturePending)
                assertFalse("Duplicate service admission",owner.capturePrimary(false))
                if(cancel) {owner.detachPreview();assertFalse(engine.cancelStill())}
            }
            if(geotag) {
                // The real admission already owns its location. Opt-out must affect only future captures.
                compose.runOnUiThread {repository.update {it.copy(geotaggingEnabled=false)};locationProvider!!.runtime.refreshAccess()}
                assertNull(locationProvider!!.runtime.snapshot())
            }
            if(rejectCrop) {
                compose.waitUntil(20_000) {owner.cameraStates.value.phase==CameraUiPhase.ERROR}
                assertEquals("still-encode-failed",owner.cameraStates.value.errorCode)
                assertFalse(owner.cameraStates.value.stillCapturePending);assertNull(owner.cameraStates.value.lastStillPublication)
                assertEquals(initial,rows())
                android.util.Log.i("PhotoFormatProbe","unrepresentableCropFailure=${owner.cameraStates.value.errorCode} artifacts=0")
                expectedAspect=PhotoAspectSelection()
                compose.runOnUiThread {repository.update {it.copy(photoAspect=expectedAspect)}}
                compose.waitUntil(5_000) {owner.cameraStates.value.effectiveSettings?.photoAspect==expectedAspect}
                compose.runOnUiThread {owner.detachPreview();assertTrue(owner.attachPreview(requireNotNull(surface.get()),0))}
                compose.waitUntil(15_000) {owner.cameraStates.value.phase==CameraUiPhase.PREVIEWING}
                compose.runOnUiThread {assertTrue(owner.capturePrimary(false))}
            }
            if(cancel) {
                release.countDown()
                val retired=CountDownLatch(1)
                (Camera2PreviewEngine::class.java.getDeclaredField("cameraExecutor").apply {isAccessible=true}.get(engine) as java.util.concurrent.Executor).execute {retired.countDown()}
                assertTrue(retired.await(5,TimeUnit.SECONDS))
                assertEquals(CameraUiPhase.READY,owner.cameraStates.value.phase);assertFalse(owner.cameraStates.value.stillCapturePending)
                assertEquals(initial,rows());assertNull(owner.cameraStates.value.lastStillPublication)
                compose.runOnUiThread { productionSlate?.let { assertEquals(it, repository.states.value.productionSlate) } }
                android.util.Log.i("PhotoFormatProbe","format=$format cancelledBeforeSubmission=true artifacts=0 ownerRetired=true")
                return
            }
            if(holdWriter) {
                val writer=CaptureService::class.java.getDeclaredField("storageExecutor").apply {isAccessible=true}.get(outer) as java.util.concurrent.ThreadPoolExecutor
                compose.waitUntil(15_000) {writer.queue.size==1 || owner.cameraStates.value.phase==CameraUiPhase.ERROR}
                assertEquals(1,writer.queue.size)
                compose.runOnUiThread {
                    owner.detachPreview()
                    assertTrue(owner.cameraStates.value.stillCapturePending);assertTrue(owner.cameraStates.value.captureControlsLocked)
                    owner.prepare(640,480)
                    owner.attachPreview(requireNotNull(surface.get()),0)
                    assertFalse("Native delivery must not release service publication ownership",owner.capturePrimary(false))
                    repository.update {it.copy(photoQuality=81,photoAspect=PhotoAspectSelection(true,9,16),
                        productionSlate=nextProductionSlate ?: it.productionSlate,captureNaming=nextCaptureNaming ?: it.captureNaming,photoFormat=if(d.supportsRaw && d.rawSize!=null) StillPhotoFormat.RAW_JPEG else StillPhotoFormat.JPEG)}
                }
                compose.waitUntil(5_000) {owner.cameraStates.value.settingsPending}
                assertEquals(73,owner.cameraStates.value.effectiveSettings?.photoQuality)
                assertEquals(aspect,owner.cameraStates.value.effectiveSettings?.photoAspect)
                productionSlate?.let { assertEquals(it, owner.cameraStates.value.effectiveSettings?.productionSlate) }
                nextProductionSlate?.let { assertEquals(it, repository.states.value.productionSlate) }
                assertEquals(initial,rows())
                if(destroy) {
                    compose.runOnUiThread {context.unbindService(connection);bound=false}
                    compose.waitUntil(5_000) {writer.isShutdown}
                    assertEquals("Orderly shutdown retains the accepted publication",1,writer.queue.size)
                }
            }
            release.countDown()
            if(format==StillPhotoFormat.HEIC && aspect.enabled) {
                val size=requireNotNull(d.heicSize)
                val side=minOf(size.width,size.height) // This service fixture requests1:1.
                val advertised=Class.forName("com.librestatic.opencinecam.camera.HeicBitmapEncoderKt")
                    .getDeclaredMethod("canEncodeHeicBitmap",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
                    .apply {isAccessible=true}.invoke(null,side,side) as Boolean
                if(!advertised) {
                    compose.waitUntil(25_000) {owner.cameraStates.value.phase==CameraUiPhase.ERROR}
                    assertEquals("still-encode-failed",owner.cameraStates.value.errorCode)
                    assertFalse(owner.cameraStates.value.stillCapturePending)
                    assertNull(owner.cameraStates.value.lastStillPublication);assertEquals(initial,rows())
                    assertEquals(StillPhotoFormat.HEIC,repository.states.value.photoFormat)
                    assertEquals(aspect,repository.states.value.photoAspect)
                    android.util.Log.i("PhotoFormatProbe","HEICcamera=true cropEncoder=false fallback=false artifacts=0")
                    return
                }
            }
            val publication = if(destroy) {
                compose.waitUntil(25_000) {
                    val added=rows()-initial
                    added.size==2 && added.all { uri -> requireNotNull(context.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)).use {it.moveToFirst() && it.getInt(0)==0} }
                }
                val relationUri=(rows(MediaStore.Downloads.EXTERNAL_CONTENT_URI)-initial).single()
                val relation=Json.parseToJsonElement(requireNotNull(context.contentResolver.openInputStream(relationUri)).use {it.readBytes().toString(Charsets.UTF_8)}).jsonObject
                assertNull("Destroyed service must not apply completion state",owner.cameraStates.value.lastStillPublication)
                com.librestatic.opencinecam.storage.StillPublication(relation.getValue("bundleId").jsonPrimitive.content,relation.getValue("sensorTimestampNs").jsonPrimitive.long,
                    relation.getValue("images").jsonArray.map { entry -> val image=entry.jsonObject
                        com.librestatic.opencinecam.storage.StillPublishedImage(StillImageKind.valueOf(image.getValue("kind").jsonPrimitive.content),image.getValue("uri").jsonPrimitive.content,
                            image.getValue("displayName").jsonPrimitive.content,image.getValue("sha256").jsonPrimitive.content,image.getValue("bytes").jsonPrimitive.long)
                    },relationUri.toString())
            } else {
                compose.waitUntil(25_000) {owner.cameraStates.value.lastStillPublication!=null || owner.cameraStates.value.phase==CameraUiPhase.ERROR}
                assertFalse(owner.cameraStates.value.stillCapturePending)
                requireNotNull(owner.cameraStates.value.lastStillPublication) {owner.cameraStates.value.message ?: "Missing publication"}
            }
            val expectedKinds=when(format) {
                StillPhotoFormat.JPEG -> setOf(StillImageKind.JPEG)
                StillPhotoFormat.RAW_JPEG -> setOf(StillImageKind.JPEG,StillImageKind.DNG)
                StillPhotoFormat.DNG -> setOf(StillImageKind.DNG)
                StillPhotoFormat.HEIC -> setOf(StillImageKind.HEIC)
            }
            assertEquals(expectedKinds,publication.images.map {it.kind}.toSet())
            assertEquals(expectedKinds.size+1,(rows()-initial).size)
            fun bytes(uri:String)=requireNotNull(context.contentResolver.openInputStream(Uri.parse(uri))).use {it.readBytes()}
            fun row(uri:String,mime:String) {
                requireNotNull(context.contentResolver.query(Uri.parse(uri),arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.MIME_TYPE),null,null,null)).use {
                    assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0));assertEquals(mime,it.getString(1))
                }
            }
            row(publication.metadataUri,"application/json")
            val metadata=Json.parseToJsonElement(bytes(publication.metadataUri).toString(Charsets.UTF_8)).jsonObject
            productionSlate?.let { frozen ->
                assertEquals(frozen, parseProductionSlateJson(metadata.getValue("productionSlate").jsonObject))
                val expected = nextProductionSlate ?: frozen.copy(takeNumber=frozen.takeNumber+1)
                compose.waitUntil(5_000) { repository.states.value.productionSlate == expected }
                compose.runOnUiThread { assertEquals("A single committed image advances at most once", expected, repository.states.value.productionSlate) }
            }
            captureNaming?.let { naming ->
                val stem=captureFileStem(publication.id,CaptureNameSnapshot(naming,requireNotNull(productionSlate),0))
                publication.images.forEach { image ->
                    assertEquals(stem+"."+image.displayName.substringAfterLast('.'),image.displayName)
                    context.contentResolver.query(Uri.parse(image.uri),arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.RELATIVE_PATH),null,null,null)!!.use {
                        assertTrue(it.moveToFirst());assertEquals(image.displayName,it.getString(0))
                        assertEquals("DCIM/OpenCineCam/OCC_${publication.id}/",it.getString(1))
                    }
                }
                context.contentResolver.query(Uri.parse(publication.metadataUri),arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)!!.use {
                    assertTrue(it.moveToFirst());assertEquals(stem+".still.json",it.getString(0))
                }
                assertEquals(nextCaptureNaming ?: naming,repository.states.value.captureNaming)
            }
            assertEquals(publication.id,metadata.getValue("bundleId").jsonPrimitive.content)
            assertEquals(73,metadata.getValue("quality").jsonPrimitive.int)
            assertTrue(metadata.getValue("captureId").jsonPrimitive.long>0)
            assertEquals(publication.sensorTimestampNs,metadata.getValue("sensorTimestampNs").jsonPrimitive.long)
            assertEquals(publication.sensorTimestampNs,metadata.getValue("flash").jsonObject.getValue("sensorTimestampNs").jsonPrimitive.long)
            for(image in publication.images) {
                val mime=when(image.kind) {StillImageKind.JPEG->"image/jpeg";StillImageKind.DNG->"image/x-adobe-dng";StillImageKind.HEIC->"image/heic"}
                row(image.uri,mime)
                val content=bytes(image.uri)
                assertEquals(image.bytes,content.size.toLong())
                assertEquals(image.sha256,MessageDigest.getInstance("SHA-256").digest(content).joinToString("") {"%02x".format(it.toInt() and 255)})
                val expectedStem=captureFileStem(publication.id,captureNaming?.let {
                    CaptureNameSnapshot(it,requireNotNull(productionSlate),0)
                })
                val extension=when(image.kind) { StillImageKind.JPEG->"jpg";StillImageKind.DNG->"dng";StillImageKind.HEIC->"heic" }
                assertEquals(expectedStem+"."+extension,image.displayName)
                val relation=metadata.getValue("images").jsonArray.single {it.jsonObject.getValue("kind").jsonPrimitive.content==image.kind.name}.jsonObject
                assertEquals(image.uri,relation.getValue("uri").jsonPrimitive.content)
                assertEquals(image.sha256,relation.getValue("sha256").jsonPrimitive.content)
                assertEquals(publication.sensorTimestampNs,relation.getValue("sensorTimestampNs").jsonPrimitive.long)
                if(image.kind==StillImageKind.DNG && expectedAspect.enabled) {
                    // Read the real DNG orientation using the pinned runtime parser, without
                    // adding an app compile dependency or treating its thumbnail as RAW pixels.
                    val type=Class.forName("androidx.exifinterface.media.ExifInterface")
                    val exif=type.getConstructor(java.io.InputStream::class.java).newInstance(java.io.ByteArrayInputStream(content))
                    val orientation=type.getMethod("getAttributeInt",String::class.java,java.lang.Integer.TYPE).invoke(exif,"Orientation",0) as Int
                    val degrees=mapOf(1 to 0,6 to 90,3 to 180,8 to 270).getValue(orientation)
                    assertEquals(degrees,relation.getValue("orientationDegrees").jsonPrimitive.int)
                    assertEquals(degrees,relation.getValue("aspect").jsonObject.getValue("outputOrientationDegrees").jsonPrimitive.int)
                    android.util.Log.i("PhotoFormatProbe","rawNativeExifOrientation=$orientation declaredDegrees=$degrees matched=true")
                }
                val bitmap=requireNotNull(BitmapFactory.decodeByteArray(content,0,content.size)) {"Platform failed to decode actual ${image.kind} bytes"}
                assertTrue(bitmap.width>0 && bitmap.height>0)
                try {assertPhotoAspectImage(bitmap,relation,expectedAspect,image.kind,d.rawSize?.width,d.rawSize?.height)} finally {bitmap.recycle()}
                android.util.Log.i("PhotoFormatProbe","format=$format bundle=${publication.id} sensor=${publication.sensorTimestampNs} kind=${image.kind} bytes=${image.bytes} sha256=${image.sha256} published=true decoded=true metadataMatched=true quality=73")
            }
            if(geotag) {
                val location=metadata.getValue("captureLocation").jsonObject
                assertEquals("AVAILABLE",location.getValue("status").jsonPrimitive.content)
                assertEquals(admittedLocation!!.fix!!.latitude,location.getValue("latitude").jsonPrimitive.double,0.0)
                assertEquals(admittedLocation!!.fix!!.longitude,location.getValue("longitude").jsonPrimitive.double,0.0)
                assertEquals("PRECISE",location.getValue("permissionPrecision").jsonPrimitive.content)
                assertFalse(repository.states.value.geotaggingEnabled)
                android.util.Log.i("E16GeotagProbe","actualPhotoService=true frozenBeforeWriter=true optedOutBeforePublication=true locationPreservedForAdmittedCapture=true futureLocationDisabled=true relatedMetadata=true")
            }
            if(reviewPhoto) reviewPublishedPhoto(context,publication) { reviewSelection.value=it }
            if(destroy) android.util.Log.i("PhotoFormatProbe","destroyedDuringPublication=true acceptedWriterDrained=true staleStateSuppressed=true")
            if(holdWriter && !destroy) {
                compose.waitUntil(15_000) {!owner.cameraStates.value.settingsPending && owner.cameraStates.value.phase==CameraUiPhase.PREVIEWING}
                assertEquals(81,owner.cameraStates.value.effectiveSettings?.photoQuality)
                assertEquals(PhotoAspectSelection(true,9,16),owner.cameraStates.value.effectiveSettings?.photoAspect)
                assertFalse(owner.cameraStates.value.captureControlsLocked)
                android.util.Log.i("PhotoFormatProbe","detachedDuringPublication=true duplicateRejected=true pendingQualityAppliedAfterPublication=81 previewRestored=true")
            }
        } finally {
            release.countDown()
            locationProvider?.close()
            compose.runOnUiThread {if(!destroy) binder.get()?.detachPreview();repository.set(before)}
            if(storageWriter.get()?.isShutdown==true) {
                // onDestroy already retired the executor; do not enqueue a swallowed task.
                cameraEngine.get()?.closeAsync()?.get(20,TimeUnit.SECONDS)
            } else engineExecutor.get()?.let { executor ->
                val retired=CountDownLatch(1);executor.execute {retired.countDown()}
                assertTrue(retired.await(15,TimeUnit.SECONDS))
            }
            storageWriter.get()?.let { writer ->
                if(writer.isShutdown) assertTrue(writer.awaitTermination(25,TimeUnit.SECONDS))
                else {val drained=CountDownLatch(1);writer.execute {drained.countDown()};assertTrue(drained.await(25,TimeUnit.SECONDS))}
            }
            compose.runOnUiThread {}
            if(bound) context.unbindService(connection)
            (rows()-initial).forEach {context.contentResolver.delete(it,null,null)}
        }
    }

    /** Platform-rendered DNG preview, not a claim of full RAW raster or calibrated demosaicing. */
    private fun reviewPublishedPhoto(context:Context,publication:StillPublication,show:(MediaReviewSelection?)->Unit) {
        val media=LocalMediaRepository(context)
        val name=publication.images.first().displayName
        fun lookup():LocalMediaTake {
            var cursor:LocalMediaCursor?=null
            var selected:LocalMediaTake?=null
            var pages=0
            do {
                check(++pages<=100)
                val page=media.page(GallerySettings(),name,cursor,60)
                selected=page.takes.singleOrNull { take -> take.originals.any { it.uri==publication.images.first().uri } }
                cursor=page.next
            } while(selected==null && cursor!=null)
            return requireNotNull(selected) { "Actual DNG publication missing from catalog" }
        }
        val take=lookup()
        assertEquals(LocalMediaRelationStatus.DECLARED,take.relationStatus)
        assertEquals(publication.images.map {it.uri}.toSet(),take.originals.map {it.uri}.toSet())
        assertEquals(setOf(publication.metadataUri),take.metadata.map {it.uri}.toSet())
        val members=(listOf(take.primary)+take.originals).distinctBy {it.uri}
        fun bytes(a:LocalMediaArtifact)=requireNotNull(context.contentResolver.openInputStream(Uri.parse(a.uri))).use {it.readBytes()}
        val originals=(take.originals+take.metadata).associate {it.uri to bytes(it)}
        val settings=SettingsRepositories.get(context).states.value
        fun readers()=Thread.getAllStackTraces().keys.filter {it.name=="media-review-reader" && it.isAlive}.toSet()
        val beforeReaders=readers()
        fun node(tag:String)=compose.onNodeWithTag("media-playback-$tag",useUnmergedTree=true)
        try {
            compose.runOnUiThread {show(MediaReviewSelection(listOf(take),take.primary,GallerySettings(),name,null))}
            fun verify(index:Int) {
                val artifact=members[index]
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithTag("media-playback-error-detail",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() ||
                        compose.onAllNodesWithTag("media-playback-frame",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
                }
                node("error-detail").assertDoesNotExist()
                // A single file is named in the take details, where the DNG note is too; several get the switcher.
                compose.openPlaybackDetails()
                node("member").performScrollTo().assertTextEquals(context.getString(R.string.media_playback_member,index+1,members.size,artifact.name))
                for(tag in listOf("play-pause","exact","next-frame","previous-frame","native-frame-mode")) node(tag).assertDoesNotExist()
                val content=originals.getValue(artifact.uri)
                val decoded=requireNotNull(BitmapFactory.decodeByteArray(content,0,content.size))
                val exifType=Class.forName("androidx.exifinterface.media.ExifInterface")
                val exif=exifType.getConstructor(java.io.InputStream::class.java).newInstance(java.io.ByteArrayInputStream(content))
                val orientation=exifType.getMethod("getAttributeInt",String::class.java,java.lang.Integer.TYPE).invoke(exif,"Orientation",1) as Int
                val rotation=mapOf(1 to 0,6 to 90,3 to 180,8 to 270).getValue(orientation)
                val oriented=android.graphics.Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,
                    android.graphics.Matrix().apply {postRotate(rotation.toFloat())},true)
                if(artifact.mimeType=="image/x-adobe-dng") node("dng-preview").performScrollTo().assertTextEquals(
                    context.getString(R.string.media_playback_dng_preview,oriented.width,oriented.height))
                else node("dng-preview").assertDoesNotExist()
                compose.closePlaybackDetails()
                var screenshot:android.graphics.Bitmap?=null
                var reference:android.graphics.Bitmap?=null
                try {
                    node("frame").performScrollTo().assertIsDisplayed()
                    screenshot=node("frame").captureToImage().asAndroidBitmap()
                    reference=android.graphics.Bitmap.createBitmap(screenshot.width,screenshot.height,android.graphics.Bitmap.Config.ARGB_8888)
                    val scale=minOf(screenshot.width.toFloat()/oriented.width,screenshot.height.toFloat()/oriented.height)
                    val width=oriented.width*scale;val height=oriented.height*scale
                    val left=(screenshot.width-width)/2;val top=(screenshot.height-height)/2
                    android.graphics.Canvas(reference).drawBitmap(oriented,null,android.graphics.RectF(left,top,left+width,top+height),
                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    var errorSum=0L;var maxError=0;var count=0
                    for(y in 1..11) for(x in 1..15) {
                        val px=(left+width*x/16).toInt().coerceIn(0,screenshot.width-1)
                        val py=(top+height*y/12).toInt().coerceIn(0,screenshot.height-1)
                        val actual=screenshot.getPixel(px,py);val expected=reference.getPixel(px,py)
                        for(shift in listOf(16,8,0)) {
                            val delta=kotlin.math.abs(((actual shr shift) and 255)-((expected shr shift) and 255))
                            errorSum+=delta;maxError=maxOf(maxError,delta);count++
                        }
                    }
                    val mean=errorSum.toDouble()/count
                    assertTrue("Displayed photo differs from independently oriented platform preview: mean=$mean max=$maxError",mean<=8 && maxError<=32)
                    android.util.Log.i("E16PhotoReviewProbe","kind=${artifact.mimeType} orientation=$orientation preview=${oriented.width}x${oriented.height} displayedMeanError=$mean displayedMaxError=$maxError actualPublishedUri=true readonlyPreview=true rawRasterFidelityClaim=false")
                } finally {
                    screenshot?.recycle();reference?.recycle();if(oriented!==decoded) oriented.recycle();decoded.recycle()
                }
                assertTrue((readers()-beforeReaders).isNotEmpty())
            }
            verify(0)
            for(index in 1 until members.size) {node("next-member").performScrollTo().performClick();verify(index)}
            for(index in members.lastIndex-1 downTo 0) {node("previous-member").performScrollTo().performClick();verify(index)}
            node("close").performClick();node("dialog").assertDoesNotExist()
            compose.waitUntil(30_000) {(readers()-beforeReaders).isEmpty()}
            for(artifact in take.originals+take.metadata) assertArrayEquals(originals.getValue(artifact.uri),bytes(artifact))
            assertEquals(settings,SettingsRepositories.get(context).states.value)
            assertEquals(take,lookup())
            android.util.Log.i("E16PhotoReviewProbe","serviceCapture=true catalogRelated=true members=${members.size} displayedCorrespondingPhoto=true originalsAndJsonUnchanged=true settingsUnchanged=true readersRetired=true")
        } finally {
            compose.runOnUiThread {show(null)}
            compose.waitUntil(30_000) {(readers()-beforeReaders).isEmpty()}
        }
    }

}
