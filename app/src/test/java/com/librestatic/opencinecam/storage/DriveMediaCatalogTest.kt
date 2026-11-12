/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.GalleryMediaKind
import com.librestatic.opencinecam.GallerySettings
import java.net.URLEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN-072 Delivery 2: the merged gallery cursor, drive take identity and the guards of a drive deletion. */
class DriveMediaCatalogTest {
    private val treeId = "FFF1-7571:Takes"
    private val folder = "$treeId/OpenCineCam/A001"
    private val bundle = "2b2a7bb1-5061-4379-86c1-cd7cbca97e42"

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun doc(id: String) = "content://$EXTERNAL_STORAGE_AUTHORITY/tree/${enc(treeId)}/document/${enc(id)}"

    // The merged cursor

    /** A phone take modified at [seconds]: a legacy row, which needs no sidecar read. */
    private fun phone(id: Long, seconds: Long, kind: LocalMediaKind = LocalMediaKind.VIDEO): CatalogRow {
        val segment = if (kind == LocalMediaKind.PHOTO) "images" else "video"
        val mime = if (kind == LocalMediaKind.PHOTO) "image/jpeg" else "video/mp4"
        return CatalogRow(LocalMediaArtifact("content://media/external_primary/$segment/media/$id", "phone$id.${if (kind == LocalMediaKind.PHOTO) "jpg" else "mp4"}",
            mime, 10, seconds), id, kind, null)
    }

    private fun driveEntry(name: String, millis: Long, kind: LocalMediaKind = LocalMediaKind.VIDEO): DriveCatalogEntry {
        val id = "$treeId/OpenCineCam/$name"
        val artifact = LocalMediaArtifact(doc("$id/$name.mp4"), "$name.${if (kind == LocalMediaKind.PHOTO) "jpg" else "mp4"}",
            if (kind == LocalMediaKind.PHOTO) "image/jpeg" else "video/mp4", 10, millis / 1000)
        val take = LocalMediaTake("drive:$id", artifact, listOf(artifact), emptyList(), kind, null, LocalMediaRelationStatus.MISSING_METADATA)
        return DriveCatalogEntry(DrivePosition(millis, id)) { DriveCatalogTake(take) }
    }

    /** MediaStore as the repository queries it: per kind, after the keyset position, at most one batch. */
    private fun mediaStore(rows: List<CatalogRow>, newestFirst: Boolean) = { kind: LocalMediaKind, position: MediaPosition? ->
        rows.filter { it.kind == kind }
            .sortedWith(if (newestFirst) compareByDescending<CatalogRow> { it.artifact.modifiedSeconds }.thenBy { it.id }
                else compareBy<CatalogRow> { it.artifact.modifiedSeconds }.thenBy { it.id })
            .filter { row -> position == null || (if (newestFirst) row.artifact.modifiedSeconds < position.modified else row.artifact.modifiedSeconds > position.modified) ||
                row.artifact.modifiedSeconds == position.modified && row.id > position.id }
            .take(CATALOG_CANDIDATE_BATCH)
    }

    private fun pages(rows: List<CatalogRow>, drive: List<DriveCatalogEntry>, settings: GallerySettings = GallerySettings(),
        query: String = "", limit: Int = 3, blocked: Set<String> = emptySet()): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var cursor: LocalMediaCursor? = null
        do {
            val page = catalogPage(settings, query, cursor, limit, mediaStore(rows, settings.newestFirst), { drive }, { blocked }) { _, _, _ -> error("No namespaces") }
            result += page.takes.map { it.id }
            cursor = page.next
            check(result.size < 500) { "The cursor never ended" }
        } while (cursor != null)
        return result
    }

    @Test fun driveTakesInterleaveWithMediaStoreByDateAcrossPages() {
        val rows = listOf(phone(1, 1_000), phone(2, 800), phone(3, 600), phone(4, 400, LocalMediaKind.PHOTO), phone(5, 200))
        val drive = listOf(driveEntry("A", 900_500), driveEntry("B", 700_000), driveEntry("C", 600_999), driveEntry("D", 100_000))
        val order = pages(rows, drive).flatten()
        assertEquals(listOf("legacy:${rows[0].artifact.uri}", "drive:$treeId/OpenCineCam/A", "legacy:${rows[1].artifact.uri}",
            "drive:$treeId/OpenCineCam/B", "legacy:${rows[2].artifact.uri}", "drive:$treeId/OpenCineCam/C",
            "legacy:${rows[3].artifact.uri}", "legacy:${rows[4].artifact.uri}", "drive:$treeId/OpenCineCam/D"), order)
        // At the same second as a phone take the phone's comes first either way (C at 600.999 s, phone 3 at 600 s).
        assertEquals(listOf("drive:$treeId/OpenCineCam/D", "legacy:${rows[4].artifact.uri}", "legacy:${rows[3].artifact.uri}",
            "legacy:${rows[2].artifact.uri}", "drive:$treeId/OpenCineCam/C", "drive:$treeId/OpenCineCam/B",
            "legacy:${rows[1].artifact.uri}", "drive:$treeId/OpenCineCam/A", "legacy:${rows[0].artifact.uri}"),
            pages(rows, drive, GallerySettings(newestFirst = false)).flatten())
    }

    @Test fun everyTakeComesOnceInOrderWhenMediaStoreSpansSeveralBatches() {
        val rows = (1L..150L).map { phone(it, 10_000 - it * 10) }
        val drive = (0 until 40).map { driveEntry("T%02d".format(it), (10_000 - it * 37L) * 1000 + 500) }
        val order = pages(rows, drive, limit = 7).flatten()
        assertEquals(190, order.size)
        assertEquals(order.size, order.toSet().size)
        val seconds = (rows.map { "legacy:${it.artifact.uri}" to it.artifact.modifiedSeconds } +
            drive.map { "drive:${it.position.folderId}" to it.position.modifiedMillis / 1000 }).toMap()
        assertEquals(order.map { seconds.getValue(it) }.sortedDescending(), order.map { seconds.getValue(it) })
    }

    @Test fun eitherSourceAloneRunsToItsEnd() {
        val drive = (0 until 5).map { driveEntry("T$it", 1_000_000L - it) }
        val onlyDrive = pages(emptyList(), drive, limit = 2)
        assertEquals(listOf(2, 2, 1), onlyDrive.map { it.size })
        assertEquals(drive.map { "drive:${it.position.folderId}" }, onlyDrive.flatten())
        val rows = (1L..5L).map { phone(it, 100 - it) }
        assertEquals(rows.map { "legacy:${it.artifact.uri}" }, pages(rows, emptyList(), limit = 2).flatten())
        assertTrue(pages(emptyList(), emptyList()).flatten().isEmpty())
    }

    @Test fun filtersSearchAndJournalApplyToDriveTakes() {
        val rows = listOf(phone(1, 1_000), phone(2, 500, LocalMediaKind.PHOTO))
        val drive = listOf(driveEntry("Night", 900_000), driveEntry("Still", 700_000, LocalMediaKind.PHOTO), driveEntry("Day", 600_000))
        assertEquals(listOf("legacy:${rows[0].artifact.uri}", "drive:$treeId/OpenCineCam/Night", "drive:$treeId/OpenCineCam/Day"),
            pages(rows, drive, GallerySettings(kind = GalleryMediaKind.VIDEO)).flatten())
        assertEquals(listOf("drive:$treeId/OpenCineCam/Night"), pages(rows, drive, query = "nIGHT").flatten())
        assertEquals(listOf("legacy:${rows[0].artifact.uri}", "drive:$treeId/OpenCineCam/Still", "drive:$treeId/OpenCineCam/Day", "legacy:${rows[1].artifact.uri}"),
            pages(rows, drive, blocked = setOf("drive:$treeId/OpenCineCam/Night")).flatten())
    }

    @Test fun aDriveExhaustedEarlierIsNotReadAgain() {
        val rows = (1L..4L).map { phone(it, 100 - it) }
        val drive = listOf(driveEntry("New", 500_000))
        val settings = GallerySettings()
        var reads = 0
        val first = catalogPage(settings, "", null, 2, mediaStore(rows, true), { reads++; drive }, { emptySet() }) { _, _, _ -> null }
        assertEquals(listOf("drive:$treeId/OpenCineCam/New", "legacy:${rows[0].artifact.uri}"), first.takes.map { it.id })
        val second = catalogPage(settings, "", first.next, 2, mediaStore(rows, true), { reads++; drive }, { emptySet() }) { _, _, _ -> null }
        assertEquals(1, reads)
        assertEquals(rows.drop(1).take(2).map { "legacy:${it.artifact.uri}" }, second.takes.map { it.id })
    }

    // Identity and validation

    @Test fun documentIdsAndFolderMembershipAreParsedStrictly() {
        assertEquals("$folder/A001.mp4", externalStorageDocumentId(doc("$folder/A001.mp4")))
        assertEquals("$folder/A001.mp4", externalStorageDocumentId("content://$EXTERNAL_STORAGE_AUTHORITY/document/${enc("$folder/A001.mp4")}"))
        assertEquals("$folder/A001.mp4", driveMemberDocumentId(doc("$folder/A001.mp4"), folder))
        assertEquals(folder, driveFolderOf(doc("$folder/A001.mp4")))
        listOf(doc("$folder/sub/A001.mp4"), doc("$folder/.occ-in-progress"), doc("${folder}2/A001.mp4"), doc(folder),
            "content://media/external_primary/video/media/12", "content://evil.documents/tree/x/document/${enc("$folder/A001.mp4")}")
            .forEach { assertNull(it, driveMemberDocumentId(it, folder)) }
        val renamedFolder = "FFF1-7571-01:" + folder.substringAfter(':')
        assertEquals("$renamedFolder/A001.mp4", driveMemberDocumentId(doc("$folder/A001.mp4"), renamedFolder))
        assertEquals("$folder/A001.mp4", driveMemberDocumentId(doc("$renamedFolder/A001.mp4"), folder))
        assertNull(driveMemberDocumentId(doc("FFF1-7572:" + folder.substringAfter(':') + "/A001.mp4"), folder))
        assertTrue(isDriveUri(doc("$folder/A001.mp4")))
        assertFalse(isDriveUri("content://media/external_primary/video/media/12"))
        assertEquals("WD Elements/Takes/OpenCineCam/A001/A001.mp4", drivePath("WD Elements", doc("$folder/A001.mp4")))
    }

    @Test fun persistedDriveReferencesMustBeCanonical() {
        val video = doc("$folder/A001.mp4")
        assertTrue(canonicalDriveDocumentUri(video))
        assertEquals(video, "content://$EXTERNAL_STORAGE_AUTHORITY/tree/${safUriEncode(treeId)}/document/${safUriEncode("$folder/A001.mp4")}")
        assertTrue(canonicalDriveDocumentUri("content://$EXTERNAL_STORAGE_AUTHORITY/tree/${safUriEncode("FFF1-7571:")}/document/${safUriEncode("FFF1-7571:OpenCineCam/A001/A001.mp4")}"))
        listOf(
            video.replace("%3A", "%3a"), // another spelling of the same id
            video.replace("A001.mp4", "%41001.mp4"),
            "content://$EXTERNAL_STORAGE_AUTHORITY/document/${enc("$folder/A001.mp4")}", // no tree
            "$video?x=1", "$video#f",
            doc("FFF1-7571:Other/OpenCineCam/A001/A001.mp4"), // outside the tree
            doc("$folder/../A001.mp4"), doc("$treeId//A001.mp4"), doc(treeId),
            "content://media/external_primary/video/media/12",
        ).forEach { assertFalse(it, canonicalDriveDocumentUri(it)) }
    }

    @Test fun driveMembersHaveKindsAndCollectionsFromTheirNames() {
        assertEquals(LocalMediaKind.VIDEO, takeOriginalKind(doc("$folder/A001.mp4")))
        assertEquals(LocalMediaKind.AUDIO, takeOriginalKind(doc("$folder/A001.wav")))
        assertEquals(LocalMediaKind.RAW_VIDEO, takeOriginalKind(doc("$folder/A001.ocraw")))
        assertEquals(LocalMediaKind.PHOTO, takeOriginalKind(doc("$folder/S001.dng")))
        assertEquals(LocalMediaKind.VIDEO, takeOriginalKind("content://media/external_primary/video/media/12"))
        assertNull(takeOriginalKind(doc("$folder/A001.recording.json")))
        assertEquals(MediaDeleteCollection.METADATA, takeMemberCollection(doc("$folder/A001.recording.json")))
        assertEquals(MediaDeleteCollection.METADATA, takeMemberCollection(doc("$folder/A001.gcsv")))
        assertEquals(MediaDeleteCollection.VIDEO, takeMemberCollection(doc("$folder/A001.mp4")))
        assertNull(takeMemberCollection(doc("$folder/A001.ocraw")))
        assertNull(takeMemberCollection("content://evil/document/x.json"))
        assertEquals(MediaDeleteCollection.METADATA, takeMemberCollection("content://media/external_primary/downloads/4"))
        assertTrue(sameTakeMemberUri(doc("$folder/A001.mp4"), "content://$EXTERNAL_STORAGE_AUTHORITY/document/${enc("$folder/A001.mp4")}"))
        assertFalse(sameTakeMemberUri(doc("$folder/A001.mp4"), doc("$folder/A002.mp4")))
    }

    @Test fun aDriveTakeKeyedByItsFolderMayBeProxied() {
        val video = doc("$folder/A001.mp4")
        assertTrue(proxyTakeIdBinds("drive:$folder", video))
        assertTrue(proxyTakeIdBinds("take:$bundle", video))
        assertTrue(proxyTakeIdBinds("legacy:content://media/external_primary/video/media/12", "content://media/external_primary/video/media/12"))
        assertFalse(proxyTakeIdBinds("drive:$treeId/OpenCineCam/A002", video))
        assertFalse(proxyTakeIdBinds("drive:$folder", "content://media/external_primary/video/media/12"))
        assertFalse(proxyTakeIdBinds("still:$bundle", video))
    }

    private fun member(name: String, kind: LocalMediaKind, namespace: MediaNamespace, id: Long, uri: String = doc("$folder/$name")) =
        CatalogRow(LocalMediaArtifact(uri, name, if (kind == LocalMediaKind.AUDIO) "audio/x-wav" else "video/mp4", 100, 5), id, kind, namespace)

    private fun sidecar(json: String, n: Int = 1) =
        MetadataDocument(LocalMediaArtifact(doc("$folder/A001.$n.json"), "A001.$n.json", "application/json", json.length.toLong(), 5), json)

    private fun videoJson(uri: String, id: String? = bundle) = buildJsonObject {
        put("schema", "opencinecam.recording.v1"); id?.let { put("bundleId", it) }; put("videoUri", uri)
    }.toString()

    @Test fun aDriveNamespaceIsItsBundleIdOrElseItsFolder() {
        val declared = listOf(sidecar(videoJson(doc("$folder/A001.mp4")), 1), sidecar(videoJson(doc("$folder/A001.mp4")), 2))
        assertEquals(bundle, driveBundleId(declared))
        assertEquals("take:$bundle", MediaNamespace(bundle, true, folder).key)
        assertNull(driveBundleId(listOf(sidecar(videoJson(doc("$folder/A001.mp4"), id = null)))))
        assertNull(driveBundleId(declared + sidecar(videoJson(doc("$folder/A001.mp4"), id = "0e2a7bb1-5061-4379-86c1-cd7cbca97e42"), 3)))
        assertNull(driveBundleId(listOf(sidecar(videoJson(doc("$folder/A001.mp4"), id = "not-a-uuid")))))
        assertEquals("drive:$folder", MediaNamespace("", true, folder).key)
        // A drive take and a phone take of one bundle are different namespaces, with the same key.
        assertNotEquals(MediaNamespace(bundle, true), MediaNamespace(bundle, true, folder))
    }

    @Test fun catalogTakeAcceptsReferencesInsideTheTakeFolderOnly() {
        val namespace = MediaNamespace(bundle, true, folder)
        val video = member("A001.mp4", LocalMediaKind.VIDEO, namespace, 1)
        assertEquals(LocalMediaRelationStatus.DECLARED,
            catalogTake(namespace, listOf(video), listOf(sidecar(videoJson(video.artifact.uri))))?.relationStatus)
        // The same document through another tree of the same drive still matches by document id.
        val reTree = "content://$EXTERNAL_STORAGE_AUTHORITY/tree/${enc("FFF1-7571:")}/document/${enc("$folder/A001.mp4")}"
        assertEquals(LocalMediaRelationStatus.DECLARED, catalogTake(namespace, listOf(video), listOf(sidecar(videoJson(reTree))))?.relationStatus)
        for (outside in listOf(doc("$treeId/OpenCineCam/B002/A001.mp4"), doc("$folder/nested/A001.mp4"),
            "content://media/external_primary/video/media/7", "content://other.provider/document/${enc("$folder/A001.mp4")}")) {
            assertEquals(outside, LocalMediaRelationStatus.INVALID_METADATA,
                catalogTake(namespace, listOf(video), listOf(sidecar(videoJson(outside))))?.relationStatus)
        }
        // A sidecar written while the drive was FFF1-7571 names the same file once vold mounts it as FFF1-7571-01.
        val renamed = MediaNamespace(bundle, true, "FFF1-7571-01:" + folder.substringAfter(':'))
        val moved = member("A001.mp4", LocalMediaKind.VIDEO, renamed, 1, doc("${renamed.driveFolder}/A001.mp4"))
        assertEquals(LocalMediaRelationStatus.DECLARED,
            catalogTake(renamed, listOf(moved), listOf(sidecar(videoJson(video.artifact.uri))))?.relationStatus)
        assertEquals(LocalMediaRelationStatus.DECLARED,
            catalogTake(namespace, listOf(video), listOf(sidecar(videoJson(moved.artifact.uri))))?.relationStatus)
        assertTrue(sameTakeMemberUri(video.artifact.uri, moved.artifact.uri))
        // Another drive is never an alias.
        assertEquals(LocalMediaRelationStatus.INVALID_METADATA, catalogTake(renamed, listOf(moved),
            listOf(sidecar(videoJson(doc("FFF1-7572:" + folder.substringAfter(':') + "/A001.mp4")))))?.relationStatus)
        // A sibling in the folder that is not listed is a missing member, not an invalid reference.
        assertEquals(LocalMediaRelationStatus.INCOMPLETE,
            catalogTake(namespace, listOf(video), listOf(sidecar(videoJson(doc("$folder/A002.mp4")))))?.relationStatus)
        // The declared bundle must be the namespace's.
        assertEquals(LocalMediaRelationStatus.INVALID_METADATA,
            catalogTake(MediaNamespace("", true, folder), listOf(member("A001.mp4", LocalMediaKind.VIDEO, MediaNamespace("", true, folder), 1)),
                listOf(sidecar(videoJson(video.artifact.uri))))?.relationStatus)
    }

    @Test fun phoneValidationStaysStrictAboutDriveDocuments() {
        val namespace = MediaNamespace(bundle, true)
        val video = CatalogRow(LocalMediaArtifact("content://media/external_primary/video/media/7", "A001.mp4", "video/mp4", 100, 5),
            7, LocalMediaKind.VIDEO, namespace)
        assertEquals(LocalMediaRelationStatus.DECLARED, catalogTake(namespace, listOf(video), listOf(sidecar(videoJson(video.artifact.uri))))?.relationStatus)
        assertEquals(LocalMediaRelationStatus.INVALID_METADATA,
            catalogTake(namespace, listOf(video), listOf(sidecar(videoJson(doc("$folder/A001.mp4")))))?.relationStatus)
        assertEquals(LocalMediaRelationStatus.INVALID_METADATA,
            catalogTake(namespace, listOf(video), listOf(sidecar(videoJson("content://media/external_primary/audio/media/7")))).let { it?.relationStatus })
    }

    @Test fun fileRolesFollowTheRecoveryMapping() {
        assertEquals(DriveFileRole.VIDEO, driveFileRole("A001.MP4", true))
        assertEquals(DriveFileRole.AUDIO, driveFileRole("A001.wav", true))
        assertEquals(DriveFileRole.AUDIO, driveFileRole("A001.flac", true))
        assertEquals(DriveFileRole.DOCUMENT, driveFileRole("A001.clip.json", true))
        assertEquals(DriveFileRole.DOCUMENT, driveFileRole("A001.gcsv", true))
        assertEquals(DriveFileRole.DOCUMENT, driveFileRole("A001.jpg", true))
        assertEquals(DriveFileRole.PHOTO, driveFileRole("S001.jpg", false))
        assertEquals(DriveFileRole.PHOTO, driveFileRole("S001.dng", false))
        assertEquals(DriveFileRole.DOCUMENT, driveFileRole("S001.mp4", false))
        assertEquals(DriveFileRole.SKIPPED, driveFileRole(".occ-in-progress", true))
        assertEquals(DriveFileRole.RAW, driveFileRole("A001.ocraw", true))
        assertEquals(DriveFileRole.SKIPPED, driveFileRole("S001.ocraw", false))
        assertEquals(RawVideoOutput.MIME_TYPE, driveMime("A001.ocraw", DriveFileRole.RAW, "application/x-whatever"))
        assertEquals("image/jpeg", driveMime("S001.jpg", DriveFileRole.PHOTO, "application/octet-stream"))
        assertEquals("application/json", driveMime("A001.clip.json", DriveFileRole.DOCUMENT, "application/octet-stream"))
        assertEquals("audio/flac", driveMime("A001.flac", DriveFileRole.AUDIO, "application/octet-stream"))
    }

    // Deletion and thumbnails

    @Test fun onlyATakeFolderDirectlyUnderOpenCineCamMayBeDeleted() {
        assertTrue(driveTakeFolderDeletable(treeId, "$treeId/OpenCineCam/A001", recording = true))
        assertTrue(driveTakeFolderDeletable(treeId, "$treeId/OpenCineCam/Stills/S001", recording = false))
        assertTrue(driveTakeFolderDeletable("FFF1-7571:", "FFF1-7571:OpenCineCam/A001", recording = true))
        for ((id, recording) in listOf(treeId to true, "$treeId/OpenCineCam" to true, "$treeId/OpenCineCam/" to true,
            "$treeId/OpenCineCam/Stills" to true, "$treeId/OpenCineCam/Stills" to false, "$treeId/OpenCineCam/A001/inner" to true,
            "$treeId/OpenCineCam/.occ-benchmark" to true, "$treeId/OpenCineCam/A001" to false, "$treeId/OpenCineCam/Stills/S001" to true,
            "$treeId/Other/A001" to true, "FFF1-7571:OpenCineCam/A001" to true, "$treeId/OpenCineCam/.." to true,
            "$treeId/OpenCineCamX/A001" to true)) {
            assertFalse("$id recording=$recording", driveTakeFolderDeletable(treeId, id, recording))
        }
    }

    @Test fun thumbnailKeyChangesWithTheFileAndTheRequest() {
        val key = driveThumbnailKey(doc("$folder/A001.mp4"), 512, 100, 5)
        assertEquals(key, driveThumbnailKey(doc("$folder/A001.mp4"), 512, 100, 5))
        assertTrue(key.matches(Regex("[0-9a-f]{40}\\.jpg")))
        listOf(driveThumbnailKey(doc("$folder/A002.mp4"), 512, 100, 5), driveThumbnailKey(doc("$folder/A001.mp4"), 256, 100, 5),
            driveThumbnailKey(doc("$folder/A001.mp4"), 512, 101, 5), driveThumbnailKey(doc("$folder/A001.mp4"), 512, 100, 6))
            .forEach { assertNotEquals(key, it) }
    }
}
