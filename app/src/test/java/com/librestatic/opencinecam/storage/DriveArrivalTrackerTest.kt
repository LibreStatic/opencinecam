/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveArrivalTrackerTest {
    @Test
    fun nothingAttachedIsNotArriving() {
        val tracker = DriveArrivalTracker(windowMillis = 1_000L)
        assertFalse(tracker.arriving(0L))
        assertNull(tracker.nextExpiryMillis(0L))
    }

    @Test
    fun theWindowOpensAtAttachAndClosesAfterIt() {
        val tracker = DriveArrivalTracker(windowMillis = 1_000L)
        tracker.attached("/dev/bus/usb/001/002", 5_000L)
        assertTrue(tracker.arriving(5_000L))
        assertTrue(tracker.arriving(5_999L))
        assertEquals(6_000L, tracker.nextExpiryMillis(5_500L))
        assertFalse(tracker.arriving(6_000L))
        assertNull(tracker.nextExpiryMillis(6_000L))
    }

    @Test
    fun detachingEndsTheWindow() {
        val tracker = DriveArrivalTracker(windowMillis = 1_000L)
        tracker.attached("disk", 0L)
        tracker.detached("disk")
        assertFalse(tracker.arriving(10L))
        assertNull(tracker.nextExpiryMillis(10L))
        // Detaching a device never seen changes nothing.
        tracker.detached("other")
        assertFalse(tracker.arriving(10L))
    }

    @Test
    fun twoDevicesKeepTheirOwnWindows() {
        val tracker = DriveArrivalTracker(windowMillis = 1_000L)
        tracker.attached("a", 0L)
        tracker.attached("b", 600L)
        assertEquals(1_000L, tracker.nextExpiryMillis(100L))
        assertTrue(tracker.arriving(1_200L))
        assertEquals(1_600L, tracker.nextExpiryMillis(1_200L))
        tracker.detached("b")
        assertFalse(tracker.arriving(1_200L))
    }

    @Test
    fun reattachingRestartsTheWindow() {
        val tracker = DriveArrivalTracker(windowMillis = 1_000L)
        tracker.attached("disk", 0L)
        tracker.attached("disk", 2_000L)
        assertTrue(tracker.arriving(2_500L))
        assertEquals(3_000L, tracker.nextExpiryMillis(2_500L))
    }

    @Test
    fun aDeviceAttachedLongAgoDoesNotKeepItOpen() {
        // A hub's card reader stays attached with no volume; once its window closes it no longer counts.
        val tracker = DriveArrivalTracker()
        tracker.attached("hub-reader", 0L)
        assertTrue(tracker.arriving(DRIVE_ARRIVAL_WINDOW_MILLIS - 1))
        assertFalse(tracker.arriving(DRIVE_ARRIVAL_WINDOW_MILLIS * 10))
        assertNull(tracker.nextExpiryMillis(DRIVE_ARRIVAL_WINDOW_MILLIS * 10))
        // A disk plugged in later opens its own window; the reader's expiry is not offered again.
        tracker.attached("disk", DRIVE_ARRIVAL_WINDOW_MILLIS * 10)
        assertTrue(tracker.arriving(DRIVE_ARRIVAL_WINDOW_MILLIS * 10 + 1))
        assertEquals(DRIVE_ARRIVAL_WINDOW_MILLIS * 11, tracker.nextExpiryMillis(DRIVE_ARRIVAL_WINDOW_MILLIS * 10 + 1))
    }

    @Test
    fun massStorageIsTheDeviceClassOrAnyInterfaceClass() {
        assertTrue(isMassStorageDevice(8, emptyList()))
        assertTrue(isMassStorageDevice(0, listOf(8)))
        assertTrue(isMassStorageDevice(0, listOf(3, 8)))
        assertFalse(isMassStorageDevice(0, listOf(3, 1)))
        assertFalse(isMassStorageDevice(9, emptyList()))
    }
}
