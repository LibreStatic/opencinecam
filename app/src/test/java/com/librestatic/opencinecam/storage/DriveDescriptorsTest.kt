/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** ADR-0037: the process-wide registry of write descriptors on a drive, closed at once when the drive leaves. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DriveDescriptorsTest {
    // The registry is process-wide: each test uses its own volumes and releases them afterwards.
    private val volume = "A1B2-" + UUID.randomUUID().toString().take(4).uppercase()
    private val other = "C3D4-" + UUID.randomUUID().toString().take(4).uppercase()

    @After fun tearDown() {
        DriveDescriptors.releaseVolume(volume)
        DriveDescriptors.releaseVolume(other)
    }

    private class Counting(private val failure: Throwable? = null) : AutoCloseable {
        var closes = 0
        override fun close() {
            closes++
            failure?.let { throw it }
        }
    }

    @Test fun releaseClosesEveryDescriptorOfTheVolumeOnceAndForgetsThem() {
        val first = Counting()
        val second = Counting()
        DriveDescriptors.register(volume, first)
        DriveDescriptors.register(volume, second)
        assertEquals(2, DriveDescriptors.registered(volume))

        assertEquals(2, DriveDescriptors.releaseVolume(volume))
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
        assertEquals(0, DriveDescriptors.registered(volume))
        assertEquals(0, DriveDescriptors.releaseVolume(volume))
        assertEquals(1, first.closes)
    }

    @Test fun volumesMatchIgnoringCase() {
        val descriptor = Counting()
        DriveDescriptors.register(volume.lowercase(), descriptor)
        assertEquals(1, DriveDescriptors.registered(volume))
        assertEquals(1, DriveDescriptors.releaseVolume(volume.lowercase()))
        assertEquals(1, descriptor.closes)
    }

    @Test fun aReleaseBoundedByAMarkSparesTheDescriptorsRegisteredAfterIt() {
        val before = Counting()
        DriveDescriptors.register(volume, before)
        val mark = DriveDescriptors.registrationMark()
        // Opened on a newer mount of the same volume while a rescan was reading the volumes.
        val after = Counting()
        DriveDescriptors.register(volume, after)

        assertEquals(1, DriveDescriptors.releaseVolume(volume, registeredBy = mark))
        assertEquals(1, before.closes)
        assertEquals(0, after.closes)
        assertEquals(1, DriveDescriptors.registered(volume))
        assertEquals(1, DriveDescriptors.releaseVolume(volume))
        assertEquals(1, after.closes)
    }

    @Test fun aDescriptorOpenedAcrossAReleaseIsClosedInsteadOfRegistered() {
        val document = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "$volume:Rec"), "$volume:Rec/OpenCineCam/A001/A001.mp4")
        val mark = DriveDescriptors.releaseMark(document)
        // The drive left while the open was in flight: nothing was registered yet, so the release closed nothing.
        assertEquals(0, DriveDescriptors.releaseVolume(volume))
        val late = Counting()
        assertSame(DriveDescriptorHandle.NONE, DriveDescriptors.registerDocument(document, late, openedAfter = mark))
        assertEquals(1, late.closes)
        assertEquals(0, DriveDescriptors.registered(volume))

        // Opened after that release (a newer mount): registered, and closed by the next release.
        val fresh = Counting()
        DriveDescriptors.registerDocument(document, fresh, openedAfter = DriveDescriptors.releaseMark(document))
        assertEquals(0, fresh.closes)
        assertEquals(1, DriveDescriptors.registered(volume))
        // Another volume's release does not reject a descriptor on this one.
        val otherMark = DriveDescriptors.releaseMark(document)
        DriveDescriptors.releaseVolume(other)
        val unaffected = Counting()
        DriveDescriptors.registerDocument(document, unaffected, openedAfter = otherMark)
        assertEquals(0, unaffected.closes)
        assertEquals(2, DriveDescriptors.releaseVolume(volume))
        assertEquals(1, fresh.closes)
    }

    @Test fun aLateBackstopReleaseDoesNotRefuseAnOpenOnTheNewMount() {
        val document = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "$volume:Rec"), "$volume:Rec/OpenCineCam/Stills/A/A.dng")
        val boundary = DriveDescriptors.registrationMark()
        DriveDescriptors.releaseVolume(volume) // the monitor's release as the drive left
        // The drive mounted again and a still opened on it; then the service's backstop for the old Leaving runs.
        val mark = DriveDescriptors.releaseMark(document)
        assertEquals(0, DriveDescriptors.releaseVolume(volume, boundary, backstop = true))
        val still = Counting()
        DriveDescriptors.registerDocument(document, still, openedAfter = mark)
        assertEquals(0, still.closes)
        assertEquals(1, DriveDescriptors.registered(volume))
        DriveDescriptors.releaseVolume(volume)
    }

    @Test fun otherVolumesAreUntouched() {
        val leaving = Counting()
        val staying = Counting()
        DriveDescriptors.register(volume, leaving)
        DriveDescriptors.register(other, staying)

        assertEquals(1, DriveDescriptors.releaseVolume(volume))
        assertEquals(1, leaving.closes)
        assertEquals(0, staying.closes)
        assertEquals(1, DriveDescriptors.registered(other))
    }

    @Test fun unregisterIsIdempotentAndAnUnregisteredDescriptorIsNotClosed() {
        val kept = Counting()
        val closedNormally = Counting()
        DriveDescriptors.register(volume, kept)
        val handle = DriveDescriptors.register(volume, closedNormally)
        handle.unregister()
        handle.unregister()
        assertEquals(1, DriveDescriptors.registered(volume))

        assertEquals(1, DriveDescriptors.releaseVolume(volume))
        assertEquals(0, closedNormally.closes)
        assertEquals(1, kept.closes)
        // After a release the handle's unregister still does nothing harmful.
        handle.unregister()
    }

    @Test fun aCloseThatThrowsDoesNotStopTheOthers() {
        val failing = Counting(java.io.IOException("ENODEV"))
        val after = Counting()
        val fatal = Counting(IllegalStateException("closed twice"))
        DriveDescriptors.register(volume, failing)
        DriveDescriptors.register(volume, fatal)
        DriveDescriptors.register(volume, after)

        assertEquals(3, DriveDescriptors.releaseVolume(volume))
        assertEquals(1, failing.closes)
        assertEquals(1, fatal.closes)
        assertEquals(1, after.closes)
        assertEquals(0, DriveDescriptors.registered(volume))
    }

    @Test fun releasingAnUnknownVolumeClosesNothing() {
        assertEquals(0, DriveDescriptors.releaseVolume("0000-0000"))
    }

    @Test fun onlyDocumentsOnADriveAreRegistered() {
        val onDrive = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "$volume:Rec"), "${volume.lowercase()}:Rec/OpenCineCam/A001/A001.mp4")
        assertEquals(volume, driveVolumeOfDocument(onDrive))
        assertNull(driveVolumeOfDocument(Uri.parse("content://media/external/video/media/42")))
        assertNull(driveVolumeOfDocument(DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:DCIM/A001.mp4")))

        val descriptor = Counting()
        DriveDescriptors.registerDocument(onDrive, descriptor)
        val phone = DriveDescriptors.registerDocument(Uri.parse("content://media/external/video/media/42"), Counting())
        assertSame(DriveDescriptorHandle.NONE, phone)
        phone.unregister()
        assertEquals(1, DriveDescriptors.releaseVolume(volume))
        assertEquals(1, descriptor.closes)
    }

    @Test fun closeOnceClosesItsPartsInOrderOnlyOnceAndReportsTheFirstFailure() {
        val order = mutableListOf<String>()
        val closer = CloseOnce(
            AutoCloseable { order += "stream"; throw java.io.IOException("first") },
            AutoCloseable { order += "descriptor" },
        )
        assertFalse(closer.closed)
        assertThrows(java.io.IOException::class.java) { closer.close() }
        assertTrue(closer.closed)
        closer.close()
        assertEquals(listOf("stream", "descriptor"), order)
    }
}
