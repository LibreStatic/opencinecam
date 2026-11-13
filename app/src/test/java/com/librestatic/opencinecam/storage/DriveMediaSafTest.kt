/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** PLAN-072 Delivery 2: listing and deleting drive takes against [FakeExternalStorageProvider]. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowDocumentsProviderQuery::class])
class DriveMediaSafTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var drive: FakeExternalStorageProvider
    private lateinit var tree: Uri
    private var destination: RecordingDestination = RecordingDestination.Phone
    private var granted = true
    private val bundle = "2b2a7bb1-5061-4379-86c1-cd7cbca97e42"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        drive = FakeExternalStorageProvider.install(temp.newFolder("drive"))
        check(drive.file("Takes/OpenCineCam/Stills").mkdirs())
        tree = drive.tree("Takes")
        FakeExternalStorageProvider.grant(context, tree)
        destination = RecordingDestination.UsbFolder(tree, FakeExternalStorageProvider.VOLUME)
    }

    private fun catalog(cache: DriveCatalogCache = DriveCatalogCache()) = DriveMediaCatalog(context.contentResolver, { destination },
        { granted }, { artifact -> MetadataDocument(artifact, context.contentResolver.openInputStream(artifact.uri.toUri())?.use { it.readBytes().decodeToString() }) },
        cache)

    private fun docUri(path: String) = DocumentsContract.buildDocumentUriUsingTree(tree, "${FakeExternalStorageProvider.VOLUME}:Takes/$path").toString()

    private fun put(path: String, text: String = "media", modified: Long? = null): File =
        drive.file("Takes/$path").also { it.parentFile!!.mkdirs(); it.writeText(text); modified?.let(it::setLastModified) }

    /** A committed recording: video plus its recording sidecar declaring [bundle]. */
    private fun recording(name: String, modified: Long, bundleId: String? = bundle): File {
        put("OpenCineCam/$name/$name.mp4")
        put("OpenCineCam/$name/$name.recording.json", buildJsonObject {
            put("schema", "opencinecam.recording.v1"); bundleId?.let { put("bundleId", it) }; put("videoUri", docUri("OpenCineCam/$name/$name.mp4"))
        }.toString())
        return drive.file("Takes/OpenCineCam/$name").also { it.setLastModified(modified) }
    }

    private fun takes(catalog: DriveMediaCatalog = catalog()) =
        catalog.entries(refresh = true).sortedWith(compareBy(drivePositionOrder(true)) { it.position }).mapNotNull { it.load()?.take }

    @Test fun listsCommittedTakeFoldersAndSkipsTheRest() {
        recording("A001", 3_000_000)
        recording("A002", 2_000_000, bundleId = null)
        recording("A003", 4_000_000).also { File(it, SafRecordingRows.MARKER).createNewFile() }
        put("OpenCineCam/A004/A004${RAW_VIDEO_EXTENSION}")
        drive.file("Takes/OpenCineCam/A004").setLastModified(500_000)
        put("OpenCineCam/A005/A005${RAW_VIDEO_EXTENSION}")
        File(drive.file("Takes/OpenCineCam/A005"), SafRecordingRows.MARKER).createNewFile()
        put("OpenCineCam/.occ-benchmark/x.mp4")
        put("OpenCineCam/Stills/S001/S001.jpg")
        put("OpenCineCam/Stills/.trash/S002.jpg")
        put("OpenCineCam/A001.mp4")
        drive.file("Takes/OpenCineCam/Stills/S001").setLastModified(1_000_000)
        val found = takes()
        assertEquals(listOf("take:$bundle", "drive:${FakeExternalStorageProvider.VOLUME}:Takes/OpenCineCam/A002",
            "drive:${FakeExternalStorageProvider.VOLUME}:Takes/OpenCineCam/Stills/S001",
            "raw:${docUri("OpenCineCam/A004/A004$RAW_VIDEO_EXTENSION")}"), found.map { it.id })
        val take = found.first()
        assertEquals(LocalMediaRelationStatus.DECLARED, take.relationStatus)
        assertEquals(docUri("OpenCineCam/A001/A001.mp4"), take.primary.uri)
        assertEquals(listOf("A001.recording.json"), take.metadata.map { it.name })
        assertTrue(take.onDrive)
        val still = found[2]
        assertEquals(LocalMediaKind.PHOTO, still.kind)
        assertEquals(LocalMediaRelationStatus.MISSING_METADATA, still.relationStatus)
        // A folder whose only media is an `.ocraw` is a RAW take, as the phone's RAW rows are (the in-progress A005 is not).
        val raw = found.last()
        assertEquals(LocalMediaKind.RAW_VIDEO, raw.kind)
        assertEquals(rawVideoTake(raw.primary), raw)
        assertEquals(RawVideoOutput.MIME_TYPE, raw.primary.mimeType)
        assertEquals("A004$RAW_VIDEO_EXTENSION", raw.primary.name)
    }

    @Test fun anOcrawBesideAVideoIsIgnored() {
        recording("A001", 3_000_000)
        put("OpenCineCam/A001/A001$RAW_VIDEO_EXTENSION")
        val take = takes().single()
        assertEquals(LocalMediaKind.VIDEO, take.kind)
        assertEquals(listOf("A001.mp4"), take.originals.map { it.name })
    }

    @Test fun aShareSnapshotRereadsTheTakeFolder() {
        recording("A001", 3_000_000)
        val take = takes().single()
        val snapshot = catalog().shareSnapshot(take)
        assertEquals(take, snapshot.take)
        assertEquals(take.metadata, snapshot.documents.map { it.artifact })
        assertNotNull(snapshot.documents.single().text)
        // The plan rebuilds the drive namespace and re-runs the catalog's declaration check on it.
        val plan = mediaSharePlan(snapshot, com.librestatic.opencinecam.MediaSharingSettings(
            metadata = com.librestatic.opencinecam.MediaShareMetadata.TECHNICAL))
        assertEquals(take.originals, plan.originals)
        assertEquals(listOf("take.technical.json"), plan.generated.map { it.name })
        val originalsOnly = mediaSharePlan(snapshot, com.librestatic.opencinecam.MediaSharingSettings(
            content = com.librestatic.opencinecam.MediaShareContent.ORIGINALS_ONLY))
        assertEquals(listOf("A001.mp4"), originalsOnly.originals.map { it.name })
    }

    @Test fun aShareSnapshotOfAFolderKeyedTakeAndARawTake() {
        recording("A002", 2_000_000, bundleId = null)
        put("OpenCineCam/A004/A004$RAW_VIDEO_EXTENSION")
        for (take in takes()) assertEquals(take, catalog().shareSnapshot(take).take)
        val keyed = takes().single { it.id.startsWith("drive:") }
        assertEquals(keyed.originals, mediaSharePlan(catalog().shareSnapshot(keyed), com.librestatic.opencinecam.MediaSharingSettings(
            content = com.librestatic.opencinecam.MediaShareContent.ORIGINALS_ONLY)).originals)
    }

    @Test fun aShareSnapshotRefusesAChangedTake() {
        val folder = recording("A001", 3_000_000)
        val take = takes().single()
        File(folder, "A001.mp4").writeText("media, longer now")
        assertTrue(runCatching { catalog().shareSnapshot(take) }.exceptionOrNull() is IllegalStateException)
        File(folder, "A001.mp4").delete()
        assertTrue(runCatching { catalog().shareSnapshot(take) }.exceptionOrNull() is IllegalStateException)
        assertFalse(runCatching { catalog().shareSnapshot(take) }.exceptionOrNull() is DriveNotConnectedException)
        folder.deleteRecursively()
        val gone = runCatching { catalog().shareSnapshot(take) }.exceptionOrNull()
        assertTrue(gone is IllegalStateException && gone !is DriveNotConnectedException)
    }

    @Test fun aTakeRewrittenInPlaceIsReadAgainAfterItsSnapshotFails() {
        val cache = DriveCatalogCache()
        val folder = recording("A001", 3_000_000)
        val take = takes(catalog(cache)).single()
        // Rewritten from a computer: the file changes, the folder's last change does not.
        File(folder, "A001.mp4").writeText("media, rewritten in place")
        folder.setLastModified(3_000_000)
        assertTrue(runCatching { catalog(cache).shareSnapshot(take) }.exceptionOrNull() is IllegalStateException)
        val fresh = takes(catalog(cache)).single()
        assertEquals("media, rewritten in place".length.toLong(), fresh.primary.sizeBytes)
        assertEquals(fresh, catalog(cache).shareSnapshot(fresh).take)
    }

    @Test fun aShareSnapshotWithTheDriveUnpluggedSaysSo() {
        recording("A001", 3_000_000)
        val take = takes().single()
        drive.mounted = false
        assertTrue(runCatching { catalog().shareSnapshot(take) }.exceptionOrNull() is DriveNotConnectedException)
        drive.mounted = true
        granted = false
        assertTrue(runCatching { catalog().shareSnapshot(take) }.exceptionOrNull() is DriveNotConnectedException)
        granted = true
        assertEquals(take, catalog().shareSnapshot(take).take)
        File(drive.file("Takes/OpenCineCam/A001"), SafRecordingRows.MARKER).createNewFile()
        assertTrue(runCatching { catalog().shareSnapshot(take) }.exceptionOrNull()?.message == "Selected take is not committed")
    }

    @Test fun contributesNothingUnmountedUnconfiguredOrWithoutTheGrant() {
        recording("A001", 3_000_000)
        assertEquals(1, takes().size)
        drive.mounted = false
        assertTrue(catalog().entries(refresh = true).isEmpty())
        drive.mounted = true
        granted = false
        assertTrue(catalog().entries(refresh = true).isEmpty())
        granted = true
        destination = RecordingDestination.Phone
        assertTrue(catalog().entries(refresh = true).isEmpty())
    }

    @Test fun aTakeReadWhileItsFolderWasForgottenIsNotKept() {
        recording("A001", 3_000_000)
        val read = requireNotNull(catalog().entries(refresh = true).single().load())
        val cache = DriveCatalogCache()
        cache.folders("drive", refresh = false) { emptyList() }
        val folder = DriveFolder(Uri.parse("content://drive/a"), "A", recording = true, position = DrivePosition(1L, "A"))
        var loads = 0
        // The folder's file is rewritten and forgotten while the first read is still running.
        cache.take("drive", folder) { loads++; cache.forget("A"); read }
        cache.take("drive", folder) { loads++; read }
        assertEquals(2, loads)
        cache.take("drive", folder) { loads++; read }
        assertEquals(2, loads)
    }

    @Test fun aListingStartedBeforeAnInvalidationDoesNotReplaceALaterOne() {
        val cache = DriveCatalogCache()
        fun folder(id: String) = DriveFolder(Uri.parse("content://drive/$id"), id, recording = true, position = DrivePosition(1L, id))
        val stale = listOf(folder("A"))
        val fresh = listOf(folder("A"), folder("B"))
        // The drive changes and is listed again while the first listing is still running.
        assertEquals(stale, cache.folders("drive", refresh = false) {
            cache.invalidate()
            assertEquals(fresh, cache.folders("drive", refresh = false) { fresh })
            stale
        })
        assertEquals(fresh, cache.folders("drive", refresh = false) { error("The later listing is kept") })
    }

    @Test fun ofTwoListingsRunningAtOnceTheOneStartedLastIsKept() {
        val cache = DriveCatalogCache()
        fun folder(id: String) = DriveFolder(Uri.parse("content://drive/$id"), id, recording = true, position = DrivePosition(1L, id))
        val older = listOf(folder("A"))
        val newer = listOf(folder("A"), folder("B"))
        // The second refresh starts and finishes while the first is still reading the drive.
        cache.folders("drive", refresh = true) {
            assertEquals(newer, cache.folders("drive", refresh = true) { newer })
            older
        }
        assertEquals(newer, cache.folders("drive", refresh = false) { error("The newer listing is kept") })
    }

    @Test fun theListingIsReusedUntilRefreshedOrInvalidated() {
        val cache = DriveCatalogCache()
        recording("A001", 3_000_000)
        assertEquals(1, takes(catalog(cache)).size)
        recording("A002", 2_000_000)
        assertEquals(1, catalog(cache).entries(refresh = false).size)
        assertEquals(2, catalog(cache).entries(refresh = true).size)
        recording("A003", 1_000_000)
        catalog(cache).invalidate()
        assertEquals(3, catalog(cache).entries(refresh = false).size)
    }

    private fun delete(take: com.librestatic.opencinecam.storage.LocalMediaTake, catalog: DriveMediaCatalog = catalog()): Pair<MediaDeleteResult, Boolean> {
        var released = false
        val result = executeDriveDelete(take, catalog, { AutoCloseable { released = true } })
        return result to released
    }

    @Test fun deletingATakeRemovesItsFolderAndVerifiesEveryFile() {
        val folder = recording("A001", 3_000_000)
        File(folder, ".DS_Store").writeText("junk")
        recording("A002", 2_000_000)
        val take = takes().first()
        val (result, released) = delete(take)
        assertNull(result.error)
        assertEquals(listOf(MediaDeleteStatus.ABSENT_VERIFIED, MediaDeleteStatus.ABSENT_VERIFIED), result.files.map { it.status })
        assertTrue(released)
        assertFalse(folder.exists())
        assertTrue(drive.file("Takes/OpenCineCam/A002/A002.mp4").exists())
    }

    @Test fun aFileOutsideTheSelectionKeepsTheFolderAndIsNeverDeleted() {
        val folder = recording("A001", 3_000_000)
        File(folder, ".notes").writeText("lens 2, slate wrong")
        val take = takes().first()
        // A writer adds a file after the snapshot, while the take's files are being deleted.
        drive.afterDelete = { File(folder, "A001.extra.txt").writeText("late") }
        val (result, released) = delete(take)
        drive.afterDelete = null
        assertNull(result.error)
        assertEquals(listOf(MediaDeleteStatus.ABSENT_VERIFIED, MediaDeleteStatus.ABSENT_VERIFIED), result.files.map { it.status })
        assertTrue(released)
        assertEquals(setOf(".notes", "A001.extra.txt"), folder.list()!!.toSet())
    }

    @Test fun aFailedDeletionStopsAtTheFirstFileAndKeepsTheMedia() {
        val folder = recording("A001", 3_000_000)
        val take = takes().first()
        // The sidecar goes first; the drive then refuses the video.
        drive.afterDelete = { folder.setWritable(false) }
        val (result, released) = delete(take)
        folder.setWritable(true)
        drive.afterDelete = null
        assertNotNull(result.error)
        assertTrue(released)
        assertTrue(File(folder, "A001.mp4").exists())
        assertEquals(MediaDeleteStatus.RETAINED, result.files.first { it.artifact.name == "A001.mp4" }.status)
    }

    @Test fun aQueuedProxyJobIsStoppedBeforeTheFolderGoes() {
        val folder = recording("A001", 3_000_000)
        val take = takes().first()
        val steps = mutableListOf<String>()
        val result = executeDriveDelete(take, catalog(), { AutoCloseable { steps += "released" } },
            bindSource = { steps += "bound ${it.id == take.id} ${folder.exists()}" }, beginDeletion = { steps += "stopped ${folder.exists()}" })
        assertNull(result.error)
        assertEquals(listOf("bound true true", "stopped true", "released"), steps)
        assertFalse(folder.exists())
    }

    @Test fun aProxyJobThatCannotBeStoppedKeepsTheTake() {
        val folder = recording("A001", 3_000_000)
        val result = executeDriveDelete(takes().first(), catalog(), { AutoCloseable { } }, beginDeletion = { error("Proxy queue persistence unavailable") })
        assertEquals("Proxy queue persistence unavailable", result.error)
        assertTrue(result.files.all { it.status == MediaDeleteStatus.NOT_ATTEMPTED })
        assertTrue(folder.exists())
    }

    @Test fun aDriveThatFailsAfterTheDeleteLeavesEveryFileUnknown() {
        val folder = recording("A001", 3_000_000)
        val take = takes().single()
        drive.afterDelete = { drive.queryFailure = SecurityException("Permission revoked") }
        val (result, released) = delete(take)
        drive.queryFailure = null
        assertEquals("Permission revoked", result.error)
        assertEquals(listOf(MediaDeleteStatus.UNKNOWN, MediaDeleteStatus.UNKNOWN), result.files.map { it.status })
        assertTrue(result.files.all { it.detail == "Permission revoked" })
        assertTrue(released)
        assertFalse(File(folder, "A001.mp4").exists())
    }

    @Test fun aFileTheDriveNoLongerDescribesIsUnknownWhenNoListingConfirmsIt() {
        val folder = recording("A001", 3_000_000)
        val take = takes().single()
        // The deletes go through, then the take's folder and the one above give no answer: the root still does, but no
        // listing can confirm the files are gone.
        drive.afterDelete = { drive.unanswered = setOf("A001", "OpenCineCam") }
        val (result, released) = delete(take)
        drive.unanswered = emptySet()
        assertEquals(listOf(MediaDeleteStatus.UNKNOWN, MediaDeleteStatus.UNKNOWN), result.files.map { it.status })
        assertNotNull(result.error)
        assertFalse(result.complete)
        assertTrue(released)
        assertFalse(File(folder, "A001.mp4").exists())
    }

    @Test fun aChangedFolderIsNotDeleted() {
        val folder = recording("A001", 3_000_000)
        val take = takes().first()
        put("OpenCineCam/A001/A001${RAW_VIDEO_EXTENSION}")
        assertNotNull(delete(take).first.error)
        File(folder, "A001${RAW_VIDEO_EXTENSION}").delete()
        File(folder, "notes").mkdir()
        assertNotNull(delete(take).first.error)
        File(folder, "notes").delete()
        File(folder, SafRecordingRows.MARKER).createNewFile()
        val (result, released) = delete(take)
        assertNotNull(result.error)
        assertTrue(released)
        assertTrue(result.files.all { it.status == MediaDeleteStatus.NOT_ATTEMPTED })
        assertTrue(File(folder, "A001.mp4").exists())
    }

    @Test fun aTakeOutsideATakeFolderIsRefused() {
        recording("A001", 3_000_000)
        put("OpenCineCam/A001.mp4")
        val take = takes().first()
        val forged = LocalMediaArtifact(docUri("OpenCineCam/A001.mp4"), "A001.mp4", "video/mp4", 5, 0)
        assertNotNull(delete(take.copy(primary = forged, originals = listOf(forged))).first.error)
        val base = LocalMediaArtifact(DocumentsContract.buildDocumentUriUsingTree(tree, "${FakeExternalStorageProvider.VOLUME}:Takes/x").toString(), "x", "video/mp4", 5, 0)
        assertNotNull(delete(take.copy(primary = base, originals = listOf(base))).first.error)
        destination = RecordingDestination.Phone
        assertNotNull(delete(take).first.error)
        assertTrue(drive.file("Takes/OpenCineCam/A001.mp4").exists())
        assertTrue(drive.file("Takes/OpenCineCam/A001/A001.mp4").exists())
    }
}
