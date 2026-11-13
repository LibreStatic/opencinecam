/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowDocumentsProviderQuery::class])
class ContentResolverBenchmarkDestinationTest {
    @Test
    fun descriptorBackedDocumentGetsAChannelSinkThatTakesDirectBuffers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "bench.bin").apply { writeBytes(ByteArray(0)) }
        val destination = ContentResolverBenchmarkDestination(context.contentResolver, "file", Uri.fromFile(file))

        val sink = destination.openSink()
        val payload = ByteArray(4096) { (it % 251).toByte() }
        try {
            assertTrue(sink.supportsByteBuffer)
            val direct = ByteBuffer.allocateDirect(payload.size).apply { put(payload); flip() }
            sink.write(direct)
            sink.write(byteArrayOf(7, 8, 9))
            sink.sync()
            assertEquals(payload.size + 3L, sink.bytesWritten)
        } finally {
            sink.close()
        }

        assertArrayEquals(payload + byteArrayOf(7, 8, 9), file.readBytes())
    }

    @Test
    fun sinkOnADriveIsReleasedWithItAndRetiredWhenItCloses() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val drive = FakeExternalStorageProvider.install(File(context.cacheDir, "bench-drive").apply { deleteRecursively(); mkdirs() })
        val tree = drive.tree("")
        FakeExternalStorageProvider.grant(context, tree)
        val documents = SafDocuments(context.contentResolver, tree)
        fun sink(name: String) = ContentResolverBenchmarkDestination(context.contentResolver, "saf:drive",
            documents.newFile(documents.root, name).uri).openSink()

        val finished = sink("finished.bin")
        assertEquals(1, DriveDescriptors.registered(drive.volume))
        finished.write(byteArrayOf(1, 2, 3))
        finished.close()
        assertEquals(0, DriveDescriptors.registered(drive.volume))

        // ADR-0037: the drive leaves mid-measurement, so the descriptor closes and the next write fails.
        val pulled = sink("pulled.bin")
        assertEquals(1, DriveDescriptors.releaseVolume(drive.volume))
        assertThrows(java.io.IOException::class.java) { pulled.write(ByteBuffer.wrap(byteArrayOf(4))) }
        pulled.close()
        assertEquals(0, DriveDescriptors.registered(drive.volume))
    }
}
