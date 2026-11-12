/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.transfers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.share.openShareFile
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.DriveDescriptorWatch
import com.librestatic.opencinecam.storage.DriveDescriptors
import com.librestatic.opencinecam.storage.FakeExternalStorageProvider
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import com.librestatic.opencinecam.storage.SafRecordingRows
import com.librestatic.opencinecam.storage.ShadowDocumentsProviderQuery
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

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

    @Test fun aSharedFileIsRegisteredWhileOpenAndClosedWhenTheDriveLeaves() {
        val volume = FakeExternalStorageProvider.VOLUME
        val whole = openShareFile(context.contentResolver, Uri.parse(video.uri))
        assertEquals(1, DriveDescriptors.registered(volume))
        // The application installs the process's own volume watch, and the first descriptor on a drive starts it.
        assertTrue(DriveDescriptorWatch.active)
        assertEquals(11, whole.channel.size())
        whole.close()
        assertEquals(0, DriveDescriptors.registered(volume))
        val cut = openShareFile(context.contentResolver, Uri.parse(video.uri))
        assertEquals(1, DriveDescriptors.releaseVolume(volume))
        assertFalse(cut.channel.isOpen)
        cut.close()
        assertEquals(0, DriveDescriptors.registered(volume))
    }

    @Test @Config(shadows = [ShadowDocumentsProviderQuery::class, ShadowFstatProbe::class])
    fun theProbesDescriptorIsRegisteredWhileItInspects() {
        val volume = FakeExternalStorageProvider.VOLUME
        val seen = ArrayList<Int>()
        ShadowFstatProbe.onFstat = { seen += DriveDescriptors.registered(volume) }
        val failure = try {
            runCatching { SafPreparedArtifactProbe(context.contentResolver).inspect(video, pending = false) }.exceptionOrNull()
        } finally { ShadowFstatProbe.onFstat = null }
        // The shadow ends the inspection at its first fstat, on the open descriptor.
        assertTrue(failure.toString(), failure is ErrnoException)
        assertEquals(listOf(1), seen)
        assertEquals(0, DriveDescriptors.registered(volume))
    }

    @Test @Config(shadows = [ShadowDocumentsProviderQuery::class, ShadowFstatProbe::class])
    fun aDriveReleasedDuringTheInspectionIsAnAbsentDrive() {
        val volume = FakeExternalStorageProvider.VOLUME
        ShadowFstatProbe.onFstat = { assertEquals(1, DriveDescriptors.releaseVolume(volume)) }
        val failure = try {
            runCatching { SafPreparedArtifactProbe(context.contentResolver).inspect(video, pending = false) }.exceptionOrNull()
        } finally { ShadowFstatProbe.onFstat = null }
        assertTrue(failure.toString(), failure is WebDavSourceDriveAbsent && failure.cause is ErrnoException)
        assertEquals(0, DriveDescriptors.registered(volume))
    }

    /** A stream of the drive's video, opened while fstat works; its close-time check then fails with an errno. */
    private fun closeWithAFailingCheck(beforeClose: () -> Unit): Throwable? {
        val spec = WebDavArtifactSpec("00000000-0000-0000-0000-000000000003", WebDavArtifactRole.VIDEO, video.uri, "A001.mp4", 11)
        ShadowFstatProbe.passThrough = true
        return try {
            val stream = WebDavMediaStoreArtifactSource(context.contentResolver).source(spec).open()
            ShadowFstatProbe.passThrough = false
            beforeClose()
            runCatching { stream.close() }.exceptionOrNull()
        } finally { ShadowFstatProbe.passThrough = false }
    }

    @Test @Config(shadows = [ShadowDocumentsProviderQuery::class, ShadowFstatProbe::class])
    fun aCloseTimeCheckThatFailsOnADriveThatNoLongerAnswersIsAnAbsentDrive() {
        // The drive left but its descriptors are not released yet: the errno is the drive, so the upload waits for it.
        val failure = closeWithAFailingCheck { drive.mounted = false }
        assertTrue(failure.toString(), failure is WebDavSourceDriveAbsent && failure.cause is ErrnoException)
        assertEquals(0, DriveDescriptors.registered(FakeExternalStorageProvider.VOLUME))
    }

    @Test @Config(shadows = [ShadowDocumentsProviderQuery::class, ShadowFstatProbe::class])
    fun aCloseTimeCheckThatFailsWhileTheDriveAnswersStaysAFailure() {
        val failure = closeWithAFailingCheck { }
        assertTrue(failure.toString(), failure is ErrnoException)
        assertEquals(0, DriveDescriptors.registered(FakeExternalStorageProvider.VOLUME))
    }

    @Test fun aCheckThatMeetsAReleasedDescriptorIsAnAbsentDrive() {
        val changed = WebDavArtifactContentChanged()
        assertNull(driveReleasedOr(null, released = false))
        assertSame(changed, driveReleasedOr(changed, released = false))
        // Released by DriveDescriptors: whatever the checks met on the closed descriptor is the drive leaving.
        val absent = driveReleasedOr(changed, released = true)
        assertTrue(absent.toString(), absent is WebDavSourceDriveAbsent && absent.cause === changed)
        val closed = IllegalStateException("Prepared artifact descriptor is closed")
        assertSame(closed, (driveReleasedOr(closed, released = true) as WebDavSourceDriveAbsent).cause)
        assertTrue(driveReleasedOr(null, released = true) is WebDavSourceDriveAbsent)
        val already = WebDavSourceDriveAbsent()
        assertSame(already, driveReleasedOr(already, released = true))
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

/** Runs [onFstat] on each `Os.fstat` and then fails it (while [passThrough] is set it answers as the test's video file instead), so a test sees the state while the probe holds its descriptor. */
@Implements(Os::class)
class ShadowFstatProbe {
    companion object {
        @Volatile var onFstat: (() -> Unit)? = null
        @Volatile var passThrough = false

        @JvmStatic @Implementation
        fun fstat(@Suppress("UNUSED_PARAMETER") descriptor: FileDescriptor): StructStat {
            // Robolectric's own fstat is not a regular file's: answer as the 11-byte video would.
            if (passThrough) return StructStat(1, 1, OsConstants.S_IFREG or 0x1A4, 1, 0, 0, 0, 11, 0, 0, 0, 4096, 1)
            onFstat?.invoke()
            throw ErrnoException("fstat", OsConstants.EBADF)
        }
    }
}
