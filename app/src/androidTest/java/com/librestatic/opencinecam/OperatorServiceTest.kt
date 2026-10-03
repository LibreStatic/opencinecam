/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.*
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.transfers.*
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Rule
import org.junit.Test

/** Real locked silent VIDEO take; software REC state, deferred intent and decoder acceptance. */
class OperatorServiceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun videoTakeLocksOrReportsMissingHardwareWithoutCreatingAClip() = exerciseTake(CaptureMode.VIDEO)
    @Test fun timelapseTakeDefersCameraIntentAllowsMonitoringAndStopsWithAReadableClip() = exerciseTake(CaptureMode.TIME_LAPSE)
    @Test fun timelapseFrameLimitStopsAfterExactlyThreeEncodedImages() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3)
    @Test fun project23976HasRationalSampleTimes() = exerciseTake(CaptureMode.TIME_LAPSE, 12, CaptureFrameRate(24000, 1001))
    @Test fun project2997HasRationalSampleTimes() = exerciseTake(CaptureMode.TIME_LAPSE, 12, CaptureFrameRate(30000, 1001))
    @Test fun project5994HasRationalSampleTimes() = exerciseTake(CaptureMode.TIME_LAPSE, 12, CaptureFrameRate(60000, 1001))
    @Test fun durationLimitStopsFromWallTimeWithAReadableMovie() = exerciseTake(CaptureMode.TIME_LAPSE, wallLimitMs = 1500)
    @Test fun offSpeedVideoRetainsItsHardwareGateWithoutStartingAudio() = exerciseTake(CaptureMode.VIDEO, offSpeed = true)
    @Test fun multiplePausesKeepPreviewLiveAndOneFractionalProjectClip() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 12, rate = CaptureFrameRate(30000,1001), pauseCycles = 2)
    @Test fun durationLimitExcludesAnAcknowledgedPauseLongerThanTheLimit() = exerciseTake(CaptureMode.TIME_LAPSE, wallLimitMs = 1500, pauseCycles = 1)
    @Test fun stopWhilePausedFinalizesTheSameClipWithoutAResumeImage() = exerciseTake(CaptureMode.TIME_LAPSE, pauseCycles = 1, stopWhilePaused = true)
    @Test fun recordRunUsesEncodedFramesAndContinuesAcrossServiceTakes() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, timecodeMode = TimecodeMode.RECORD_RUN)
    @Test fun regenUsesLastPublishedTakeAcrossServiceTakes() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, timecodeMode = TimecodeMode.REGEN)
    @Test fun recordRunContinuesAfterServiceRecreation() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, timecodeMode = TimecodeMode.RECORD_RUN, recreateOwner = true)
    @Test fun regenContinuesAfterServiceRecreation() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, timecodeMode = TimecodeMode.REGEN, recreateOwner = true)
    @Test fun recordRunContinuesAfterServiceRecreationOnTheSameSurface() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, timecodeMode = TimecodeMode.RECORD_RUN, recreateOwner = true, reuseSurface = true)
    @Test fun regenContinuesAfterServiceRecreationOnTheSameSurface() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, timecodeMode = TimecodeMode.REGEN, recreateOwner = true, reuseSurface = true)
    @Test fun journalStorageFailureStillSavesBothActualServiceTakes() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, journalFailure = true)
    @Test fun webDavDestinationChangeDuringRecKeepsFirstTakeBoundAndUsesNewDestinationNext() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, queueMode = "change")
    @Test fun webDavDisableDuringRecKeepsAdmittedTakeAndDoesNotEnrollNext() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, queueMode = "disable")
    @Test fun recordingWaitsForTransferLocalIoRetirementBeforeCreatingItsRealMovie() =
        exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, transferCancel = false)
    @Test fun cancellingRecordingWhileTransferRetiresNeverResurrectsCapture() =
        exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, transferCancel = true)
    @Test fun serviceCreatedTimelapsePublishesAndUiUploadsEveryArtifactOverHttps() =
        exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, serviceHttps = true)
    @Test fun serviceCreatedTimelapseUploadsToSelfSignedLanWithEndpointOptIn() =
        exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, serviceHttps = true, lanOptIn = true)
    @Test fun timelapseSuppressesExplicitAacIntentWithoutAudioArtifacts() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, audioPreference = AudioOutputFormat.AAC_MP4)
    @Test fun timelapseSuppressesExplicitWavIntentWithoutAudioArtifacts() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, audioPreference = AudioOutputFormat.WAV_PCM)
    @Test fun timelapseSuppressesExplicitFlacIntentWithoutAudioArtifacts() = exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, audioPreference = AudioOutputFormat.FLAC)
    @Test fun videoAacIntentPublishesAudioOrReportsMissingHardware() = exerciseTake(CaptureMode.VIDEO, audioPreference = AudioOutputFormat.AAC_MP4)
    @Test fun videoWavIntentPublishesAudioOrReportsMissingHardware() = exerciseTake(CaptureMode.VIDEO, audioPreference = AudioOutputFormat.WAV_PCM)
    @Test fun videoFlacIntentPublishesAudioOrReportsMissingHardware() = exerciseTake(CaptureMode.VIDEO, audioPreference = AudioOutputFormat.FLAC)
    @Test fun productionSlateTimelapseCommitsOriginalIntentAndAdvancesNextTake() = exerciseTake(
        CaptureMode.TIME_LAPSE, frameLimit = 3,
        productionSlate = ProductionSlateSettings(project = "Shoot Ñ", scene = "5A", takeNumber = 8, goodTake = true, autoIncrementTake = true))
    @Test fun productionSlateStaysFrozenAcrossTransferRetirementWithoutOverwritingNextEdit() = exerciseTake(
        CaptureMode.TIME_LAPSE, frameLimit = 3, transferCancel = false,
        productionSlate = ProductionSlateSettings(project = "Shoot Ñ", scene = "5A", takeNumber = 8, autoIncrementTake = true))
    @Test fun configuredTimelapseNamesStayFrozenAcrossTransferRetirementAndNextEdits() = exerciseTake(
        CaptureMode.TIME_LAPSE,frameLimit=3,transferCancel=false,
        productionSlate=ProductionSlateSettings(project="Admitted",scene="S1",takeNumber=8,autoIncrementTake=true),
        captureNaming=CaptureNamingSettings(true,"{project}_{scene}_T{take}"))
    @Test fun timelapsePublishedMediaStoreTakeOpensExactInternalReviewAndReturnsToCapture() =
        exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, reviewAfterCapture = true)

    @Test fun timelapsePublishedTakeRequiresExplicitColorInterpretationAndResetsOnReopen() =
        exerciseTake(CaptureMode.TIME_LAPSE, frameLimit = 3, reviewAfterCapture = true, interpretReviewColor = true)

    @Test fun timelapseLocationIsFrozenBeforePreparationAndOptOutDoesNotRewritePublishedMetadata() =
        exerciseTake(CaptureMode.TIME_LAPSE,frameLimit=3,geotagCapture=true)

    @Test fun gpuPreviewReattachmentKeepsConfiguredCameraAndAdmitsTheNextTake() =
        exerciseTake(CaptureMode.TIME_LAPSE,frameLimit=3,reattachGpuPreview=true)

    private fun verifyGpuReattachment(owner: CaptureService.LocalBinder) {
        fun field(target:Any,name:String):Any?=target.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(target)
        val service=requireNotNull(field(owner,"this\$0"))
        val engine=requireNotNull(CaptureService::class.java.getDeclaredMethod("getPreviewEngine").apply {isAccessible=true}.invoke(service))
        val executor=field(engine,"cameraExecutor") as java.util.concurrent.Executor
        val pipeline=requireNotNull(field(engine,"logPipeline"))
        val gpuHandler=field(pipeline,"handler") as android.os.Handler
        val originalSession=requireNotNull(field(engine,"session"))
        val originalGeneration=field(engine,"generation")
        val surface=field(service,"attachedPreviewSurface") as android.view.Surface
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val drained=java.util.concurrent.CountDownLatch(1)
        executor.execute {entered.countDown();check(release.await(20,java.util.concurrent.TimeUnit.SECONDS))}
        assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS))
        try {
            for(rotation in listOf(0,180,0)) {
                compose.runOnUiThread {
                    assertEquals(CameraUiPhase.PREVIEWING,owner.cameraStates.value.phase)
                    val actionGeneration=owner.cameraStates.value.captureActionGeneration
                    assertTrue(owner.attachPreview(surface,rotation))
                    android.util.Log.i("E16ReattachProbe","rotation=$rotation phaseAfterAttach=${owner.cameraStates.value.phase} " +
                        "sameSession=${field(engine,"session")===originalSession} cameraGenerationBefore=$originalGeneration cameraGenerationAfter=${field(engine,"generation")}")
                    assertEquals("An output-only GPU reattachment must not reopen the configured camera",CameraUiPhase.PREVIEWING,owner.cameraStates.value.phase)
                    assertEquals(actionGeneration,owner.cameraStates.value.captureActionGeneration)
                    assertSame(originalSession,field(engine,"session"));assertEquals(originalGeneration,field(engine,"generation"))
                }
                val outputReady=java.util.concurrent.CountDownLatch(1)
                assertTrue(gpuHandler.post {outputReady.countDown()})
                assertTrue(outputReady.await(5,java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(rotation,field(pipeline,"previewDisplayRotationDegrees"))
                assertNotEquals(android.opengl.EGL14.EGL_NO_SURFACE,field(pipeline,"previewEglSurface"))
                assertSame(surface,field(engine,"previewSurface"))
            }
        } finally {
            release.countDown();executor.execute {drained.countDown()}
            assertTrue(drained.await(15,java.util.concurrent.TimeUnit.SECONDS))
        }
        assertSame(originalSession,field(engine,"session"));assertEquals(originalGeneration,field(engine,"generation"))
        assertEquals(CameraUiPhase.PREVIEWING,owner.cameraStates.value.phase)
        android.util.Log.i("E16ReattachProbe","heldCameraExecutor=true rotations=0,180,0 sameCameraSession=true sameCameraGeneration=true liveEglOutputs=true nextCaptureUsesOriginalAdmission=true")
    }

    private fun exerciseTake(mode: CaptureMode, frameLimit: Int? = null, rate: CaptureFrameRate = CaptureFrameRate(30), wallLimitMs: Long? = null, offSpeed: Boolean = false, pauseCycles: Int = 0, stopWhilePaused: Boolean = false, timecodeMode: TimecodeMode? = null, recreateOwner: Boolean = false, reuseSurface: Boolean = false, journalFailure: Boolean = false, queueMode: String? = null, transferCancel: Boolean? = null, serviceHttps: Boolean = false, lanOptIn: Boolean = false, audioPreference: AudioOutputFormat? = null, productionSlate: ProductionSlateSettings? = null,captureNaming:CaptureNamingSettings?=null, reviewAfterCapture: Boolean = false, interpretReviewColor: Boolean = false, geotagCapture: Boolean = false, reattachGpuPreview: Boolean = false) {
        val manualStop = frameLimit == null && wallLimitMs == null && !stopWhilePaused
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (audioPreference != null) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val repository = SettingsRepositories.get(context)
        val before = repository.states.value
        var locationProvider:GeotagTestProvider?=null
        var admittedLocation:CaptureLocationSnapshot?=null
        val queueSettings = WebDavQueueSettings.get(context)
        val previousQueue = queueSettings.states.value.preferences
        val transferEntered = java.util.concurrent.CountDownLatch(1)
        val transferRelease = java.util.concurrent.CountDownLatch(1)
        val transferId = java.util.UUID.randomUUID().toString()
        val transferSettingsFile = java.io.File(context.cacheDir, "e1-settings-$transferId.json")
        val transferFixture = transferCancel?.let {
            WebDavTransferRuntime(context, "e1-$transferId.db", settingsFactory = {
                transferEntered.countDown()
                check(transferRelease.await(30, java.util.concurrent.TimeUnit.SECONDS))
                WebDavQueueSettings(transferSettingsFile)
            }, networkSource = object : WebDavRuntimeNetworkSource {
                override fun start(changed: (android.net.Network?, WebDavNetwork) -> Unit) = changed(null, WebDavNetwork.OFFLINE)
                override fun close() = Unit
            })
        }
        if (serviceHttps) assumeHostHttpsFixture()
        val httpsFixture = if (serviceHttps) E1ServiceHttpsFixture(context, lanOptIn) else null
        var httpsPrepared = false
        var firstQueueEndpoint: String? = null
        var nextQueueEndpoint: String? = null
        var admittedBundleId: String? = null
        fun enrollmentIds(): Set<String> = java.io.File(context.noBackupFilesDir, "capture-transfer-enrollment")
            .listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.toSet()
        val previousEnrollmentIds = enrollmentIds()
        fun verifyQueue(receipt: CapturePublicationReceipt, first: Boolean, message: String) {
            if (queueMode == null) return
            val enrollment = CaptureTransferEnrollmentFile(context).load(receipt.bundleId)
            WebDavSqliteOutbox(context).use { store ->
                val bundle = store.load(receipt.bundleId)
                if (!first && queueMode == "disable") {
                    assertNull(enrollment); assertNull(bundle)
                } else {
                    assertNotNull(enrollment); assertNotNull(bundle)
                    assertTrue(message, message.contains(context.getString(R.string.webdav_queue_registered)))
                    assertFalse(message, message.contains(context.getString(R.string.webdav_queue_registration_failed)))
                    assertFalse(message, message.contains(context.getString(R.string.capture_publication_registration_failed)))
                    assertEquals(if (first) firstQueueEndpoint else nextQueueEndpoint, enrollment!!.endpointId)
                    if (first) assertEquals(admittedBundleId, receipt.bundleId)
                    assertEquals(enrollment.endpointId, bundle!!.endpointId)
                    assertTrue(bundle.sealed); assertEquals(receipt.artifacts.size, bundle.artifacts.size)
                    assertEquals(receipt.artifacts.map { it.uri }.toSet(), bundle.artifacts.map { it.spec.sourceUri }.toSet())
                    assertTrue(bundle.artifacts.all { it.spec.sizeBytes > 0 })
                }
                java.io.File(context.cacheDir, "webdav-service-$queueMode-${if (first) "first" else "second"}.txt")
                    .writeText("bundleId=${receipt.bundleId}\nenrolled=${enrollment != null}\nsealed=${bundle?.sealed}\nendpoint=${bundle?.endpointId}\nartifacts=${receipt.artifacts.size}\n")
                requireNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(receipt.artifacts.first { it.role.name == "VIDEO" }.uri))).use { input ->
                    java.io.File(context.cacheDir, "webdav-service-$queueMode-${if (first) "first" else "second"}.mp4").outputStream().use { input.copyTo(it) }
                }
            }
        }
        val binder = AtomicReference<CaptureService.LocalBinder?>()
        val surface = AtomicReference<android.view.Surface?>()
        val surfacesCreated = java.util.concurrent.atomic.AtomicInteger()
        val surfacesDestroyed = java.util.concurrent.atomic.AtomicInteger()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder.set(service as CaptureService.LocalBinder) }
            override fun onServiceDisconnected(name: ComponentName?) { binder.set(null) }
        }
        fun ownedSidecars(): Set<Long> {
            val result = mutableSetOf<Long>()
            requireNotNull(context.contentResolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Downloads._ID), "${android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                arrayOf(context.packageName),null)).use { while (it.moveToNext()) result += it.getLong(0) }
            return result
        }
        fun verifyPublicationReceipt(uri: android.net.Uri): com.librestatic.opencinecam.transfers.CapturePublicationReceipt {
            val receipts = mutableListOf<com.librestatic.opencinecam.transfers.CapturePublicationReceipt>()
            var after: String? = null
            while (true) {
                val page = com.librestatic.opencinecam.transfers.CapturePublicationJournal.list(context, limit = 100, afterBundleId = after)
                receipts.addAll(page)
                if (page.isEmpty()) break
                after = page.last().bundleId
            }
            val receipt = receipts.single { r -> r.artifacts.any {
                it.role == com.librestatic.opencinecam.storage.CaptureArtifactRole.VIDEO && it.uri == uri.toString()
            } }
            assertEquals(com.librestatic.opencinecam.transfers.CapturePublicationState.COMMITTED, receipt.state)
            assertEquals(receipt, com.librestatic.opencinecam.transfers.CapturePublicationJournal.load(context, receipt.bundleId))
            for (artifact in receipt.artifacts) {
                val row = android.net.Uri.parse(artifact.uri)
                requireNotNull(context.contentResolver.query(row,
                    arrayOf(android.provider.MediaStore.MediaColumns.IS_PENDING, android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(artifact.displayName, it.getString(1))
                }
                requireNotNull(context.contentResolver.openInputStream(row)).use { assertTrue("Recorded artifact must have bytes", it.read() >= 0) }
            }
            android.util.Log.i("CaptureBundleProbe", "serviceJournalCommitted=true completeArtifactCount=${receipt.artifacts.size} allRowsPublished=true bundle=${receipt.bundleId}")
            return receipt
        }
        fun verifyTimecode(uri: android.net.Uri, first: String, last: String, suffix: String): Long {
            val name = requireNotNull(context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Video.Media.DISPLAY_NAME), null, null, null)).use { it.moveToFirst(); it.getString(0) }
            val downloads = android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val sidecar = requireNotNull(context.contentResolver.query(downloads, arrayOf(android.provider.MediaStore.Downloads._ID, android.provider.MediaStore.Downloads.IS_PENDING),
                "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?", arrayOf(name.removeSuffix(".mp4") + ".timing.json", context.packageName), null)).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(1)); android.content.ContentUris.withAppendedId(downloads, it.getLong(0))
            }
            val text = requireNotNull(context.contentResolver.openInputStream(sidecar)).bufferedReader().use { it.readText() }
            val tc = org.json.JSONObject(text).getJSONObject("timecode")
            assertTrue(tc.getBoolean("enabled")); assertEquals(timecodeMode!!.name, tc.getString("mode"))
            assertEquals(3L, tc.getLong("encodedFrames")); assertEquals(0L, tc.getLong("firstPtsUs")); assertEquals(66666L, tc.getLong("lastPtsUs"))
            assertEquals(first, tc.getString("firstFrameTimecode")); assertEquals(last, tc.getString("lastFrameTimecode"))
            assertEquals("CONTIGUOUS_MUXED_VIDEO_SAMPLE_INDEX", tc.getString("frameMapping"))
            assertEquals(30000, tc.getInt("rateNumerator")); assertEquals(1001, tc.getInt("rateDenominator"))
            assertFalse(tc.getBoolean("containerTimecodeTrackWritten")); assertFalse(tc.getBoolean("sourceClockSynchronizationVerified"))
            val publication = verifyPublicationReceipt(uri)
            assertEquals(setOf(uri.toString(), sidecar.toString()), publication.artifacts.map { it.uri }.toSet())
            java.io.File(context.noBackupFilesDir, "capture-publication/${publication.bundleId}.json")
                .copyTo(java.io.File(context.cacheDir, "timecode-${timecodeMode.name}-$suffix.publication.json"), overwrite = true)
            java.io.File(context.cacheDir, "timecode-${timecodeMode.name}-$suffix.json").writeText(text)
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> java.io.File(context.cacheDir, "timecode-${timecodeMode.name}-$suffix.mp4").outputStream().use { input.copyTo(it) } }
            android.util.Log.i("TimecodeTakeProbe", "mode=$timecodeMode take=${tc.getLong("takeId")} frames=3 first=$first last=$last pts=0,33333,66666 sidecarPublished=true")
            return tc.getLong("takeId")
        }
        if (recreateOwner) com.librestatic.opencinecam.TimecodeContinuationFile(java.io.File(context.filesDir, "timecode-continuation.json")).clear()
        fun ownedAudioRows(): Set<Long> = requireNotNull(context.contentResolver.query(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Audio.Media._ID),
            "${android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?", arrayOf(context.packageName), null,
        )).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        val beforeAudioRows = if (audioPreference != null) ownedAudioRows() else emptySet()
        val beforeSidecars = ownedSidecars()
        var bound = false
        val journalDirectory = java.io.File(context.noBackupFilesDir, "capture-publication")
        val preservedJournal = java.io.File(context.noBackupFilesDir, "capture-publication-test-${java.util.UUID.randomUUID()}")
        var journalBlocked = false
        try {
            httpsFixture?.let { it.prepare(); httpsPrepared = true }
            if (queueMode != null) firstQueueEndpoint = queueSettings.save("https://first.example.invalid/takes/", true, false).activeEndpointId
            if (journalFailure) {
                if (journalDirectory.exists()) check(journalDirectory.renameTo(preservedJournal))
                journalBlocked = true
                journalDirectory.writeText("Injected non-directory storage failure")
            }
            compose.runOnUiThread { repository.set(CameraSettings(captureNaming=captureNaming ?: CaptureNamingSettings(),productionSlate = productionSlate ?: ProductionSlateSettings(), timecodeRememberPosition = recreateOwner, timecodeResetRevision = if (recreateOwner) 1 else 0, timecodeEnabled = timecodeMode != null, timecodeMode = timecodeMode ?: TimecodeMode.RECORD_RUN,
                timecodeNominalFps = 30, timecodeDropFrame = true, timecodeStartHours = 0, timecodeStartMinutes = 0, timecodeStartSeconds = 59, timecodeStartFrames = 29, audioEnabled = audioPreference != null, audioOutputFormat = audioPreference ?: AudioOutputFormat.AAC_MP4, audioBitDepth = if (audioPreference in setOf(AudioOutputFormat.FLAC, AudioOutputFormat.WAV_PCM)) AudioBitDepth.PCM_16 else CameraSettings().audioBitDepth, videoWidth = 640, videoHeight = 480, timelapseWidth = 640, timelapseHeight = 480, timelapseFps = rate.numerator, timelapseFpsDenominator = rate.denominator, videoOffSpeed = offSpeed,
                timelapseIntervalMs = if (rate.denominator != 1 || wallLimitMs != null) 100 else 500,
                timelapseDurationMs = wallLimitMs ?: 3600000,
                timelapseLimitMode = when { wallLimitMs != null -> TimeLapseLimitMode.DURATION; frameLimit != null -> TimeLapseLimitMode.FRAME_COUNT; else -> TimeLapseLimitMode.UNLIMITED }, timelapseFrameCount = frameLimit ?: 300, operation = OperatorPreferences(startupMode = StartupMode.VIDEO, lockDuringTake = true))) }
            bound = context.bindService(Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            compose.waitUntil(10_000) { binder.get() != null }
            var owner = requireNotNull(binder.get())
            httpsFixture?.inject(owner)
            if (transferFixture != null) {
                val outer = owner.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(owner)
                CaptureService::class.java.getDeclaredField("transferRuntime").apply { isAccessible = true }.set(outer, transferFixture)
                transferFixture.refresh()
                assertTrue(transferEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            compose.runOnUiThread { owner.prepare(640, 480); owner.selectMode(mode, reopen = false) }
            val d = requireNotNull(owner.cameraStates.value.descriptor)
            val surfaceGeneration = androidx.compose.runtime.mutableIntStateOf(0)
            val showTransfers = androidx.compose.runtime.mutableStateOf(false)
            val showCameraRoot = androidx.compose.runtime.mutableStateOf(false)
            compose.setContent { if (showCameraRoot.value) androidx.compose.material3.MaterialTheme { CameraRootScreen() }
                else if (showTransfers.value) requireNotNull(httpsFixture).Panel() else androidx.compose.runtime.key(surfaceGeneration.intValue) { AndroidView(factory = { host -> SurfaceView(host).apply {
                holder.setFixedSize(d.previewSize.width, d.previewSize.height)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { surfacesCreated.incrementAndGet(); surface.set(holder.surface) }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surface.set(holder.surface) }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { surfacesDestroyed.incrementAndGet(); surface.set(null) }
                })
            } }, modifier = Modifier.fillMaxSize()) } }
            compose.waitUntil(10_000) { surface.get()?.isValid == true }
            compose.runOnUiThread { assertTrue(owner.attachPreview(requireNotNull(surface.get()), 0)) }
            compose.waitUntil(15_000) { owner.cameraStates.value.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.ERROR) }
            assertEquals(owner.cameraStates.value.message, CameraUiPhase.PREVIEWING, owner.cameraStates.value.phase)
            assertEquals(mode, owner.cameraStates.value.selectedMode)
            assertFalse(owner.setRecordingPaused(true))
            val originalFocus = owner.cameraStates.value.requestedFocusDiopters
            val originalZoom = owner.cameraStates.value.zoomRatio
            val oldExposure = repository.states.value.exposure
            fun ownedVideos(): Set<Long> {
                val result = mutableSetOf<Long>()
                requireNotNull(context.contentResolver.query(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.Video.Media._ID), "${android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?", arrayOf(context.packageName), null)).use { cursor ->
                    while (cursor.moveToNext()) result.add(cursor.getLong(0))
                }
                return result
            }
            val beforeVideos = ownedVideos()
            val hardwareAvc = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.filter {
                it.isEncoder && !it.isAlias && it.isHardwareAccelerated && it.supportedTypes.any { type -> type.equals("video/avc", true) }
            }
            if(geotagCapture) {
                // Granting location here would leak into the cases that require it revoked.
                org.junit.Assume.assumeTrue("Grant location before running: adb shell pm grant ${context.packageName} " +
                    "android.permission.ACCESS_FINE_LOCATION", locationPermission(context)!=null)
                val provider=GeotagTestProvider(context);locationProvider=provider
                compose.runOnUiThread {repository.update {it.copy(geotaggingEnabled=true)}}
                provider.foreground(true);provider.publish()
                admittedLocation=provider.waitFor {it?.status==CaptureLocationStatus.AVAILABLE}
            }
            var accepted = false
            compose.runOnUiThread { accepted = owner.captureForRole(CaptureActionTicket(false, owner.cameraStates.value.captureActionGeneration), audioForThisTake = audioPreference != null || offSpeed) }
            if(geotagCapture) {
                assertTrue(accepted)
                compose.runOnUiThread {repository.update {it.copy(geotaggingEnabled=false)};locationProvider!!.runtime.refreshAccess()}
                assertNull(locationProvider!!.runtime.snapshot())
            }
            if (transferFixture != null) {
                assertTrue(accepted)
                assertEquals(CameraUiPhase.CAPTURING, owner.cameraStates.value.phase)
                assertEquals(beforeVideos, ownedVideos())
                assertNull(owner.cameraStates.value.lastSavedUri)
                assertEquals(context.getString(R.string.webdav_transfer_preparing_capture), owner.cameraStates.value.message)
                var heartbeat = false
                compose.runOnUiThread { heartbeat = true }
                assertTrue(heartbeat)
                assertTrue(transferFixture.states.value.busy)
                if (transferCancel == true) {
                    compose.runOnUiThread { assertTrue(owner.stopRecording()) }
                    assertEquals(CameraUiPhase.PREVIEWING, owner.cameraStates.value.phase)
                }
                if (productionSlate != null && transferCancel == false) compose.runOnUiThread {
                    repository.update { it.copy(productionSlate = productionSlate.copy(scene = "NEXT", takeNumber = 41),captureNaming=if(captureNaming!=null) CaptureNamingSettings(true,"NEXT") else it.captureNaming) }
                }
                transferRelease.countDown()
                compose.waitUntil(10_000) { !transferFixture.states.value.busy }
                if (transferCancel == true) {
                    // A delivered continuation, not merely an idle main queue before IO resumes.
                    val outer = owner.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(owner)
                    val preparation = CaptureService::class.java.getDeclaredField("transferPreparationJob")
                        .apply { isAccessible = true }.get(outer) as kotlinx.coroutines.Job
                    kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { preparation.join() } }
                    instrumentation.waitForIdleSync()
                    compose.runOnUiThread {
                        assertEquals(CameraUiPhase.PREVIEWING, owner.cameraStates.value.phase)
                        assertNull(owner.cameraStates.value.lastSavedUri)
                    }
                    assertEquals(beforeVideos, ownedVideos())
                    assertNotEquals(WebDavTransferMessage.WAITING_RECORDING, transferFixture.states.value.message)
                    return
                }
            }
            compose.waitUntil(20_000) { owner.cameraStates.value.phase in setOf(CameraUiPhase.RECORDING, CameraUiPhase.ERROR) }
            if (mode == CaptureMode.VIDEO && hardwareAvc.isEmpty()) {
                val rejected = owner.cameraStates.value
                assertEquals(CameraUiPhase.ERROR, rejected.phase)
                assertTrue(rejected.message.orEmpty(), rejected.message.orEmpty().contains("No hardware video/avc Surface encoder accepts"))
                assertNull(rejected.lastSavedUri)
                assertFalse(rejected.recordingFinalizing)
                if (offSpeed) assertEquals(CaptureFrameRate(30), rejected.recordingProjectRate)
                assertEquals(beforeVideos, ownedVideos())
                if (audioPreference != null) {
                    assertEquals(beforeAudioRows, ownedAudioRows())
                    assertEquals(beforeSidecars, ownedSidecars())
                    compose.runOnUiThread {
                        val outer = owner.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(owner)
                        assertNull(CaptureService::class.java.getDeclaredField("audioSidecarRecorder").apply { isAccessible = true }.get(outer))
                        assertFalse(owner.cameraStates.value.audioMonitoringActive)
                    }
                    android.util.Log.i("ServiceAudioMatrixProbe", "mode=VIDEO requested=$audioPreference outcome=HARDWARE_REQUIRED positiveCellAccepted=false createdVideo=0 createdAudio=0 createdMetadata=0")
                }
                android.util.Log.i("OperatorTakeProbe", "mode=VIDEO hardwareAvcEncoders=0 offSpeed=$offSpeed cleanRejection=true createdClips=0 REC_NOT_RUN error=${rejected.errorCode} message=${rejected.message}")
                // A rejected dispatched start must release its exact service admission: after
                // recovery the operator's next take is admitted instead of silently ignored.
                val outer = owner.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(owner)
                val admission = CaptureService::class.java.getDeclaredField("transferCapture").apply { isAccessible = true }
                fun admissionHeld() = (admission.get(outer) as AtomicReference<*>).get() != null
                compose.waitUntil(10_000) { !admissionHeld() }
                compose.runOnUiThread { owner.recoverPreview(640, 480) }
                compose.waitUntil(15_000) { owner.cameraStates.value.phase == CameraUiPhase.PREVIEWING }
                var retried = false
                var retriedPhase: CameraUiPhase? = null
                compose.runOnUiThread {
                    retried = owner.captureForRole(CaptureActionTicket(false, owner.cameraStates.value.captureActionGeneration), audioForThisTake = audioPreference != null || offSpeed)
                    retriedPhase = owner.cameraStates.value.phase
                }
                // Off-speed VIDEO starts on the already-running GPU viewfinder graph, whose engine
                // start is synchronous: an admitted take is dispatched and rejected inside this call,
                // so capturePrimary returns false with the phase already ERROR. A leaked admission
                // instead returns false silently and leaves the viewfinder PREVIEWING.
                assertTrue("A take after a rejected start must be admitted (phase=$retriedPhase)",
                    retried || retriedPhase == CameraUiPhase.ERROR)
                compose.waitUntil(20_000) { owner.cameraStates.value.phase in setOf(CameraUiPhase.RECORDING, CameraUiPhase.ERROR) }
                val retriedRejection = owner.cameraStates.value
                assertEquals(CameraUiPhase.ERROR, retriedRejection.phase)
                assertTrue(retriedRejection.message.orEmpty(), retriedRejection.message.orEmpty().contains("No hardware video/avc Surface encoder accepts"))
                assertEquals(beforeVideos, ownedVideos())
                compose.waitUntil(10_000) { !admissionHeld() }
                Assume.assumeTrue("positive VIDEO path needs a hardware AVC encoder", false)
            }
            assertTrue("Supported take must accept capture", accepted)
            assertEquals(owner.cameraStates.value.message, CameraUiPhase.RECORDING, owner.cameraStates.value.phase)
            assertTrue(owner.cameraStates.value.captureControlsLocked)
            if (audioPreference != null && mode == CaptureMode.TIME_LAPSE) {
                compose.runOnUiThread {
                    val outer = owner.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(owner)
                    for (name in listOf("audioSidecarRecorder", "previewAudioMonitor")) {
                        assertNull("Silent interval take must not own $name", CaptureService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(outer))
                    }
                    assertFalse(owner.cameraStates.value.audioMonitoringActive)
                }
                assertEquals(beforeAudioRows, ownedAudioRows())
            }
            httpsFixture?.observeAdmission()
            if (queueMode != null) {
                // Enrollment must already be durable while the clip is still recording.
                admittedBundleId = (enrollmentIds() - previousEnrollmentIds).single()
                assertEquals(firstQueueEndpoint, CaptureTransferEnrollmentFile(context).load(admittedBundleId!!)!!.endpointId)
                assertNull(CapturePublicationJournal.load(context, admittedBundleId!!))
                nextQueueEndpoint = queueSettings.save(if (queueMode == "change") "https://second.example.invalid/takes/" else "https://first.example.invalid/takes/",
                    queueMode != "disable", false).activeEndpointId
            }
            if (pauseCycles > 0) {
                compose.waitUntil(5000) { owner.cameraStates.value.timelapseFramesCaptured >= 1 && owner.cameraStates.value.recordingPauseStatus != null }
                repeat(pauseCycles) { cycle ->
                    compose.runOnUiThread {
                        val live = owner.cameraStates.value
                        val takeId = requireNotNull(live.recordingPauseStatus).takeId
                        val ticket = CaptureActionTicket(false, live.captureActionGeneration)
                        assertFalse(owner.setRecordingPausedForRole(true, takeId + 1, ticket))
                        assertFalse(owner.setRecordingPausedForRole(true, takeId, ticket.copy(selfRole = true)))
                        assertFalse(owner.setRecordingPausedForRole(true, takeId, CaptureActionTicket(false,live.captureActionGeneration - 1)))
                        assertFalse(owner.cameraStates.value.recordingPausePending)
                        assertTrue(owner.setRecordingPausedForRole(true, takeId, ticket))
                    }
                    compose.waitUntil(5000) { owner.cameraStates.value.recordingPauseStatus?.paused == true && !owner.cameraStates.value.recordingPausePending }
                    val paused = owner.cameraStates.value
                    assertTrue(paused.captureControlsLocked)
                    compose.runOnUiThread { assertFalse(owner.setRecordingPaused(true)) }
                    android.os.SystemClock.sleep(2000)
                    val held = owner.cameraStates.value
                    assertEquals(CameraUiPhase.RECORDING, held.phase)
                    assertEquals(paused.timelapseFramesCaptured, held.timelapseFramesCaptured)
                    assertEquals(paused.recordingElapsedMs, held.recordingElapsedMs)
                    assertTrue("Paused preview must remain live", held.analysisUpdatedAtMs > paused.analysisUpdatedAtMs)
                    if (!stopWhilePaused) {
                        compose.runOnUiThread { assertTrue(owner.setRecordingPaused(false)) }
                        compose.waitUntil(5000) { owner.cameraStates.value.recordingPauseStatus?.paused == false && owner.cameraStates.value.timelapseFramesCaptured > held.timelapseFramesCaptured }
                    }
                    android.util.Log.i("TimelapsePauseProbe", "cycle=$cycle pausedFrames=${held.timelapseFramesCaptured} activeMs=${held.recordingElapsedMs} previewLive=true stoppedWhilePaused=$stopWhilePaused")
                }
                if (stopWhilePaused) compose.runOnUiThread { assertTrue(owner.stopRecording()) }
            }
            if (manualStop) {
            compose.runOnUiThread {
                repository.update { it.copy(exposure = it.exposure.copy(iso = 800, mode = ExposureMode.MANUAL), timelapseFps = 25, timelapseFpsDenominator = 1) }
                owner.setManualFocus(1f); owner.setZoomRatio(2f)
                owner.applyPreset(CameraPreset(name = "Must not unlock", settings = CameraSettings()))
                assertTrue(owner.performOperatorAction(OperatorAction.ZEBRA))
            }
            compose.waitUntil(5_000) { owner.cameraStates.value.settingsPending && owner.cameraStates.value.effectiveSettings?.zebraEnabled == true }
            assertEquals(oldExposure, owner.cameraStates.value.effectiveSettings?.exposure)
            assertEquals(originalFocus, owner.cameraStates.value.requestedFocusDiopters)
            assertEquals(originalZoom, owner.cameraStates.value.zoomRatio)
            assertTrue(repository.states.value.operation.lockDuringTake)
            assertEquals(800, repository.states.value.exposure.iso)
            assertEquals(CameraUiPhase.RECORDING, owner.cameraStates.value.phase)
            val analysisAtStart = owner.cameraStates.value.analysisUpdatedAtMs
            android.os.SystemClock.sleep(2_500)
            if (mode == CaptureMode.TIME_LAPSE) assertTrue("Preview analysis must keep advancing during interval capture", owner.cameraStates.value.analysisUpdatedAtMs > analysisAtStart)
            compose.runOnUiThread {
                android.util.Log.i("TimelapseCadenceProbe", "beforeStop=${owner.cameraStates.value.phase} frames=${owner.cameraStates.value.timelapseFramesCaptured} error=${owner.cameraStates.value.message}")
                assertTrue("Stop rejected: ${owner.cameraStates.value.phase}/${owner.cameraStates.value.errorCode}/${owner.cameraStates.value.message}", owner.captureForRole(CaptureActionTicket(false, owner.cameraStates.value.captureActionGeneration)))
            }
            }
            compose.waitUntil(20_000) { owner.cameraStates.value.phase in setOf(CameraUiPhase.SAVED, CameraUiPhase.ERROR) }
            assertEquals(owner.cameraStates.value.message, CameraUiPhase.SAVED, owner.cameraStates.value.phase)
            if (manualStop) {
                try { compose.waitUntil(5_000) { owner.cameraStates.value.effectiveSettings?.exposure?.iso == 800 && !owner.cameraStates.value.settingsPending } }
                catch (failure: Throwable) { android.util.Log.e("TimelapseCadenceProbe", "After-save state: ${owner.cameraStates.value.phase}, effectiveISO=${owner.cameraStates.value.effectiveSettings?.exposure?.iso}, requestedISO=${repository.states.value.exposure.iso}, pending=${owner.cameraStates.value.settingsPending}"); throw failure }
            }
            assertFalse(owner.cameraStates.value.captureControlsLocked)
            val uri = android.net.Uri.parse(requireNotNull(owner.cameraStates.value.lastSavedUri))
            if (journalFailure) assertTrue(owner.cameraStates.value.message, owner.cameraStates.value.message.orEmpty().contains(context.getString(R.string.capture_publication_registration_failed)))
            else {
                val publication = verifyPublicationReceipt(uri)
                if(geotagCapture) {
                    val metadata=publication.artifacts.single {it.role.name=="VIDEO_METADATA"}
                    val json=org.json.JSONObject(requireNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(metadata.uri))).use {it.readBytes().toString(Charsets.UTF_8)})
                    val point=json.getJSONObject("captureLocation")
                    assertEquals("AVAILABLE",point.getString("status"))
                    assertEquals(admittedLocation!!.fix!!.latitude,point.getDouble("latitude"),0.0)
                    assertEquals(admittedLocation!!.fix!!.longitude,point.getDouble("longitude"),0.0)
                    assertFalse(repository.states.value.geotaggingEnabled)
                    android.util.Log.i("E16GeotagProbe","actualTimelapseService=true locationFrozenAtAdmission=true optOutDuringPreparation=true primaryVideoMetadata=true relatedPublication=true")
                }
                productionSlate?.let { frozen ->
                    val metadata = publication.artifacts.single { it.role.name == "VIDEO_METADATA" }
                    val json = org.json.JSONObject(requireNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(metadata.uri))).use { it.readBytes().toString(Charsets.UTF_8) })
                    val node = kotlinx.serialization.json.Json.parseToJsonElement(json.getJSONObject("productionSlate").toString()) as kotlinx.serialization.json.JsonObject
                    assertEquals(frozen, parseProductionSlateJson(node))
                    captureNaming?.let { naming ->
                        val stem=captureFileStem(json.getString("bundleId"),CaptureNameSnapshot(naming,frozen,0))
                        assertEquals(stem+".mp4",publication.artifacts.single { it.role.name=="VIDEO" }.displayName)
                        assertEquals(stem+".timing.json",metadata.displayName)
                        assertEquals(CaptureNamingSettings(true,"NEXT"),repository.states.value.captureNaming)
                    }
                    assertEquals(uri.toString(), json.getString("videoUri"))
                    val expected = if (transferCancel == false) frozen.copy(scene = "NEXT", takeNumber = 41) else frozen.copy(takeNumber = frozen.takeNumber + 1)
                    assertEquals(expected, repository.states.value.productionSlate)
                }
                verifyQueue(publication, first = true, message = owner.cameraStates.value.message.orEmpty())
                httpsFixture?.observePublication(publication)
                audioPreference?.let { format ->
                    val summary = ServiceAudioMatrixAssertions.assertPublished(context, publication, format, mode == CaptureMode.TIME_LAPSE)
                    if (mode == CaptureMode.TIME_LAPSE) assertEquals(beforeAudioRows, ownedAudioRows())
                    android.util.Log.i("ServiceAudioMatrixProbe", "mode=$mode requested=$format explicitAudioIntent=true outcome=PUBLISHED $summary")
                }
            }
            val firstTimecodeTakeId = if (timecodeMode != null) verifyTimecode(uri, "00:00:59;29", "00:01:00;03", "first") else null
            val fixture = java.io.File(context.cacheDir, "operator-lock-${mode.name.lowercase()}${audioPreference?.let { "-audio-${it.name}" }.orEmpty()}${frameLimit?.let { "-limit-$it" }.orEmpty()}${if (rate.denominator != 1) "-rate-${rate.numerator}-${rate.denominator}" else ""}${wallLimitMs?.let { "-duration-$it" }.orEmpty()}${if (pauseCycles > 0) "-pause-$pauseCycles-stop-$stopWhilePaused" else ""}.mp4")
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> fixture.outputStream().use { output -> input.copyTo(output) } }
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(fixture.path)
                val duration = requireNotNull(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                assertTrue(duration > 0)
                val frame = retriever.getFrameAtTime(0)
                assertNotNull("Saved take must decode a frame", frame)
                if (mode == CaptureMode.TIME_LAPSE) {
                    val extractor = android.media.MediaExtractor()
                    val timestamps = mutableListOf<Long>()
                    try {
                        extractor.setDataSource(fixture.path)
                        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                        extractor.selectTrack(track)
                        while (extractor.sampleTime >= 0) { timestamps.add(extractor.sampleTime); extractor.advance() }
                    } finally { extractor.release() }
                    if (manualStop) assertTrue("Unexpected interval frame count: $timestamps", timestamps.size in 4..8)
                    else if (frameLimit != null) { assertEquals(frameLimit, timestamps.size); assertEquals(frameLimit, owner.cameraStates.value.timelapseFramesCaptured) }
                    else if (stopWhilePaused) assertEquals(owner.cameraStates.value.timelapseFramesCaptured,timestamps.size)
                    else { assertTrue(timestamps.size >= 3); assertTrue(owner.cameraStates.value.recordingElapsedMs in requireNotNull(wallLimitMs)..(wallLimitMs + 5000)) }
                    assertEquals(rate, owner.cameraStates.value.recordingProjectRate)
                    timestamps.forEachIndexed { index, pts -> assertTrue("Absolute project PTS drift at $index: $pts", kotlin.math.abs(pts - projectFrameTimestampNs(index.toLong(), rate) / 1000) <= 2) }
                    timestamps.zipWithNext().forEach { (a, b) -> assertTrue("Project cadence delta ${b-a} us", kotlin.math.abs((b-a) - 1_000_000.0 * rate.denominator / rate.numerator) <= 20) }
                    if (pauseCycles > 0) {
                        val status = requireNotNull(owner.cameraStates.value.recordingPauseStatus)
                        assertTrue(status.finished);assertEquals(timestamps.size.toLong(), status.submittedFrames)
                        assertEquals(if (stopWhilePaused) 1 else pauseCycles * 2, status.events.size)
                        assertTrue(status.pausedElapsedMs >= pauseCycles * 2000L)
                        val clipName = requireNotNull(context.contentResolver.query(uri,arrayOf(android.provider.MediaStore.Video.Media.DISPLAY_NAME),null,null,null)).use { it.moveToFirst();it.getString(0) }
                        val sidecarName = clipName.removeSuffix(".mp4") + ".timing.json"
                        val downloads = android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        val sidecar = requireNotNull(context.contentResolver.query(downloads,arrayOf(android.provider.MediaStore.Downloads._ID,android.provider.MediaStore.Downloads.IS_PENDING),
                            "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",arrayOf(sidecarName,context.packageName),null)).use {
                            assertTrue(it.moveToFirst());assertEquals(0,it.getInt(1));android.content.ContentUris.withAppendedId(downloads,it.getLong(0))
                        }
                        val jsonText = requireNotNull(context.contentResolver.openInputStream(sidecar)).bufferedReader().use { it.readText() }
                        val json = org.json.JSONObject(jsonText)
                        assertEquals(status.submittedFrames,json.getLong("submittedFrames"));assertEquals(status.events.size,json.getJSONArray("pauseEvents").length())
                        assertEquals(status.activeElapsedMs,json.getLong("activeCaptureMs"));assertEquals(rate.numerator,json.getInt("projectNumerator"))
                        java.io.File(context.cacheDir,fixture.nameWithoutExtension + ".timing.json").writeText(jsonText)
                        context.contentResolver.delete(sidecar,null,null)
                        android.util.Log.i("TimelapsePauseProbe", "finalFrames=${timestamps.size} exactPtsUs=$timestamps pauses=${status.events.size} activeMs=${status.activeElapsedMs} pausedMs=${status.pausedElapsedMs} sidecarVerified=true fixture=${fixture.name}")
                    }
                    if (reviewAfterCapture) {
                        reviewPublishedTake(context, owner, uri, timestamps, interpretReviewColor) {
                            owner.detachPreview()
                            showCameraRoot.value = true
                        }
                    }
                    android.util.Log.i("TimelapseCadenceProbe", "frames=${timestamps.size} ptsUs=$timestamps projectFps=${rate.projectLabel()} sensorFps=${owner.cameraStates.value.targetFps} wallMs=${owner.cameraStates.value.recordingElapsedMs} codec=${owner.cameraStates.value.timelapseEncoder} hardware=${owner.cameraStates.value.timelapseHardwareEncoder} missed=${owner.cameraStates.value.timelapseMissedIntervals}")
                }
                if (manualStop) android.util.Log.i("OperatorTakeProbe", "mode=$mode lockedDuringREC=true cameraIntentDeferred=true directFocusZoomRejected=true presetCannotUnlock=true zebraLive=true stopAccepted=true afterSaveISO=800 pending=false durationMs=$duration decoded=${frame?.width}x${frame?.height} bytes=${fixture.length()}")
                frame?.recycle()
                if (httpsFixture != null) {
                    compose.runOnUiThread { owner.detachPreview(); showTransfers.value = true }
                    httpsFixture.runtime.refresh()
                    compose.waitUntil(20_000) {
                        val state = httpsFixture.runtime.states.value
                        !state.busy && state.message != WebDavTransferMessage.WAITING_RECORDING &&
                            state.bundles.any { it.id == httpsFixture.bundleId }
                    }
                    compose.onNodeWithTag("webdav-transfer-send-${httpsFixture.bundleId}")
                        .performScrollTo().assertIsEnabled().performClick()
                    compose.waitUntil(60_000) { !httpsFixture.runtime.states.value.busy }
                    httpsFixture.assertCompleteAndExport()
                }
            } finally { retriever.release(); context.contentResolver.delete(uri, null, null) }
            if (mode == CaptureMode.TIME_LAPSE && frameLimit == 3 && !serviceHttps) {
                if (recreateOwner) {
                    val oldOwner = owner
                    val oldSurface = requireNotNull(surface.get())
                    val createdBeforeRecreation = surfacesCreated.get()
                    val destroyedBeforeRecreation = surfacesDestroyed.get()
                    compose.runOnUiThread { oldOwner.detachPreview(); context.unbindService(connection); bound = false; context.stopService(Intent(context, CaptureService::class.java)); binder.set(null) }
                    if (reuseSurface) {
                        // Preserve the exact Surface object and its native window across owners.
                        // Admission, rather than replacing the SurfaceView, must await EGL/Camera2.
                        assertSame(oldSurface, surface.get())
                        assertTrue(oldSurface.isValid)
                    } else {
                        compose.runOnUiThread { surfaceGeneration.intValue++ }
                        compose.waitUntil(10000) { surface.get()?.let { it.isValid && it !== oldSurface } == true }
                    }
                    instrumentation.waitForIdleSync()
                    bound = context.bindService(Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
                    assertTrue(bound); compose.waitUntil(10000) { binder.get() != null }
                    owner = requireNotNull(binder.get()); assertNotSame(oldOwner, owner)
                    compose.runOnUiThread { owner.prepare(640, 480); owner.selectMode(CaptureMode.TIME_LAPSE, reopen = false); assertTrue(owner.attachPreview(requireNotNull(surface.get()), 0)) }
                    compose.waitUntil(15000) { owner.cameraStates.value.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.ERROR) }
                    assertEquals(owner.cameraStates.value.message, CameraUiPhase.PREVIEWING, owner.cameraStates.value.phase)
                    if (reuseSurface) {
                        assertSame(oldSurface, surface.get())
                        assertEquals("The same-Surface test must not recreate its native window", createdBeforeRecreation, surfacesCreated.get())
                        assertEquals("The same-Surface test must not destroy its native window", destroyedBeforeRecreation, surfacesDestroyed.get())
                    } else assertNotSame(oldSurface, surface.get())
                    android.util.Log.i("TimecodeTakeProbe", "serviceRecreated=true distinctBinder=true sameOperatorSurface=$reuseSurface distinctOperatorSurface=${!reuseSurface} surfaceCreatedDelta=${surfacesCreated.get() - createdBeforeRecreation} surfaceDestroyedDelta=${surfacesDestroyed.get() - destroyedBeforeRecreation} mode=$timecodeMode")
                }

                repeat(if (interpretReviewColor) 3 else 1) { transitionIndex ->
                    compose.runOnUiThread { owner.selectMode(CaptureMode.PHOTO); assertNull(owner.cameraStates.value.recordingProjectRate) }
                    // Selection queues a camera reopen; PREVIEWING alone can still describe the old graph.
                    compose.waitUntil(15000) { owner.cameraStates.value.let {
                        it.phase == CameraUiPhase.ERROR || (it.phase == CameraUiPhase.PREVIEWING && it.selectedMode == CaptureMode.PHOTO && !it.gpuViewfinder)
                    } }
                    assertEquals(CameraUiPhase.PREVIEWING,owner.cameraStates.value.phase)
                    assertFalse("PHOTO must finish opening its direct graph before the next selection", owner.cameraStates.value.gpuViewfinder)
                    compose.runOnUiThread { owner.selectMode(CaptureMode.TIME_LAPSE) }
                    compose.waitUntil(15000) { owner.cameraStates.value.let {
                        it.phase == CameraUiPhase.ERROR || (it.phase == CameraUiPhase.PREVIEWING && it.selectedMode == CaptureMode.TIME_LAPSE && it.gpuViewfinder)
                    } }
                    assertEquals(CameraUiPhase.PREVIEWING,owner.cameraStates.value.phase);assertTrue("Expected GPU preview after TIME_LAPSE reopen: ${owner.cameraStates.value}",owner.cameraStates.value.gpuViewfinder)
                    assertNull(owner.cameraStates.value.recordingProjectRate)
                    if (reattachGpuPreview) verifyGpuReattachment(owner)
                    if (interpretReviewColor) {
                        compose.runOnUiThread {
                            fun actualSelfRole(): String = runCatching {
                                val serviceField = owner.javaClass.declaredFields.single { it.type == CaptureService::class.java }.apply { isAccessible = true }
                                val service = serviceField.get(owner)
                                CaptureService::class.java.getDeclaredField("selfRecordingRole").apply { isAccessible = true }.getBoolean(service).toString()
                            }.getOrElse { "lookupFailure=$it" }
                            val beforeSecondCapture = owner.cameraStates.value
                            val beforeActorState = owner.states.value
                            val ticket = CaptureActionTicket(false, beforeSecondCapture.captureActionGeneration)
                            val roleBefore = actualSelfRole()
                            val admitted = owner.captureForRole(ticket)
                            val afterSecondCapture = owner.cameraStates.value
                            val roleAfter = actualSelfRole()
                            android.util.Log.i("ReviewTransitionProbe", "transition=${transitionIndex + 1}/${if (interpretReviewColor) 3 else 1} " +
                                "ticket=$ticket admitted=$admitted actualSelfRoleBefore=$roleBefore actualSelfRoleAfter=$roleAfter " +
                                "phaseBefore=${beforeSecondCapture.phase} phaseAfter=${afterSecondCapture.phase} " +
                                "errorCodeBefore=${beforeSecondCapture.errorCode} errorCodeAfter=${afterSecondCapture.errorCode} " +
                                "gpuViewfinderBefore=${beforeSecondCapture.gpuViewfinder} gpuViewfinderAfter=${afterSecondCapture.gpuViewfinder}")
                            assertTrue("Second capture rejected: ticket=$ticket actualSelfRoleBefore=$roleBefore actualSelfRoleAfter=${actualSelfRole()} " +
                                "cameraBefore=$beforeSecondCapture cameraAfter=${owner.cameraStates.value} " +
                                "actorBefore=$beforeActorState actorAfter=${owner.states.value}", admitted)
                        }
                    } else {
                        compose.runOnUiThread { assertTrue(owner.captureForRole(CaptureActionTicket(false,owner.cameraStates.value.captureActionGeneration))) }
                    }
                    compose.waitUntil(20000) { owner.cameraStates.value.phase in setOf(CameraUiPhase.SAVED,CameraUiPhase.ERROR) }
                    assertEquals(owner.cameraStates.value.message,CameraUiPhase.SAVED,owner.cameraStates.value.phase)
                    val second=android.net.Uri.parse(requireNotNull(owner.cameraStates.value.lastSavedUri));val reader=android.media.MediaExtractor()
                    if (journalFailure) assertTrue(owner.cameraStates.value.message, owner.cameraStates.value.message.orEmpty().contains(context.getString(R.string.capture_publication_registration_failed)))
                    else verifyQueue(verifyPublicationReceipt(second), first = false, message = owner.cameraStates.value.message.orEmpty())
                    if (timecodeMode != null) assertNotEquals(firstTimecodeTakeId, verifyTimecode(second, "00:01:00;04", "00:01:00;06", "second"))
                    try {
                        reader.setDataSource(context,second,null);reader.selectTrack(0)
                        val times=mutableListOf<Long>();while(reader.sampleTime>=0) { times+=reader.sampleTime;reader.advance() }
                        assertEquals(listOf(0L,33333L,66666L),times)
                        android.util.Log.i("OutputCommitProbe","sameOwnerRestart=true modeChangeClearedProject=true gpuViewfinder=true secondPtsUs=$times")
                    } finally { reader.release();context.contentResolver.delete(second,null,null) }
                }
            }
        } finally {
          transferRelease.countDown()
          locationProvider?.close()
          try {
            compose.runOnUiThread { binder.get()?.stopRecording() }
            compose.waitUntil(20_000) { binder.get()?.cameraStates?.value?.phase != CameraUiPhase.RECORDING }
            compose.runOnUiThread { binder.get()?.detachPreview(); repository.set(before) }
            if (bound) context.unbindService(connection)
            if (audioPreference != null) (ownedAudioRows() - beforeAudioRows).forEach { id ->
                context.contentResolver.delete(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), null, null)
            }
            (ownedSidecars() - beforeSidecars).forEach { id ->
                context.contentResolver.delete(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,id),null,null)
            }
          } finally {
            if (httpsFixture != null) {
                if (httpsPrepared) {
                    httpsFixture.runtime.cancel()
                    compose.waitUntil(20_000) { !httpsFixture.runtime.states.value.busy &&
                        httpsFixture.runtime.states.value.message != WebDavTransferMessage.WAITING_RECORDING }
                }
                httpsFixture.closeRetired()
            }
            if (queueMode != null) queueSettings.save(previousQueue.activeEndpoint?.url.orEmpty(), previousQueue.enabled, previousQueue.allowCellular)
            if (journalBlocked) {
                check(journalDirectory.isFile && journalDirectory.delete())
                if (preservedJournal.exists()) check(preservedJournal.renameTo(journalDirectory))
            }
            if (transferFixture != null) {
                compose.waitUntil(20_000) { !transferFixture.states.value.busy &&
                    transferFixture.states.value.message != WebDavTransferMessage.WAITING_RECORDING }
                transferFixture.closeForTest()
                context.deleteDatabase("e1-$transferId.db")
                transferSettingsFile.delete()
            }
          }
        }
    }

    /** Only this opt-in path replaces the existing SurfaceView fixture with the actual root UI.
     * It keeps the same service owner and uses real catalog queries/reader, not a fabricated take. */
    private fun reviewPublishedTake(context: Context, owner: CaptureService.LocalBinder, uri: android.net.Uri,
        capturePts: List<Long>, interpretColor: Boolean = false, showRoot: () -> Unit) {
        val media = com.librestatic.opencinecam.storage.LocalMediaRepository(context)
        val name = requireNotNull(context.contentResolver.query(uri,
            arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)).use {
            check(it.moveToFirst()); it.getString(0)
        }
        var cursor: com.librestatic.opencinecam.storage.LocalMediaCursor? = null
        var selected: com.librestatic.opencinecam.storage.LocalMediaTake? = null
        var pages = 0
        do {
            check(++pages <= 100) { "Published take was not found within the catalog scan bound" }
            val page = media.page(GallerySettings(), name, cursor, 60)
            selected = page.takes.singleOrNull { it.primary.uri == uri.toString() }
            cursor = page.next
        } while (selected == null && cursor != null)
        val take = requireNotNull(selected) { "Actual published MediaStore URI was missing from the catalog: $uri" }
        assertEquals(com.librestatic.opencinecam.storage.LocalMediaRelationStatus.DECLARED, take.relationStatus)
        assertTrue(take.metadata.isNotEmpty())
        val members = take.originals + take.metadata
        fun bytes(artifact: com.librestatic.opencinecam.storage.LocalMediaArtifact): ByteArray =
            requireNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(artifact.uri))).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    check(count > 0 && output.size().toLong() + count <= 16L * 1024 * 1024) { "Three-frame review fixture exceeds byte bound" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        val originalBytes = members.associate { it.uri to bytes(it) }
        val expectedPts = mutableListOf<Long>()
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            while (extractor.sampleTime >= 0) {
                check(expectedPts.size < 16)
                expectedPts += extractor.sampleTime
                if (!extractor.advance()) break
            }
        } finally { extractor.release() }
        assertEquals(capturePts, expectedPts)
        assertEquals(3, expectedPts.size)
        fun readers() = Thread.getAllStackTraces().keys.filter { it.name == "media-review-reader" && it.isAlive }.toSet()
        val oldReaders = readers()
        // The camera screen keeps tickers and a loading indicator running, so it never idles under
        // the auto-advancing test clock; frames advance only while this review waits for them.
        compose.mainClock.autoAdvance = false
        fun awaitFrames(timeoutMillis: Long, condition: () -> Boolean) =
            compose.waitUntil(timeoutMillis) { compose.mainClock.advanceTimeByFrame(); condition() }
        OnboardingStore(context).markCompleted() // A fresh device would show the first-run wizard instead.
        try {
        compose.runOnUiThread(showRoot)
        awaitFrames(20_000) { compose.onAllNodesWithTag("media-action").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media-action").performClick()
        awaitFrames(20_000) { compose.onAllNodesWithTag("gallery-search").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = true // The gallery has replaced the camera screen and its tickers.
        compose.onNodeWithTag("gallery-search").performTextReplacement(name)
        awaitFrames(20_000) {
            runCatching { compose.onNodeWithTag("gallery-list").performScrollToKey(take.id) }.isSuccess
        }
        compose.onNodeWithTag("gallery-name-${take.id}", useUnmergedTree = true).assertTextEquals(name)
        compose.onNodeWithTag("gallery-primary-${take.id}", useUnmergedTree = true).performScrollTo().performClick()
        fun node(tag: String) = compose.onNodeWithTag("media-playback-$tag", useUnmergedTree = true)
        // Decoding notes live in the take details; close them again so the transport takes touches.
        fun assertInterpreted() {
            compose.openPlaybackDetails()
            node("color-interpretation").performScrollTo().assertIsDisplayed()
            compose.closePlaybackDetails()
        }
        fun waitExact(index: Int) {
            fun expected() = node("exact").assertExactFrame(context, index + 1, expectedPts.size, expectedPts[index])
            awaitFrames(30_000) {
                compose.onAllNodesWithTag("media-playback-error-detail", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                    runCatching { node("exact").performScrollTo(); expected() }.isSuccess
            }
            node("error-detail").assertDoesNotExist()
            expected()
            if (interpretColor) assertInterpreted()
        }
        fun click(tag: String) { node(tag).performScrollTo().assertIsEnabled().performClick() }
        val playbackBeforeReview = SettingsRepositories.get(context).states.value.playback
        fun requireExplicitInterpretation() {
            fun shown(tag: String) = compose.onAllNodesWithTag("media-playback-$tag", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            awaitFrames(30_000) { shown("error-detail") || shown("exact") }
            // Only some encoders tag the take with a color standard that conflicts with its track.
            org.junit.Assume.assumeTrue("This device's encoder produced no color conflict to interpret", shown("error-detail"))
            node("status").assertTextEquals(context.getString(R.string.media_playback_error))
            node("exact").assertDoesNotExist()
            node("frame").assertDoesNotExist()
            node("color-interpretation").assertDoesNotExist()
            // This separate test intentionally qualifies the actual encoder conflict, not a fallback.
            node("error-detail").assertTextContains("color-standard", substring = true)
            click("interpret-track")
            awaitFrames(30_000) {
                compose.onAllNodesWithTag("media-playback-exact", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            waitExact(0)
            assertInterpreted()
        }
        try {
            if (interpretColor) {
                requireExplicitInterpretation()
                val firstReaders = readers() - oldReaders
                assertTrue("Interpreted exact review must own a real reader worker", firstReaders.isNotEmpty())
                node("close").performClick()
                node("dialog").assertDoesNotExist()
                awaitFrames(30_000) { firstReaders.none { it.isAlive } && (readers() - oldReaders).isEmpty() }
                members.forEach { artifact -> assertArrayEquals("Interpretation changed owned bytes: ${artifact.uri}", originalBytes.getValue(artifact.uri), bytes(artifact)) }
                assertEquals(playbackBeforeReview, SettingsRepositories.get(context).states.value.playback)
                compose.onNodeWithTag("gallery-primary-${take.id}", useUnmergedTree = true).performScrollTo().performClick()
                // Closing a review discards its choice: reopening the same URI must reject strictly again.
                requireExplicitInterpretation()
            }
            waitExact(0)
            val ownedReaders = readers() - oldReaders
            assertTrue("Exact review must own a real reader worker", ownedReaders.isNotEmpty())
            node("previous-frame").performScrollTo().assertIsNotEnabled()
            click("next-frame"); waitExact(1)
            click("previous-frame"); waitExact(0)
            click("end"); waitExact(expectedPts.lastIndex)
            node("next-frame").performScrollTo().assertIsNotEnabled()
            click("start"); waitExact(0)
            node("close").performClick()
            node("dialog").assertDoesNotExist()
            awaitFrames(30_000) { ownedReaders.none { it.isAlive } }
            assertTrue("No new reader may remain after review dismissal", (readers() - oldReaders).isEmpty())
            members.forEach { artifact -> assertArrayEquals("Review changed owned bytes: ${artifact.uri}", originalBytes.getValue(artifact.uri), bytes(artifact)) }
            assertEquals(take, media.page(GallerySettings(), name, null, 60).takes.single { it.primary.uri == uri.toString() })
            compose.onNodeWithText(context.getString(R.string.capture_tab)).performClick()
            awaitFrames(20_000) { compose.onAllNodesWithTag("media-action").fetchSemanticsNodes().isNotEmpty() }
            val previousAnalysis = owner.cameraStates.value.analysisUpdatedAtMs
            awaitFrames(20_000) { owner.cameraStates.value.analysisUpdatedAtMs > previousAnalysis }
            assertTrue(owner.cameraStates.value.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED))
            assertEquals(CaptureMode.TIME_LAPSE, owner.cameraStates.value.selectedMode)
            node("dialog").assertDoesNotExist()
            android.util.Log.i("MediaStoreReviewProbe", "servicePublication=true catalogTake=${take.id} primary=$uri actualPtsUs=$expectedPts exactFirstPreviousNextEndStart=true readerWorkersRetired=${ownedReaders.size} originalAndJsonBytesUnchanged=true returnedToLiveCapture=true")
            if (interpretColor) {
                assertEquals(playbackBeforeReview, SettingsRepositories.get(context).states.value.playback)
                android.util.Log.i("MediaStoreReviewProbe", "explicitColorInterpretation=true colorFidelityVerified=false strictErrorBeforeEachConsent=true choiceResetOnReopen=true playbackPreferencesUnchanged=true")
            }
        } finally {
            if (interpretColor) {
                // The outer capture fixture deletes its originals on exit, including assertion failure.
                // Dismiss and retire every review owner before that cleanup can touch a source URI.
                if (compose.onAllNodesWithTag("media-playback-dialog", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                    node("close").performClick()
                    node("dialog").assertDoesNotExist()
                }
                awaitFrames(30_000) { (readers() - oldReaders).isEmpty() }
            }
        }
        } finally { compose.mainClock.autoAdvance = true }
    }

}
