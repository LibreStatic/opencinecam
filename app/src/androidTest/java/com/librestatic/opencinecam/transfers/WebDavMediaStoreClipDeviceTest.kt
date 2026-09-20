/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileNotFoundException
import java.net.URI
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebDavMediaStoreClipDeviceTest {
    @Test fun onlyConcretePositiveMediaStoreItemUrisAreAccepted() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        for (uri in listOf(
            "content://media/external/video/media", "content://media/external/video/media/0",
            "content://media/external/video/media/-1", "content://media/external/video/media/not-a-number",
            "content://media/external/video/media/9223372036854775808", "content://media/external/video/media/1?pending=1",
            "content://media/external/video/media/1#fragment", "content://media/external/video/media/1/",
            "content://media/external/unknown/1", "content://other/external/video/media/1",
            "file:///external/video/media/1", "content://media/external/video/media/1?",
        )) {
            assertThrows(uri, IllegalArgumentException::class.java) { WebDavMediaStoreClip(resolver, Uri.parse(uri)) }
        }
        for (uri in listOf(
            "content://media/external/video/media/1", "content://media/external/images/media/2",
            "content://media/external/audio/media/3", "content://media/external/downloads/4",
            "content://media/external_primary/file/5",
        )) {
            WebDavMediaStoreClip(resolver, Uri.parse(uri))
        }
    }

    @Test fun pendingMediaIsRejectedThenPublishedMediaCanBeRead() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val bytes = ByteArray(1024) { it.toByte() }
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "WebDav-fixture-${System.nanoTime()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/OpenCineCamTests")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            val source = WebDavMediaStoreClip(resolver, uri)
            assertFalse(source.snapshot().finalized)
            assertThrows(FileNotFoundException::class.java) { source.open() }
            val result = WebDavUploadTransport({ error("Pending clip reached network") }).upload(
                source,
                WebDavDestination(URI("https://dav.example/takes/")),
                WebDavUploadControl(WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI)),
            )
            assertEquals(WebDavUploadResult.Failed(WebDavFailure.SOURCE_NOT_FINALIZED), result)
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val snapshot = source.snapshot()
            assertTrue(snapshot.finalized)
            assertEquals(bytes.size.toLong(), snapshot.sizeBytes)
            assertEquals(uri.toString(), snapshot.identity)
            source.open().use { assertArrayEquals(bytes, it.readBytes()) }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test fun deletedPublishedRowDoesNotReturnAUsableSource() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "WebDav-deleted-${System.nanoTime()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var rowDeleted = false
        try {
            val source = WebDavMediaStoreClip(resolver, uri)
            val deletedRows = resolver.delete(uri, null, null)
            rowDeleted = deletedRows == 1
            assertEquals(1, deletedRows)
            assertThrows(FileNotFoundException::class.java) { source.snapshot() }
            assertThrows(FileNotFoundException::class.java) { source.open() }
        } finally {
            // MediaStore revokes ownership after deletion; deleting the stale item again can
            // throw SecurityException on API 30 and mask the source-rejection assertions.
            if (!rowDeleted) resolver.delete(uri, null, null)
        }
    }
}
