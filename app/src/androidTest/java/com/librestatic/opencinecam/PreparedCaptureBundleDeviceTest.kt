/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.ContentUris
import android.net.Uri
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.storage.*
import com.librestatic.opencinecam.transfers.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** Actual GLES clips, AudioRecord PCM and MediaStore rows, with no fixture-file dependency. */
class PreparedCaptureBundleDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val frame = RecordingFrameSize(128, 96)
    private val geometry = RecordingGeometry(RecordingGeometryMode.COMPATIBLE, 0, 0, 0, 0, 0, frame, frame, frame)

    @Test fun videoOnlyPreparesOnePendingIdentityBeforePublishing() = successfulBundle("video", null, metadata = false)
    @Test fun videoAndJsonPrepareTwoPendingIdentitiesBeforePublishing() = successfulBundle("video-json", null)
    @Test fun wavBundlePreparesFourPendingIdentitiesAndFinalPcmMetadata() = successfulBundle("wav", "WAV")
    @Test fun flacBundlePreparesFourPendingIdentitiesAndFinalPcmMetadata() = successfulBundle("flac", "FLAC")
    @Test fun preparedRegistrationFailureLeavesTheWavOriginalsPublished() = successfulBundle("wav-registration-prepared", "WAV", registrationFailure = "prepared")
    @Test fun publishedRegistrationFailureLeavesTheFlacOriginalsPublished() = successfulBundle("flac-registration-published", "FLAC", registrationFailure = "published")
    @Test fun realJournalStorageFailureStillSealsQueueAndPreservesWavOriginals() = successfulBundle("wav-queue-journal-failure", "WAV", queueFailure = "journal")
    @Test fun realSqliteStageFailurePreservesFlacOriginalsAndCommittedJournal() = successfulBundle("flac-queue-sql-failure", "FLAC", queueFailure = "sql")
    @Test fun actualVideoPublicationFailureRevokesPublishedWavAndBothMetadataRows() = failedPublication("WAV")
    @Test fun actualVideoPublicationFailureRevokesPublishedFlacAndBothMetadataRows() = failedPublication("FLAC")
    @Test fun abortingPreparedWavAndVideoNeverResurrectsEitherOutput() = abortPrepared("WAV")
    @Test fun abortingPreparedFlacAndVideoNeverResurrectsEitherOutput() = abortPrepared("FLAC")

    @Test fun automaticWavAbortWaitsForTheSharedNativeGuard() = automaticAbortWithNativeOwner("WAV")
    @Test fun automaticFlacAbortWaitsForTheSharedNativeGuard() = automaticAbortWithNativeOwner("FLAC")

    @Test fun videoWithoutTechnicalJsonPublishesOneFrozenSlateMetadataArtifact() = successfulBundle(
        "slate-video", null, metadata=false, productionSlate=slateFixture())
    @Test fun wavAndVideoPublishTheSameFrozenSlateWithoutAddingAFifthArtifact() = successfulBundle(
        "slate-wav", "WAV", productionSlate=slateFixture())
    @Test fun flacAndVideoPublishTheSameFrozenSlateWithoutAddingAFifthArtifact() = successfulBundle(
        "slate-flac", "FLAC", productionSlate=slateFixture())

    @Test fun configuredWavBundleNamesRemainCoherentThroughPreparationPublicationAndQueue() = successfulBundle(
        "named-wav","WAV",productionSlate=slateFixture(),captureNames=CaptureNameSnapshot(CaptureNamingSettings(true,"{camera}_{scene}_T{take}_{date}"),slateFixture(),0))
    @Test fun configuredFlacBundleNamesRemainCoherentThroughPreparationPublicationAndQueue() = successfulBundle(
        "named-flac","FLAC",productionSlate=slateFixture(),captureNames=CaptureNameSnapshot(CaptureNamingSettings(true,"{project}_{reel}_{time}"),slateFixture(),0))

    private fun slateFixture() = ProductionSlateSettings(project="Recorded slate", camera="B", scene="12B",
        reel="R4", lens="35mm", takeNumber=17, location=ProductionSlateLocation.EXTERIOR,
        timeOfDay=ProductionSlateTimeOfDay.NIGHT, goodTake=true, autoIncrementTake=true)

    private fun automaticAbortWithNativeOwner(format: String) = withTake(format) { take ->
        val group = take.video.recoveryGroup
        val guard = group.retainNativeWriter()
        val journal = RecordingCaptureRecovery(context)
        val before = journal.allRows(group.id)
        assertEquals(2, before.size)
        try {
            take.video.finish(false)
            take.audio!!.finish(false)
            assertEquals(before, journal.allRows(group.id))
            assertEquals(RecordingRecoveryReport(), RecordingCaptureRecovery(context).recover())
            assertEquals(before, journal.allRows(group.id))
        } finally { guard.close() }
        assertTrue(journal.allRows(group.id).isEmpty())
        assertEquals(RecordingRecoveryReport(), journal.recover())
    }

    /** Host-only recovery driver reuses the same actual GLES/AudioRecord take and assertions. */
    internal fun pauseForProcessDeath(boundary: String, pause: (String) -> Nothing) {
        withTake("WAV", duringRecording = if (boundary == "WRITING") { take -> pause(take.video.recoveryGroup.id) } else null) { take ->
            requireNotNull(take.prepareAudio())
            requireNotNull(take.prepareVideo(true))
            assertEquals(4, take.artifacts.size)
            verifyRows(take.artifacts, 1)
            verifyAudioMetadata(take.preparedAudio)
            verifyVideoDecodes(take.video.uri)
            if (boundary == "PREPARED") pause(take.video.recoveryGroup.id)
            requireNotNull(take.audio!!.publishPrepared())
            if (boundary == "PARTIAL") pause(take.video.recoveryGroup.id)
            if (boundary == "BEFORE_COMMIT") {
                // Actual provider-visible rows are not a commit. Simulate death between the last
                // publication update and durable confirmation, without altering journal state.
                take.artifacts.filter { it.role in setOf(CaptureArtifactRole.VIDEO, CaptureArtifactRole.VIDEO_METADATA) }.forEach {
                    assertEquals(1, resolver.update(Uri.parse(it.uri), android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null))
                }
                verifyRows(take.artifacts, 0)
                pause(take.video.recoveryGroup.id)
            }
            requireNotNull(take.video.publishPrepared())
            verifyRows(take.artifacts, 0)
            assertEquals("COMMITTED", boundary)
            pause(take.video.recoveryGroup.id)
        }
    }

    /** Playback integration reuses real publication/ownership checks without exporting duplicate files. */
    internal fun withPublishedPlaybackTake(format: String, review: (LocalMediaTake) -> Unit) =
        successfulBundle("review-${format.lowercase()}", format, productionSlate = slateFixture(), review = review)

    private fun successfulBundle(id: String, format: String?, metadata: Boolean = true, registrationFailure: String? = null,
        queueFailure: String? = null, productionSlate: ProductionSlateSettings? = null,captureNames:CaptureNameSnapshot?=null,
        review: ((LocalMediaTake) -> Unit)? = null) =
        withTake(format, queueEnabled = true, queueFailure = queueFailure, productionSlate = productionSlate,captureNames=captureNames) { take ->
        val queue = requireNotNull(take.queue)
        val injected = IllegalStateException("Injected $registrationFailure registration failure")
        var preparedCalls = 0
        var publishedCalls = 0
        var abortedCalls = 0
        var preparedRows = emptySet<String>()
        var preparedHashes = emptyMap<String, String>()
        val result = finalizePreparedRecordingTake<PreparedVideoOutput, PreparedAudioSidecar, Uri, AudioSidecarRecordingResult>(
            success = true,
            prepareAudio = take.audio?.let { { take.prepareAudio() } },
            prepareVideo = { take.prepareVideo(metadata, catalogMetadata = review != null) },
            publishAudio = { take.audio!!.publishPrepared() },
            publishVideo = { take.video.publishPrepared() },
            discardAudio = { take.audio?.discard() },
            discardVideo = { take.video.finish(false) },
            onPrepared = { video, audio ->
                val artifacts = video.artifacts + audio?.artifacts.orEmpty()
                assertEquals(if (format != null) 4 else if (metadata || productionSlate != null) 2 else 1, artifacts.size)
                assertEquals(artifacts.size, artifacts.map { it.uri }.toSet().size)
                assertEquals(artifacts.size, artifacts.map { it.role }.toSet().size)
                assertThrows(UnsupportedOperationException::class.java) { (video.artifacts as MutableList<PreparedCaptureArtifact>).clear() }
                audio?.let { assertThrows(UnsupportedOperationException::class.java) { (it.artifacts as MutableList<PreparedCaptureArtifact>).clear() } }
                captureNames?.let { names ->
                    val stem=captureFileStem(take.video.recoveryGroup.id,names)
                    for(artifact in artifacts) {
                        val suffix=when(artifact.role) {
                            CaptureArtifactRole.VIDEO -> ".mp4"
                            CaptureArtifactRole.VIDEO_METADATA -> ".timing.json"
                            CaptureArtifactRole.AUDIO -> if(format=="FLAC") ".flac" else ".wav"
                            CaptureArtifactRole.AUDIO_METADATA -> ".audio.json"
                        }
                        assertEquals(stem+suffix,artifact.displayName)
                    }
                }
                preparedHashes = verifyRows(artifacts, pending = 1)
                preparedRows = ownedRows()
                // Repeated preparation must reuse identities and seal bytes only once.
                assertSame(video, take.video.prepareCompletion("{\"mustNotReplace\":true}", geometry, true))
                audio?.let { assertSame(it, take.audio!!.prepareCompletion()) }
                assertEquals(preparedRows, ownedRows())
                verifyAudioMetadata(audio)
                productionSlate?.let { assertPreparedSlate(video, audio, it) }
                audio?.let {
                    val videoJson = JSONObject(String(bytes(video.artifacts.single { artifact -> artifact.role == CaptureArtifactRole.VIDEO_METADATA }), Charsets.UTF_8))
                    assertEquals(it.result.frames, videoJson.getLong("audioFrames"))
                }
                preparedCalls++
                queue.prepared(artifacts)
                if (registrationFailure == "prepared") throw injected
            },
            onPublished = { video, audio ->
                val artifacts = video.artifacts + audio?.artifacts.orEmpty()
                assertEquals(preparedRows, ownedRows())
                assertEquals(preparedHashes, verifyRows(artifacts, pending = 0))
                assertEquals(video.uri, take.video.publishPrepared())
                audio?.let { assertSame(it.result, take.audio!!.publishPrepared()) }
                assertEquals(preparedRows, ownedRows())
                verifyAudioMetadata(audio)
                productionSlate?.let { assertPreparedSlate(video, audio, it) }
                publishedCalls++
                queue.published(artifacts)
                if (registrationFailure == "published") throw injected
            },
            onAborted = { abortedCalls++ },
        )
        // Callback assertions are deliberately checked here too: registration captures throwables
        // instead of failing capture. An assertion must never masquerade as the injected failure.
        if (queueFailure != null) queue.verifyFailures(result.registrationFailures)
        else if (registrationFailure == null) assertTrue(result.registrationFailures.toString(), result.registrationFailures.isEmpty())
        else { assertEquals(1, result.registrationFailures.size); assertSame(injected, result.registrationFailures.single()) }
        assertEquals(1, preparedCalls); assertEquals(1, publishedCalls); assertEquals(0, abortedCalls)
        val saved = requireNotNull(result.outputs)
        assertEquals(take.video.uri, saved.first)
        if (format == null) assertNull(saved.second) else assertEquals(format, requireNotNull(saved.second).container)
        assertEquals(preparedHashes, verifyRows(take.artifacts, pending = 0))
        queue.verifyCompleted(take.artifacts)
        queue.verifyWorker(id, take.artifacts)
        verifyVideoDecodes(saved.first)
        if (review != null) {
            val catalog = LocalMediaRepository(context)
            fun findPublished(): LocalMediaTake {
                var cursor: LocalMediaCursor? = null
                var pages = 0
                do {
                    check(++pages <= 100)
                    val page = catalog.page(GallerySettings(), take.video.recoveryGroup.id, cursor)
                    page.takes.singleOrNull { it.primary.uri == saved.first.toString() }?.let { return it }
                    cursor = page.next
                } while (cursor != null)
                error("Published take absent from catalog")
            }
            val selected = findPublished()
            assertEquals(saved.first.toString(), selected.primary.uri)
            assertEquals(LocalMediaRelationStatus.DECLARED, selected.relationStatus)
            assertEquals(take.artifacts.map { it.uri }.toSet(), (selected.originals + selected.metadata).map { it.uri }.toSet())
            review(selected)
            assertEquals(preparedHashes, verifyRows(take.artifacts, pending = 0))
            assertEquals(selected, findPublished())
        } else for (artifact in take.artifacts) {
            val extension = when (artifact.role) {
                CaptureArtifactRole.VIDEO -> ".mp4"
                CaptureArtifactRole.AUDIO -> if (format == "FLAC") ".flac" else ".wav"
                else -> ".json"
            }
            File(context.cacheDir, "prepared-bundle-$id-${artifact.role.name.lowercase()}$extension").writeBytes(bytes(artifact))
        }
        android.util.Log.i("PreparedBundleProbe", "case=$id artifacts=${take.artifacts.size} allPendingBeforePublish=true allPublished=true identitiesUnchanged=true bytesUnchanged=true registrationFailures=${result.registrationFailures.size} captureSaved=true queueFault=$queueFailure queueSealed=${queueFailure != "sql"} frozenAdmission=true")
    }

    private fun failedPublication(format: String) = withTake(format) { take ->
        var deletionInjected = false
        var audioPublished = false
        var abortedCalls = 0
        var publishedCalls = 0
        val failure = runCatching {
            finalizePreparedRecordingTake<PreparedVideoOutput, PreparedAudioSidecar, Uri, AudioSidecarRecordingResult>(
                success = true,
                prepareAudio = { take.prepareAudio() },
                prepareVideo = { take.prepareVideo(true) },
                publishAudio = {
                    requireNotNull(take.audio!!.publishPrepared()).also {
                        assertEquals(0, row(take.artifacts.single { it.role == CaptureArtifactRole.AUDIO }).pending)
                        audioPublished = true
                    }
                },
                publishVideo = { take.video.publishPrepared() },
                discardAudio = { take.audio!!.discard() },
                discardVideo = { take.video.finish(false) },
                onPrepared = { _, _ ->
                    assertEquals(4, take.artifacts.size)
                    verifyRows(take.artifacts, pending = 1)
                    assertEquals(1, resolver.delete(take.video.uri, null, null))
                    deletionInjected = true
                },
                onPublished = { _, _ -> publishedCalls++ },
                onAborted = { abortedCalls++ },
            )
        }.exceptionOrNull()
        assertNotNull("A deleted prepared video must fail actual MediaStore publication", failure)
        assertTrue("Expected the precise provider-deletion fault, not a callback assertion", deletionInjected)
        assertTrue("Compensation must revoke audio that already reached publication", audioPublished)
        assertEquals(0, publishedCalls); assertEquals(1, abortedCalls)
        assertTrue("Registration must not hide a failing assertion: ${failure?.suppressed?.toList()}", failure!!.suppressed.isEmpty())
        assertAllAbsent(take.artifacts)
        assertNull(take.video.publishPrepared()); assertNull(take.audio!!.publishPrepared())
        assertNull(take.video.finish(true)); assertNull(take.audio!!.finish(true))
        assertAllAbsent(take.artifacts)
        android.util.Log.i("PreparedBundleProbe", "case=$format-provider-failure deletedPreparedVideo=true publishedAudioCompensated=true audioAndBothMetadataAbsent=true abortedOnce=true latePublish=null failure=${failure.javaClass.simpleName}")
    }

    private fun abortPrepared(format: String) = withTake(format) { take ->
        val audio = requireNotNull(take.prepareAudio())
        requireNotNull(take.prepareVideo(true))
        assertEquals(4, take.artifacts.size)
        verifyRows(take.artifacts, pending = 1)
        assertEquals(2, audio.artifacts.size)
        assertNull(take.video.finish(false)); assertNull(take.audio!!.finish(false))
        assertAllAbsent(take.artifacts)
        assertNull(take.video.prepareCompletion("{}", geometry, true))
        assertNull(take.audio!!.prepareCompletion())
        assertNull(take.video.publishPrepared()); assertNull(take.audio!!.publishPrepared())
        assertNull(take.video.finish(true)); assertNull(take.audio!!.finish(true))
        assertThrows(IllegalStateException::class.java) { take.audio!!.start() }
        assertAllAbsent(take.artifacts)
        android.util.Log.i("PreparedBundleProbe", "case=$format-abort preparedArtifacts=4 abortedBeforePublish=true allRowsAbsent=true latePrepare=null latePublish=null duplicateStartRejected=true")
    }

    private fun withTake(format: String?, queueEnabled: Boolean = false, queueFailure: String? = null, duringRecording: ((Take) -> Unit)? = null, productionSlate: ProductionSlateSettings? = null,captureNames:CaptureNameSnapshot?=null, block: (Take) -> Unit) {
        if (format != null) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val video = VideoOutput.create(context, productionSlate,captureNames)
        var audio: AudioSidecarRecorder? = null
        val take = Take(video)
        try {
            // Admission precedes native recording; the finalizer never invents consent.
            if (queueEnabled) take.queue = QueueFixture(queueFailure)
            val settings = CameraSettings(audioBitDepth = AudioBitDepth.PCM_16, audioSampleRateHz = 48000, audioChannels = 1,
                productionSlate = productionSlate ?: ProductionSlateSettings(),
                audioSource = AudioSourceSelection.MIC, automaticGainControlEnabled = false,
                noiseSuppressorEnabled = false, acousticEchoCancelerEnabled = false)
            audio = when (format) {
                "WAV" -> WavAudioSidecarRecorder.create(context, video.displayName, settings, recoveryGroup = video.recoveryGroup)
                "FLAC" -> FlacAudioSidecarRecorder.create(context, video.displayName, settings, recoveryGroup = video.recoveryGroup)
                else -> null
            }
            take.audio = audio
            recordClip(take, duringRecording)
            take.queue?.changeSettingsBeforePublication()
            assertNull("Video publication requires preparation", video.publishPrepared())
            audio?.let {
                assertNull("Audio publication requires preparation", it.publishPrepared())
                assertThrows(IllegalStateException::class.java) { it.start() }
            }
            block(take)
        } finally {
            // Every URI here belongs to this take. Never delete another test or user capture.
            var cleanupFailure: Throwable? = null
            fun attempt(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = failure else if (first !== failure) first.addSuppressed(failure)
                }
            }
            attempt { audio?.discard() }
            attempt { video.finish(false) }
            val owned = take.artifacts + PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, video.uri.toString(), video.displayName)
            for (artifact in owned.distinctBy { it.uri }) attempt { deleteRemainingOwnedRow(artifact) }
            attempt { take.queue?.close() }
            cleanupFailure?.let { throw it }
        }
    }

    private inner class Take(val video: VideoOutput) {
        var audio: AudioSidecarRecorder? = null
        var queue: QueueFixture? = null
        val artifacts = mutableListOf<PreparedCaptureArtifact>()
        var preparedAudio: PreparedAudioSidecar? = null
        fun prepareAudio(): PreparedAudioSidecar? = audio?.prepareCompletion()?.also {
            preparedAudio = it
            artifacts.addAll(it.artifacts.filterNot { candidate -> artifacts.any { old -> old.uri == candidate.uri } })
        }
        fun prepareVideo(metadata: Boolean, catalogMetadata: Boolean = false): PreparedVideoOutput? {
            val json = if (metadata) JSONObject().put("schema", if (catalogMetadata) "opencinecam.recording.v1" else "prepared-bundle-device-v1")
                .put("audioFrames", preparedAudio?.result?.frames ?: JSONObject.NULL).apply {
                    // The old ownership fixture deliberately used a private schema; gallery review
                    // requires a declared relationship with actual URI and bundle identity.
                    if (catalogMetadata) { put("videoUri", video.uri.toString()); put("bundleId", video.recoveryGroup.id) }
                }.toString() else null
            return video.prepareCompletion(json, geometry, timingSidecar = true)?.also {
                artifacts.addAll(it.artifacts.filterNot { candidate -> artifacts.any { old -> old.uri == candidate.uri } })
            }
        }
    }

    /** Unique private enrollment/journal/settings files and a real SQL database per recorded take. */
    private inner class QueueFixture(private val failure: String?) : AutoCloseable {
        val id = UUID.randomUUID().toString()
        private val directory = File(context.cacheDir, "prepared-codec-queue-$id").apply { check(mkdirs()) }
        private val database = "prepared-codec-queue-$id.db"
        private val journalDirectory = File(directory, "journal")
        private val enrollmentDirectory = File(directory, "enrollment")
        private val settings = WebDavQueueSettings(File(directory, "settings.json"))
        private val admitted = settings.save("https://first.example/recorded/", true, false)
        private val binding = CaptureTransferEnrollment(id, requireNotNull(admitted.activeEndpointId), 0L, admitted.revision)
        private val enrollment = CaptureTransferEnrollmentFile(enrollmentDirectory)
        private val journal = CapturePublicationJournal(journalDirectory, id)
        private val db = WebDavSqliteOutbox(context, database)
        private val probe = MediaStorePreparedArtifactProbe(resolver)
        private val bridge = bridge(db)
        private var staged: WebDavOutboxBundle? = null
        private var sealed: WebDavOutboxBundle? = null

        init {
            try {
                assertEquals(binding, enrollment.enroll(binding))
                assertNull(journal.load())
                assertTrue(db.list().isEmpty()) // Create the actual schema before installing a fault.
                if (failure == "journal") journalDirectory.writeText("preserve codec journal obstruction")
                if (failure == "sql") sql { database ->
                    database.execSQL("CREATE TRIGGER reject_codec_bundle BEFORE INSERT ON bundles BEGIN SELECT RAISE(ABORT, 'codec queue stage blocked'); END")
                }
            } catch (problem: Throwable) {
                try { close() } catch (cleanup: Throwable) { problem.addSuppressed(cleanup) }
                throw problem
            }
        }

        fun changeSettingsBeforePublication() {
            val changed = settings.save("https://second.example/later/", true, true)
            assertNotEquals(admitted.activeEndpointId, changed.activeEndpointId)
            assertTrue(changed.revision > admitted.revision)
            assertEquals(admitted.activeEndpoint, changed.endpoints.single { it.id == binding.endpointId })
            assertEquals(binding, CaptureTransferEnrollmentFile(enrollmentDirectory).load(id))
        }

        fun prepared(artifacts: List<PreparedCaptureArtifact>) {
            val problem = try { bridge.onPrepared(artifacts); null } catch (error: CapturePublicationOutboxFailure) { error }
            if (failure != "journal") {
                val receipt = requireNotNull(journal.load())
                assertEquals(CapturePublicationState.PREPARED, receipt.state)
                assertEquals(artifacts.toSet(), receipt.artifacts.toSet())
            }
            staged = db.load(id)
            if (failure == "sql") assertNull(staged)
            else {
                verifyBundle(requireNotNull(staged), artifacts, published = false)
                assertEquals(CaptureOutboxRegistrationStatus.STAGED, bridge.lastRegistration?.status)
            }
            problem?.let { throw it }
        }

        fun published(artifacts: List<PreparedCaptureArtifact>) {
            // Even all real rows being published must not implicitly seal the staged bundle.
            assertEquals(staged, db.load(id))
            val problem = try { bridge.onPublished(artifacts); null } catch (error: CapturePublicationOutboxFailure) { error }
            sealed = db.load(id)
            if (failure == "sql") assertNull(sealed)
            else {
                verifyBundle(requireNotNull(sealed), artifacts, published = true)
                assertEquals(requireNotNull(staged).artifacts.map { it.spec }.toSet(), requireNotNull(sealed).artifacts.map { it.spec }.toSet())
                assertEquals(CaptureOutboxRegistrationStatus.REGISTERED, bridge.lastRegistration?.status)
            }
            problem?.let { throw it }
        }

        fun verifyFailures(failures: List<Throwable>) {
            assertEquals("Only the two real bookkeeping callbacks may fail: $failures", 2, failures.size)
            val expected = if (failure == "journal") listOf(
                setOf(CaptureOutboxBridgeOperation.JOURNAL_PREPARE, CaptureOutboxBridgeOperation.JOURNAL_READ),
                setOf(CaptureOutboxBridgeOperation.JOURNAL_PUBLISH, CaptureOutboxBridgeOperation.JOURNAL_READ),
            ) else listOf(setOf(CaptureOutboxBridgeOperation.OUTBOX_PREPARE), setOf(CaptureOutboxBridgeOperation.OUTBOX_PUBLISH))
            failures.forEachIndexed { index, error ->
                assertTrue("An assertion must not be mistaken for optional I/O: $error", error is CapturePublicationOutboxFailure)
                val problems = (error as CapturePublicationOutboxFailure).problems
                assertEquals(expected[index], problems.map { it.operation }.toSet())
                assertEquals(expected[index].size, problems.size)
                problems.forEach { problem ->
                    if (failure == "journal") assertTrue(problem.failure.toString(), problem.failure is java.io.IOException)
                    else {
                        assertTrue(problem.failure.toString(), problem.failure is android.database.sqlite.SQLiteException)
                        assertTrue(problem.failure.message.orEmpty().contains("codec queue stage blocked"))
                    }
                }
            }
        }

        fun verifyCompleted(artifacts: List<PreparedCaptureArtifact>) {
            assertEquals(binding, CaptureTransferEnrollmentFile(enrollmentDirectory).load(id))
            assertNotEquals(binding.endpointId, settings.snapshotForAdmission().preferences.activeEndpointId)
            if (failure == "journal") assertEquals("preserve codec journal obstruction", journalDirectory.readText())
            else {
                val receipt = requireNotNull(CapturePublicationJournal(journalDirectory, id).load())
                assertEquals(CapturePublicationState.COMMITTED, receipt.state)
                assertEquals(artifacts.toSet(), receipt.artifacts.toSet())
            }
            WebDavSqliteOutbox(context, database).use { reopened ->
                assertEquals(sealed, reopened.load(id))
                if (failure == "sql") {
                    assertTrue(reopened.list().isEmpty())
                    assertEquals(CaptureOutboxRegistrationStatus.BLOCKED, bridge.lastRegistration?.status)
                } else {
                    assertEquals(listOf(id), reopened.list().map { it.id })
                    verifyBundle(requireNotNull(reopened.load(id)), artifacts, published = true)
                    if (failure == null) repeat(2) {
                        assertEquals(CaptureOutboxRegistrationStatus.ALREADY_REGISTERED, bridge(reopened).registerCommitted().status)
                        assertEquals(sealed, reopened.load(id))
                    }
                }
            }
            assertEquals(failure != null, bridge.issues.isNotEmpty())
            android.util.Log.i("PreparedBundleQueueProbe", "bundle=$id roles=${artifacts.map { it.role }} endpoint=${binding.endpointId} consentRevision=${binding.consentRevision} endpointRevision=${binding.endpointRevision} reopened=true noUploadOrHashClaim=true fault=$failure")
        }

        private fun verifyBundle(bundle: WebDavOutboxBundle, artifacts: List<PreparedCaptureArtifact>, published: Boolean) {
            assertEquals(id, bundle.id)
            assertEquals(binding.endpointId, bundle.endpointId)
            assertEquals(binding.endpointRevision, bundle.endpointRevision)
            assertEquals(published, bundle.sealed)
            assertEquals(if (published) WebDavBundleState.QUEUED else WebDavBundleState.AWAITING_PUBLICATION, bundle.state)
            assertEquals(artifacts.size, bundle.artifacts.size)
            assertEquals(artifacts.map { it.role.name }.toSet(), bundle.artifacts.map { it.spec.role.name }.toSet())
            for (artifact in artifacts) {
                val actual = bundle.artifacts.single { it.spec.role.name == artifact.role.name }
                val observed = probe.inspect(artifact, pending = !published)
                assertEquals(CapturePublicationOutboxBridge.artifactId(id, artifact.role), actual.spec.id)
                assertEquals(artifact.uri, actual.spec.sourceUri)
                assertEquals(artifact.displayName, actual.spec.sourceName)
                assertEquals(artifact.displayName, actual.spec.remoteName)
                assertEquals(bytes(artifact).size.toLong(), actual.spec.sizeBytes)
                assertEquals(observed.sizeBytes, actual.spec.sizeBytes)
                if (published) assertEquals(observed.modifiedSeconds, actual.modifiedSeconds) else assertNull(actual.modifiedSeconds)
                assertNull(actual.sha256); assertNull(actual.attempt); assertNull(actual.lastAdmission)
                assertFalse(actual.remoteMayExist)
                assertEquals(WebDavArtifactState.QUEUED, actual.state)
            }
        }

        fun verifyWorker(caseId: String, artifacts: List<PreparedCaptureArtifact>) {
            if (failure != null) return // Real optional-bookkeeping failure cases retain prior evidence.
            val report = PreparedBundleWorkerProbe.verify(context, settings, database, id, artifacts, ::bytes)
            File(context.cacheDir, "prepared-bundle-$caseId-worker.json").writeText(report)
        }

        private fun bridge(store: WebDavOutboxStore) = CapturePublicationOutboxBridge(id, journal,
            { requested -> assertEquals(id, requested); journal.load() }, enrollment, store, probe)

        private fun sql(action: (android.database.sqlite.SQLiteDatabase) -> Unit) = android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(database).absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        ).use(action)

        override fun close() {
            try { db.close() } finally {
                try { assertTrue("Own queue database cleanup", context.deleteDatabase(database)) }
                finally { assertTrue("Own queue private-file cleanup", directory.deleteRecursively()) }
            }
        }
    }

    private fun recordClip(take: Take, duringRecording: ((Take) -> Unit)? = null) {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val accepted = AtomicBoolean()
        val saved = AtomicBoolean()
        val callbackFailure = AtomicReference<Throwable?>()
        val lastEncoded = AtomicReference<EncodedRecordingProgress?>()
        val evidence = AtomicReference<OpenCineLogRecordingEvidence?>()
        SubjectPreviewGpuTest.Fixture(embeddedAudio = true).use { fixture ->
            val guard = take.video.recoveryGroup.retainNativeWriter()
            try {
                accepted.set(fixture.pipeline.startRecording(take.video.descriptor, 2_000_000, geometry,
                    onStarted = {
                        try { take.audio?.start() } catch (failure: Throwable) { callbackFailure.set(failure) }
                        finally { started.countDown() }
                    },
                    onEncodedProgress = { lastEncoded.set(it) },
                    onStopped = { success, result -> evidence.set(result); saved.set(success); stopped.countDown() },
                ))
                assertTrue("Real AVC recording must be admitted", accepted.get())
                assertTrue(started.await(5, TimeUnit.SECONDS))
                callbackFailure.get()?.let { throw it }
                // Source swap alone is not consumption: SurfaceTexture coalesces queued inputs.
                // Wait on the GL owner after each consumed timestamp, not on early codec output
                // (which may legitimately require more inputs/lookahead before EOS).
                val glHandler = OpenCineLogGpuPipeline::class.java.getDeclaredField("handler")
                    .apply { isAccessible = true }.get(fixture.pipeline) as android.os.Handler
                val texture = OpenCineLogGpuPipeline::class.java.getDeclaredField("surfaceTexture")
                    .apply { isAccessible = true }.get(fixture.pipeline) as android.graphics.SurfaceTexture
                var previousTimestampNs = Long.MIN_VALUE
                repeat(12) {
                    val timestampNs = SystemClock.elapsedRealtimeNanos()
                    assertTrue(timestampNs > previousTimestampNs)
                    previousTimestampNs = timestampNs
                    fixture.sourceFrame(timestampNs)
                    awaitSourceConsumption(fixture, glHandler, texture, timestampNs)
                    if (it == 3) duringRecording?.invoke(take)
                }
                assertTrue(fixture.pipeline.stopRecording())
                assertTrue(stopped.await(10, TimeUnit.SECONDS))
                assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
                assertTrue("Real AVC take must finalize", saved.get())
                assertEquals(12L, requireNotNull(evidence.get()).encodedFrames)
                assertEquals(12L, requireNotNull(lastEncoded.get()).frameCount)
            } finally {
                fixture.pipeline.stopRecording()
                if (accepted.get()) assertTrue("Native writer must retire before output cleanup", stopped.await(10, TimeUnit.SECONDS))
                fixture.pipeline.recordingFileRetirement().get(10, TimeUnit.SECONDS)
                guard.close()
            }
        }
    }

    private fun awaitSourceConsumption(fixture: SubjectPreviewGpuTest.Fixture, handler: android.os.Handler,
        texture: android.graphics.SurfaceTexture, timestampNs: Long) {
        val completion = java.util.concurrent.CompletableFuture<Unit>()
        val deadlineMs = SystemClock.elapsedRealtime() + 10_000
        val probe = object : Runnable {
            override fun run() {
                if (completion.isDone) return
                try {
                    check(fixture.failures.isEmpty()) { fixture.failures.toString() }
                    val observed = texture.timestamp
                    if (observed == timestampNs) completion.complete(Unit)
                    else {
                        check(observed < timestampNs) { "Unexpected source timestamp: $observed > $timestampNs" }
                        check(SystemClock.elapsedRealtime() < deadlineMs) { "GL did not consume $timestampNs; observed=$observed" }
                        check(handler.postDelayed(this, 1)) { "GL consumption probe rejected" }
                    }
                } catch (failure: Throwable) { completion.completeExceptionally(failure) }
            }
        }
        check(handler.post(probe))
        try { completion.get(11, TimeUnit.SECONDS) }
        finally { completion.cancel(false); handler.removeCallbacks(probe) }
    }

    private data class Row(val displayName: String, val pending: Int)

    @Suppress("DEPRECATION") // Explicit pending visibility also works on min-API29 providers.
    private fun collection(role: CaptureArtifactRole): Uri = MediaStore.setIncludePending(when (role) {
        CaptureArtifactRole.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        CaptureArtifactRole.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
    })

    private fun row(artifact: PreparedCaptureArtifact): Row = requireNotNull(resolver.query(
        collection(artifact.role), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING),
        "${MediaStore.MediaColumns._ID} = ?", arrayOf(ContentUris.parseId(Uri.parse(artifact.uri)).toString()), null,
    )).use {
        assertTrue("Owned row must exist: $artifact", it.moveToFirst())
        Row(it.getString(0), it.getInt(1))
    }

    private fun bytes(artifact: PreparedCaptureArtifact): ByteArray = requireNotNull(resolver.openInputStream(Uri.parse(artifact.uri))).use { it.readBytes() }

    private fun verifyRows(artifacts: List<PreparedCaptureArtifact>, pending: Int): Map<String, String> = artifacts.associate { artifact ->
        val row = row(artifact)
        assertEquals(artifact.displayName, row.displayName)
        assertTrue(row.displayName.isNotBlank())
        assertEquals("Wrong publication state for $artifact", pending, row.pending)
        val content = bytes(artifact)
        assertTrue("Prepared bytes must be complete and nonempty: $artifact", content.isNotEmpty())
        artifact.uri to MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    }

    private fun assertPreparedSlate(video: PreparedVideoOutput, audio: PreparedAudioSidecar?, slate: ProductionSlateSettings) {
        fun parsed(artifact: PreparedCaptureArtifact): ProductionSlateSettings? = parseProductionSlateJson(
            Json.parseToJsonElement(bytes(artifact).toString(Charsets.UTF_8)).jsonObject.getValue("productionSlate").jsonObject)
        val metadata = video.artifacts.single { it.role == CaptureArtifactRole.VIDEO_METADATA }
        assertEquals(slate, parsed(metadata))
        val json = JSONObject(bytes(metadata).toString(Charsets.UTF_8))
        assertEquals(video.uri.toString(), json.getString("videoUri"))
        audio?.let {
            assertEquals(slate, parsed(it.artifacts.single { artifact -> artifact.role == CaptureArtifactRole.AUDIO_METADATA }))
        }
        assertEquals(2, video.artifacts.size)
        assertEquals(if (audio == null) 2 else 4, (video.artifacts + audio?.artifacts.orEmpty()).size)
    }

    private fun verifyAudioMetadata(audio: PreparedAudioSidecar?) {
        if (audio == null) return
        val artifact = audio.artifacts.single { it.role == CaptureArtifactRole.AUDIO_METADATA }
        val json = JSONObject(String(bytes(artifact), Charsets.UTF_8))
        assertEquals(audio.result.frames, json.getLong("frames"))
        assertEquals(audio.result.uri.toString(), json.getString("audioUri"))
        assertEquals(audio.result.displayName, json.getString("file"))
        assertEquals(audio.result.container, json.getString("container"))
        assertEquals(audio.result.frames, json.getJSONObject("captureTiming").getLong("writtenFrames"))
        assertTrue("Real AudioRecord must produce PCM", audio.result.frames > 0)
    }

    private fun deleteRemainingOwnedRow(artifact: PreparedCaptureArtifact) {
        // A successful discard already removed some identities. API30 may revoke URI access
        // immediately, so inspect the owned collection/ID instead of deleting a stale URI twice.
        val exists = requireNotNull(resolver.query(
            collection(artifact.role), arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns._ID} = ?",
            arrayOf(ContentUris.parseId(Uri.parse(artifact.uri)).toString()), null,
        )).use { it.moveToFirst() }
        if (exists) check(resolver.delete(Uri.parse(artifact.uri), null, null) == 1) {
            "Cleanup failed for an existing owned row: $artifact"
        }
    }

    private fun assertAllAbsent(artifacts: List<PreparedCaptureArtifact>) {
        for (artifact in artifacts) requireNotNull(resolver.query(
            collection(artifact.role), arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns._ID} = ?",
            arrayOf(ContentUris.parseId(Uri.parse(artifact.uri)).toString()), null,
        )).use { assertEquals("Compensated row survived: $artifact", 0, it.count) }
    }

    @Suppress("DEPRECATION") // Count prepared rows as well as published ones on API29/30.
    private fun ownedRows(): Set<String> {
        val result = mutableSetOf<String>()
        for (collection in listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaStore.Downloads.EXTERNAL_CONTENT_URI)) {
            requireNotNull(resolver.query(MediaStore.setIncludePending(collection), arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?", arrayOf(context.packageName), null)).use { cursor ->
                while (cursor.moveToNext()) result += ContentUris.withAppendedId(collection, cursor.getLong(0)).toString()
            }
        }
        return result
    }

    private fun verifyVideoDecodes(uri: Uri) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(track)
            val pts = mutableListOf<Long>()
            while (extractor.sampleTime >= 0) { pts += extractor.sampleTime; extractor.advance() }
            assertEquals("Expected exactly 12 actual muxed samples: $pts", 12, pts.size)
            assertTrue(pts.zipWithNext().all { (left, right) -> right > left })
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val decoded = requireNotNull(retriever.getFrameAtTime(0))
            try { assertEquals(128, decoded.width); assertEquals(96, decoded.height) } finally { decoded.recycle() }
        } finally { retriever.release() }
    }
}
