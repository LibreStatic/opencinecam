/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.storage.DriveDescriptorHandle
import com.librestatic.opencinecam.storage.DriveDescriptors
import com.librestatic.opencinecam.storage.EXTERNAL_STORAGE_AUTHORITY
import com.librestatic.opencinecam.storage.RawVideoQualificationStore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The camera-free paths of [RawVideoQualifier]: Robolectric advertises no camera. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RawVideoQualifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Recorder : RawVideoQualifier.Listener {
        val progress = mutableListOf<RawQualificationProgress>()
        val results = mutableListOf<JSONObject>()
        override fun onProgress(progress: RawQualificationProgress) { this.progress += progress }
        override fun onResult(result: JSONObject) { results += result }
    }

    @Test fun cancelledBeforeItStartsItReturnsCancelledWithoutTouchingTheStore() {
        val listener = Recorder()
        val qualifier = RawVideoQualifier(context, listener = listener)
        val file = File(context.filesDir, RawVideoQualificationStore.FILE_NAME)
        file.delete()
        qualifier.cancel()
        assertTrue(qualifier.isCancelled)
        val outcome = qualifier.run(RawVideoQualificationStore(file, "fp"))
        assertEquals(RawQualificationStatus.CANCELLED, outcome.status)
        assertEquals(RawVideoQualifier.REASON_CANCELLED, outcome.reasonCode)
        assertEquals("CANCELLED", outcome.report.getString("status"))
        assertTrue(outcome.records.isEmpty())
        assertFalse(file.exists())
        assertTrue(listener.results.isEmpty())
        assertEquals(RawQualificationPhase.DONE, listener.progress.last().phase)
        assertEquals(1.0, listener.progress.last().fraction, 0.0)
        assertEquals(0L, listener.progress.last().remainingMs)
        // Where the time went is reported even for a run that never started a step.
        assertEquals(0, outcome.report.getJSONArray("steps").length())
        assertTrue(outcome.report.has("elapsedMs"))
        assertTrue(outcome.report.isNull("storageModel"))
    }

    @Test fun withoutARaw10CameraItIsUnknownAndUnsupported() {
        val qualifier = RawVideoQualifier(context)
        assertNull(qualifier.estimate())
        val outcome = qualifier.run()
        assertEquals(RawQualificationStatus.UNKNOWN, outcome.status)
        assertEquals(RawVideoQualifier.REASON_UNSUPPORTED, outcome.reasonCode)
        assertEquals(RawVideoQualifier.SCHEMA, outcome.report.getString("schema"))
        assertTrue(outcome.records.isEmpty())
        // Cancelling after the end is harmless.
        qualifier.cancel()
    }

    @Test(expected = IllegalStateException::class) fun aQualifierRunsOnce() {
        val qualifier = RawVideoQualifier(context)
        qualifier.run()
        qualifier.run()
    }

    private fun scratch(): Pair<java.nio.channels.FileChannel, ParcelFileDescriptor> {
        val file = File(context.cacheDir, "qualify-${UUID.randomUUID()}.bin").apply { writeBytes(ByteArray(16)) }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        return FileOutputStream(descriptor.fileDescriptor).channel to descriptor
    }

    @Test fun aScratchFileOnADriveIsReleasedWithItAndCancelsTheRun() {
        val volume = "C3D4-" + UUID.randomUUID().toString().take(4).uppercase()
        val uri = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "$volume:Rec"), "$volume:Rec/OpenCineCam/.qualify.part")
        val qualifier = RawVideoQualifier(context)
        val (channel, descriptor) = scratch()
        qualifier.registerDriveRow(uri, channel, descriptor)
        assertEquals(1, DriveDescriptors.registered(volume))
        assertFalse(qualifier.isCancelled)

        // ADR-0037: the drive left, so vold would kill a process still holding this descriptor.
        assertEquals(1, DriveDescriptors.releaseVolume(volume))
        assertFalse(channel.isOpen)
        assertTrue(qualifier.driveLost)
        assertTrue(qualifier.isCancelled)
        assertEquals(0, DriveDescriptors.registered(volume))
    }

    @Test fun aFinishedDriveRowIsRetiredAndAPhoneRowIsNeverRegistered() {
        val volume = "C3D4-" + UUID.randomUUID().toString().take(4).uppercase()
        val uri = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "$volume:Rec"), "$volume:Rec/OpenCineCam/.qualify.part")
        val qualifier = RawVideoQualifier(context)
        val (channel, descriptor) = scratch()
        qualifier.registerDriveRow(uri, channel, descriptor).unregister()
        assertEquals(0, DriveDescriptors.registered(volume))
        assertEquals(0, DriveDescriptors.releaseVolume(volume))
        assertTrue(channel.isOpen)

        val phone = qualifier.registerDriveRow(Uri.parse("content://media/external/file/42"), channel, descriptor)
        assertEquals(DriveDescriptorHandle.NONE, phone)
        assertFalse(qualifier.driveLost)
        assertFalse(qualifier.isCancelled)
        channel.close(); descriptor.close()
    }
}
