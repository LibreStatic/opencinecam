/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.transfers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.FakeExternalStorageProvider
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import com.librestatic.opencinecam.storage.SafRecordingRows
import com.librestatic.opencinecam.storage.ShadowDocumentsProviderQuery
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * PLAN-072 Delivery 2: a drive take's artifacts in the WebDAV outbox. The folder's marker is the drive's IS_PENDING,
 * a document gone from a drive that answers is MISSING, and a drive that does not answer is the transient
 * [WebDavSourceDriveAbsent]; the outbox persists drive document URIs like MediaStore items.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowDocumentsProviderQuery::class])
class SafPreparedArtifactProbeTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var drive: FakeExternalStorageProvider
    private lateinit var tree: Uri

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        drive = FakeExternalStorageProvider.install(temp.newFolder("drive"))
        check(drive.file("Takes/OpenCineCam/A001").mkdirs())
        tree = drive.tree("Takes")
        FakeExternalStorageProvider.grant(context, tree)
        drive.file("Takes/OpenCineCam/A001/A001.mp4").writeText("video bytes")
        drive.file("Takes/OpenCineCam/A001/A001.recording.json").writeText("{}")
    }

    private fun docUri(path: String) =
        DocumentsContract.buildDocumentUriUsingTree(tree, "${FakeExternalStorageProvider.VOLUME}:Takes/$path").toString()
    private val video get() = PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, docUri("OpenCineCam/A001/A001.mp4"), "A001.mp4")
    private val marker get() = File(drive.file("Takes/OpenCineCam/A001"), SafRecordingRows.MARKER)
    private fun observe(artifact: PreparedCaptureArtifact = video, pending: Boolean) =
        safPreparedObservation(context.contentResolver, artifact, pending)

    @Test fun theMarkerIsTheDrivesPendingFlag() {
        check(marker.createNewFile())
        assertEquals(SafPreparedObservation("A001.mp4", 11, drive.file("Takes/OpenCineCam/A001/A001.mp4").lastModified(), "video/mp4"),
            observe(pending = true))
        assertThrows(IllegalStateException::class.java) { observe(pending = false) }
        check(marker.delete())
        assertEquals(11, observe(pending = false).sizeBytes)
        assertThrows(IllegalStateException::class.java) { observe(pending = true) }
        val sidecar = PreparedCaptureArtifact(CaptureArtifactRole.CLIP_METADATA, docUri("OpenCineCam/A001/A001.recording.json"), "A001.recording.json")
        assertEquals("application/json", observe(sidecar, pending = false).mimeType)
    }

    @Test fun aMissingDocumentIsMissingButAnUnpluggedDriveIsTransient() {
        check(drive.file("Takes/OpenCineCam/A001/A001.mp4").delete())
        val missing = runCatching { observe(pending = false) }.exceptionOrNull()
        assertTrue(missing.toString(), missing is FileNotFoundException && missing !is WebDavSourceDriveAbsent)
        drive.mounted = false
        val absent = runCatching { observe(pending = false) }.exceptionOrNull()
        assertTrue(absent.toString(), absent is WebDavSourceDriveAbsent)
        // Through the hasher the absent drive is Waiting, never a persisted source failure.
        val factory = WebDavArtifactSourceFactory { WebDavMediaStoreArtifactSource(context.contentResolver).source(it) }
        val spec = WebDavArtifactSpec("00000000-0000-0000-0000-000000000002", WebDavArtifactRole.VIDEO, video.uri, "A001.mp4", 11)
        val control = WebDavUploadControl(WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))
        val attempt = requireNotNull(control.enter().attempt)
        try {
            assertEquals(WebDavHashResult.Waiting, DefaultWebDavArtifactHasher(factory).hash(WebDavOutboxArtifact(spec, modifiedSeconds = 7), WebDavOperationScope(attempt)))
        } finally { control.leave(attempt) }
    }

    @Test fun onlyCanonicalDocumentsOfTheirOwnNameAndRoleAreObserved() {
        assertThrows(IllegalArgumentException::class.java) { observe(video.copy(displayName = "A002.mp4"), pending = false) }
        assertThrows(IllegalArgumentException::class.java) { observe(video.copy(uri = video.uri.replace("A001.mp4", "..%2FA001.mp4")), pending = false) }
        assertThrows(IllegalStateException::class.java) { observe(video.copy(role = CaptureArtifactRole.AUDIO), pending = false) }
    }

    @Test fun aDriveTakeRoundTripsThroughThePersistedOutbox() {
        val video = WebDavArtifactSpec("00000000-0000-0000-0000-000000000002", WebDavArtifactRole.VIDEO, video.uri, "A001.mp4", 11)
        val sidecar = WebDavArtifactSpec("00000000-0000-0000-0000-000000000003", WebDavArtifactRole.CLIP_METADATA,
            docUri("OpenCineCam/A001/A001.recording.json"), "A001.recording.json", 2)
        val plan = WebDavBundlePlan("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000009", 0, listOf(video, sidecar))
        WebDavSqliteOutbox(context, "drive-outbox.db").use { it.stage(plan) }
        val loaded = WebDavSqliteOutbox(context, "drive-outbox.db").use { requireNotNull(it.load(plan.id)) }
        assertEquals(plan.artifacts.toSet(), loaded.artifacts.map { it.spec }.toSet())
        WebDavSourceFailure(sidecar.sourceUri, WebDavSourceFailureReason.MISSING) // A drive source can fail like a phone one.
        // A document of another tree, or an alias of one, is not an outbox source.
        assertThrows(IllegalArgumentException::class.java) { video.copy(sourceUri = video.sourceUri.replace("A001%2FA001.mp4", "A001%2F.%2FA001.mp4")) }
        assertThrows(IllegalArgumentException::class.java) { video.copy(sourceUri = video.sourceUri.replace("tree/FFF1-7571%3ATakes", "tree/FFF1-7571%3AOther")) }
    }
}
