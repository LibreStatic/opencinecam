/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecordingDestinationTest {
    private val usb = RecordingDestination.UsbFolder(Uri.parse("content://$EXTERNAL_STORAGE_AUTHORITY/tree/FFF1-7571%3ARAW"), "FFF1-7571")

    @Test
    fun phoneResolvesToPhone() {
        val resolved = resolveRecordingDestination(RecordingDestination.Phone, emptySet(), hasPermission = false)
        assertEquals(ResolvedRecordingDestination(RecordingDestination.Phone, RecordingDestination.Phone, null), resolved)
    }

    @Test
    fun mountedAndPermittedFolderIsUsed() {
        val resolved = resolveRecordingDestination(usb, setOf("FFF1-7571"), hasPermission = true)
        assertEquals(ResolvedRecordingDestination(usb, usb, null), resolved)
    }

    @Test
    fun absentDriveFallsBackToPhone() {
        val resolved = resolveRecordingDestination(usb, setOf("1234-ABCD"), hasPermission = true)
        assertEquals(ResolvedRecordingDestination(RecordingDestination.Phone, usb, DestinationFallback.DRIVE_NOT_CONNECTED), resolved)
    }

    @Test
    fun lostPermissionFallsBackToPhoneEvenWithTheDriveAbsent() {
        assertEquals(ResolvedRecordingDestination(RecordingDestination.Phone, usb, DestinationFallback.PERMISSION_LOST),
            resolveRecordingDestination(usb, setOf("FFF1-7571"), hasPermission = false))
        assertEquals(DestinationFallback.PERMISSION_LOST, resolveRecordingDestination(usb, emptySet(), hasPermission = false).fallback)
    }

    @Test
    fun absentDriveIsStartingWhileAStorageDeviceArrives() {
        assertEquals(ResolvedRecordingDestination(RecordingDestination.Phone, usb, DestinationFallback.DRIVE_STARTING),
            resolveRecordingDestination(usb, emptySet(), hasPermission = true, driveArriving = true))
        assertEquals(DestinationFallback.DRIVE_NOT_CONNECTED, resolveRecordingDestination(usb, emptySet(), hasPermission = true, driveArriving = false).fallback)
    }

    @Test
    fun mountedDriveIgnoresAnArrivingDevice() {
        assertEquals(ResolvedRecordingDestination(usb, usb, null), resolveRecordingDestination(usb, setOf("FFF1-7571"), hasPermission = true, driveArriving = true))
        assertEquals(ResolvedRecordingDestination(RecordingDestination.Phone, RecordingDestination.Phone, null),
            resolveRecordingDestination(RecordingDestination.Phone, emptySet(), hasPermission = true, driveArriving = true))
    }

    @Test
    fun lostPermissionWinsOverAStartingDrive() {
        assertEquals(DestinationFallback.PERMISSION_LOST, resolveRecordingDestination(usb, emptySet(), hasPermission = false, driveArriving = true).fallback)
    }

    @Test
    fun driveIdentityDropsTheStableNameSuffix() {
        assertEquals("FFF1-7571", driveIdentityOf("FFF1-7571-01"))
        assertEquals("FFF1-7571", driveIdentityOf("fff1-7571-02"))
        assertEquals("FFF1-7571", driveIdentityOf("FFF1-7571"))
        // Only one suffix of exactly two digits goes.
        assertEquals("FFF1-7571-01", driveIdentityOf("FFF1-7571-01-02"))
        assertEquals("FFF1-7571-001", driveIdentityOf("FFF1-7571-001"))
        assertEquals("FFF1-7571-AB", driveIdentityOf("FFF1-7571-AB"))
        // ext4 UUIDs and NTFS serials are left alone.
        assertEquals("3F2504E0-4F89-11D3-9A0C-0305E82C3301", driveIdentityOf("3f2504e0-4f89-11d3-9a0c-0305e82c3301"))
        assertEquals("01D9A2B3C4D5E6F7", driveIdentityOf("01D9A2B3C4D5E6F7"))
        assertTrue(sameDrive("FFF1-7571", "fff1-7571-01"))
        assertFalse(sameDrive("FFF1-7571", "1234-ABCD"))
    }

    @Test
    fun driveMountedUnderAnotherNameIsRenamed() {
        assertEquals(ResolvedRecordingDestination(RecordingDestination.Phone, usb, DestinationFallback.DRIVE_RENAMED),
            resolveRecordingDestination(usb, setOf("FFF1-7571-01"), hasPermission = true))
        val renamed = usb.copy(volumeId = "FFF1-7571-01")
        assertEquals(DestinationFallback.DRIVE_RENAMED, resolveRecordingDestination(renamed, setOf("fff1-7571"), hasPermission = true).fallback)
        // It wins over a drive still arriving, a lost permission wins over it, and an unrelated drive is no match.
        assertEquals(DestinationFallback.DRIVE_RENAMED, resolveRecordingDestination(usb, setOf("FFF1-7571-01"), hasPermission = true, driveArriving = true).fallback)
        assertEquals(DestinationFallback.PERMISSION_LOST, resolveRecordingDestination(usb, setOf("FFF1-7571-01"), hasPermission = false).fallback)
        assertEquals(DestinationFallback.DRIVE_NOT_CONNECTED, resolveRecordingDestination(usb, setOf("1234-ABCD-01"), hasPermission = true).fallback)
        // The configured name mounted wins over the other one.
        assertEquals(usb, resolveRecordingDestination(usb, setOf("FFF1-7571-01", "FFF1-7571"), hasPermission = true).destination)
    }

    @Test
    fun volumeMatchIgnoresCase() {
        assertEquals(usb, resolveRecordingDestination(usb, setOf("fff1-7571"), hasPermission = true).destination)
        val lower = usb.copy(volumeId = "fff1-7571")
        assertEquals(lower, resolveRecordingDestination(lower, setOf("FFF1-7571"), hasPermission = true).destination)
    }

    @Test
    fun driveTreeIsAcceptedWithAnUpperCaseVolume() {
        assertEquals(TreeValidation.Ok("FFF1-7571"), validateRecordingTree(EXTERNAL_STORAGE_AUTHORITY, "FFF1-7571:RAW/OCC"))
        assertEquals(TreeValidation.Ok("FFF1-7571"), validateRecordingTree(EXTERNAL_STORAGE_AUTHORITY, "fff1-7571:"))
    }

    @Test
    fun phoneStorageTreesAreRejected() {
        val rejected = TreeValidation.Rejected(RecordingFolderRejection.PHONE_STORAGE)
        assertEquals(rejected, validateRecordingTree(EXTERNAL_STORAGE_AUTHORITY, "primary:DCIM"))
        assertEquals(rejected, validateRecordingTree(EXTERNAL_STORAGE_AUTHORITY, "primary:"))
        assertEquals(rejected, validateRecordingTree(EXTERNAL_STORAGE_AUTHORITY, "home:"))
    }

    @Test
    fun otherProvidersAndUnparsableIdsAreNotADrive() {
        val rejected = TreeValidation.Rejected(RecordingFolderRejection.NOT_A_DRIVE)
        assertEquals(rejected, validateRecordingTree("com.cxinventor.file.explorer.documents", "FFF1-7571:RAW"))
        assertEquals(rejected, validateRecordingTree(null, "FFF1-7571:RAW"))
        assertEquals(rejected, validateRecordingTree(EXTERNAL_STORAGE_AUTHORITY, null))
        assertEquals(rejected, validateRecordingTree(EXTERNAL_STORAGE_AUTHORITY, "garbage"))
        assertEquals(rejected, validateRecordingTree(EXTERNAL_STORAGE_AUTHORITY, ":RAW"))
        assertEquals(rejected, validateRecordingTree(EXTERNAL_STORAGE_AUTHORITY, "a/b:RAW"))
    }

    @Test
    fun volumeIdComesFromTheTreeUri() {
        assertEquals("FFF1-7571", volumeIdOfTree(Uri.parse("content://$EXTERNAL_STORAGE_AUTHORITY/tree/fff1-7571%3ARAW%2FOCC")))
        assertNull(volumeIdOfTree(Uri.parse("content://$EXTERNAL_STORAGE_AUTHORITY/document/FFF1-7571%3ARAW")))
    }

    @Test
    fun pickerPrefersGoogleDocumentsUiThenAospThenNone() {
        assertEquals("com.google.android.documentsui", documentsUiPackage { true })
        assertEquals("com.android.documentsui", documentsUiPackage { it == "com.android.documentsui" })
        assertNull(documentsUiPackage { false })
    }
}
