/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.CaptureNamingSettings
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.RawQualificationTarget
import com.librestatic.opencinecam.RecordingDestinationStatus
import com.librestatic.opencinecam.storage.ClipContinuation
import com.librestatic.opencinecam.storage.DestinationFallback
import com.librestatic.opencinecam.storage.DriveDisconnectPolicy
import com.librestatic.opencinecam.storage.EXTERNAL_STORAGE_AUTHORITY
import com.librestatic.opencinecam.storage.RawVideoQualificationStore
import com.librestatic.opencinecam.storage.RawVideoRecoveryReport
import com.librestatic.opencinecam.storage.RecordingDestination
import com.librestatic.opencinecam.storage.RecordingRecoveryReport
import com.librestatic.opencinecam.storage.resolveRecordingDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecordingDestinationWiringTest {
    private val drive = RecordingDestination.UsbFolder(Uri.parse("content://$EXTERNAL_STORAGE_AUTHORITY/tree/FFF1-7571%3ARAW"), "FFF1-7571")
    private val phone = RecordingDestination.Phone

    @Test fun idleMeasuresTheNextTakesDestination() {
        assertEquals(drive, freeSpaceDestination(takeDestination = null, nextDestination = drive))
        assertEquals(phone, freeSpaceDestination(takeDestination = null, nextDestination = phone))
    }

    @Test fun aRunningTakeMeasuresItsOwnDestinationWhateverComesNext() {
        // A take that fell back to the phone keeps measuring the phone after the drive is plugged in, and the reverse.
        assertEquals(phone, freeSpaceDestination(takeDestination = phone, nextDestination = drive))
        assertEquals(drive, freeSpaceDestination(takeDestination = drive, nextDestination = phone))
    }

    @Test fun aRawTakeMeasuresItsOwnDestinationToo() {
        // PLAN-072 Delivery 3: a RAW take holds its destination like an encoded one, so the ticker follows it to the drive.
        assertEquals(drive, freeSpaceDestination(takeDestination = drive, nextDestination = phone))
        assertEquals(phone, freeSpaceDestination(takeDestination = phone, nextDestination = drive))
    }

    @Test fun theNextRawDestinationIsTheDriveOnlyWhenItIsUsable() {
        assertEquals("saf:FFF1-7571", rawDestinationId(drive))
        assertEquals(RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS, rawDestinationId(phone))
        // A usable drive: its own records; absent or without the grant: the phone's, so phone-only records keep working.
        assertEquals("saf:FFF1-7571", rawDestinationId(resolveRecordingDestination(drive, setOf("fff1-7571"), true).destination))
        assertEquals(RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS,
            rawDestinationId(resolveRecordingDestination(drive, emptySet(), true).destination))
        assertEquals(RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS,
            rawDestinationId(resolveRecordingDestination(drive, setOf("FFF1-7571"), false).destination))
        assertEquals(RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS,
            rawDestinationId(resolveRecordingDestination(phone, setOf("FFF1-7571"), true).destination))
        // The volume id is case-insensitive on Android, so the key is upper-cased.
        assertEquals("saf:FFF1-7571", rawDestinationId(RecordingDestination.UsbFolder(drive.treeUri, "fff1-7571")))
    }

    @Test fun theQualificationTargetIsShownOnlyWithADriveConfigured() {
        assertNull(rawQualificationTarget(resolveRecordingDestination(phone, setOf("FFF1-7571"), true), "Elements"))
        assertEquals(RawQualificationTarget(drive = true, driveLabel = "Elements"),
            rawQualificationTarget(resolveRecordingDestination(drive, setOf("FFF1-7571"), true), "Elements"))
        assertEquals(RawQualificationTarget(drive = true), rawQualificationTarget(resolveRecordingDestination(drive, setOf("FFF1-7571"), true), " "))
        assertEquals(RawQualificationTarget(drive = false), rawQualificationTarget(resolveRecordingDestination(drive, emptySet(), true), "Elements"))
    }

    @Test fun leavingStopsOnlyADriveTakeOnThatVolume() {
        assertTrue(driveLeavingStopsTake(drive, "FFF1-7571"))
        assertTrue(driveLeavingStopsTake(drive, "fff1-7571"))
        assertFalse(driveLeavingStopsTake(drive, "AAAA-0001"))
        assertFalse(driveLeavingStopsTake(phone, "FFF1-7571"))
        assertFalse(driveLeavingStopsTake(null, "FFF1-7571"))
    }

    @Test fun aLostDriveIsItsOwnStopReason() {
        assertEquals(TakeStopReason.DRIVE_LOST, takeStopReason(driveLostFailure(writeFailed = false)))
        assertEquals(TakeStopReason.DRIVE_LOST, takeStopReason(driveLostFailure(writeFailed = true)))
        assertEquals("drive-disconnected", driveLostFailure(writeFailed = false).correlationId)
        assertEquals("drive-write-failed", driveLostFailure(writeFailed = true).correlationId)
    }

    @Test fun theFallbackNoticeNamesItsReasonAndJoinsTheWarningLineLast() {
        val notConnected = "USB drive not connected: recording to the phone"
        val lost = "Access to the USB drive was lost: recording to the phone"
        val starting = "USB drive starting: recording to the phone"
        val renamed = "Choose USB folder again: recording to the phone"
        assertNull(destinationFallbackNotice(null, notConnected, lost, starting, renamed))
        assertEquals(notConnected, destinationFallbackNotice(DestinationFallback.DRIVE_NOT_CONNECTED, notConnected, lost, starting, renamed))
        assertEquals(lost, destinationFallbackNotice(DestinationFallback.PERMISSION_LOST, notConnected, lost, starting, renamed))
        // A take that began while the drive was spinning up says so rather than "not connected".
        assertEquals(starting, destinationFallbackNotice(DestinationFallback.DRIVE_STARTING, notConnected, lost, starting, renamed))
        // A drive vold mounted under another volume name says the folder must be chosen again.
        assertEquals(renamed, destinationFallbackNotice(DestinationFallback.DRIVE_RENAMED, notConnected, lost, starting, renamed))
        assertEquals(notConnected, recordingWarningLine(null, null, null, listOfNotNull(destinationFallbackNotice(DestinationFallback.DRIVE_NOT_CONNECTED, notConnected, lost, starting, renamed))))
        assertEquals("Storage unknown · Cadence · $notConnected", recordingWarningLine("Storage unknown", null, null,
            listOf("Cadence") + listOfNotNull(destinationFallbackNotice(DestinationFallback.DRIVE_NOT_CONNECTED, notConnected, lost, starting, renamed))))
    }

    @Test fun theStatusDescribesTheResolvedDestinationWithoutUris() {
        assertEquals(RecordingDestinationStatus(), recordingDestinationStatus(resolveRecordingDestination(phone, emptySet(), true), "Elements", 5L))
        assertEquals(RecordingDestinationStatus(configuredDrive = true, onDrive = true, driveLabel = "Elements", driveFreeBytes = 5L),
            recordingDestinationStatus(resolveRecordingDestination(drive, setOf("FFF1-7571"), true), "Elements", 5L))
        assertEquals(RecordingDestinationStatus(configuredDrive = true, fallback = DestinationFallback.DRIVE_NOT_CONNECTED, driveLabel = "Elements"),
            recordingDestinationStatus(resolveRecordingDestination(drive, emptySet(), true), "Elements", 5L))
        assertEquals(RecordingDestinationStatus(configuredDrive = true, fallback = DestinationFallback.PERMISSION_LOST),
            recordingDestinationStatus(resolveRecordingDestination(drive, setOf("FFF1-7571"), false), null, null))
        assertEquals(RecordingDestinationStatus(configuredDrive = true, fallback = DestinationFallback.DRIVE_RENAMED, driveLabel = "Elements"),
            recordingDestinationStatus(resolveRecordingDestination(drive, setOf("FFF1-7571-01"), true), "Elements", 5L))
    }

    @Test fun aMountRecoveryAddsToTheStartupReport() {
        val raw = RawVideoRecoveryReport(1, 0, 0)
        val startup = RecordingRecoveryReport(discardedGroups = 1, incompleteGroups = 2, rawTakes = raw, deferredGroups = 3)
        val merged = mergeRecordingRecovery(startup, RecordingRecoveryReport(incompleteGroups = 2, unresolvedGroups = 1, deferredGroups = 1))
        assertEquals(RecordingRecoveryReport(discardedGroups = 1, unresolvedGroups = 1, incompleteGroups = 4, rawTakes = raw, deferredGroups = 1), merged)
        val later = RecordingRecoveryReport(incompleteGroups = 1)
        assertEquals(later, mergeRecordingRecovery(null, later))
        // RAW takes repaired on a mounted drive add to the phone's.
        assertEquals(RawVideoRecoveryReport(3, 1, 0),
            mergeRecordingRecovery(startup, RecordingRecoveryReport(rawTakes = RawVideoRecoveryReport(2, 1, 0))).rawTakes)
    }

    @Test fun stopAndSaveNeverContinues() {
        for (mode in CaptureMode.entries) for (highSpeed in listOf(false, true)) {
            assertEquals(DriveLossAction.StopAndSave,
                driveLossAction(DriveDisconnectPolicy.STOP_AND_SAVE, mode, highSpeed, rawQualifiedOnPhone = true, phoneRoom = true))
        }
    }

    @Test fun continueOnThePhoneTruthTable() {
        val policy = DriveDisconnectPolicy.CONTINUE_ON_PHONE
        fun action(mode: CaptureMode, highSpeed: Boolean = false, raw: Boolean = true, room: Boolean = true) =
            driveLossAction(policy, mode, highSpeed, raw, room)
        for (mode in listOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG)) {
            assertEquals(mode.name, DriveLossAction.Continue, action(mode, raw = false))
            assertEquals(mode.name, DriveLossAction.StopUnavailable(ContinuationUnavailable.HIGH_SPEED), action(mode, highSpeed = true))
            assertEquals(mode.name, DriveLossAction.StopUnavailable(ContinuationUnavailable.PHONE_FULL), action(mode, room = false))
        }
        // Time-lapse first: its own reason whatever else holds.
        assertEquals(DriveLossAction.StopUnavailable(ContinuationUnavailable.TIME_LAPSE), action(CaptureMode.TIME_LAPSE, highSpeed = true, room = false))
        // RAW: only when qualified on the phone and with room there; high speed does not apply to it.
        assertEquals(DriveLossAction.Continue, action(CaptureMode.RAW_VIDEO, highSpeed = true))
        assertEquals(DriveLossAction.StopUnavailable(ContinuationUnavailable.RAW_NOT_QUALIFIED), action(CaptureMode.RAW_VIDEO, raw = false, room = false))
        assertEquals(DriveLossAction.StopUnavailable(ContinuationUnavailable.PHONE_FULL), action(CaptureMode.RAW_VIDEO, room = false))
        // Modes that never record to the drive as a take cannot continue either.
        for (mode in CaptureMode.entries - setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG, CaptureMode.RAW_VIDEO, CaptureMode.TIME_LAPSE)) {
            assertEquals(mode.name, DriveLossAction.StopUnavailable(ContinuationUnavailable.MODE), action(mode))
        }
    }

    private val names = CaptureNameSnapshot(CaptureNamingSettings(), ProductionSlateSettings(), 0)
    private val parentId = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private fun request(index: Int = 1) = ContinuationRequest(CaptureMode.VIDEO, "OCC_${parentId}_cont$index",
        ClipContinuation(parentId, "OCC_$parentId.mp4", index, "drive-disconnected"), captureNames = names, audio = true)

    @Test fun aContinuationIsConsumedOnce() {
        val token = DriveContinuation({ 0L })
        assertFalse(token.isPending)
        assertNull(token.consume())
        assertTrue(token.arm(request()))
        assertTrue(token.isPending)
        // A second trigger for the same loss (the volume event and the failed write) arms nothing more.
        assertFalse(token.arm(request(2)))
        assertEquals(request(), token.consume())
        assertNull(token.consume())
        assertFalse(token.isPending)
    }

    @Test fun aUserStopVetoesTheTakeUntilTheNextStarts() {
        val token = DriveContinuation({ 0L })
        assertTrue(token.arm(request()))
        assertNotNull(token.veto())
        assertFalse(token.isPending)
        assertNull(token.consume())
        // Vetoed before the trigger (stopped by the operator as the drive left): nothing arms for this take.
        assertFalse(token.arm(request()))
        assertNull(token.veto())
        token.takeStarted()
        assertTrue(token.arm(request()))
    }

    @Test fun anUnconsumedContinuationExpires() {
        var now = 1_000L
        val token = DriveContinuation({ now }, ttlMs = 15_000L)
        assertTrue(token.arm(request()))
        now += 15_000L
        assertTrue(token.isPending)
        now += 1L
        assertFalse(token.isPending)
        assertNull(token.consume())
        // Expired is gone: a later loss arms again.
        assertTrue(token.arm(request()))
    }

    @Test fun aContinuationRecordsOnThePhoneEvenWithTheDriveBack() {
        val back = resolveRecordingDestination(drive, setOf("FFF1-7571"), true)
        val forced = continuationDestination(back)
        assertEquals(phone, forced.destination)
        assertEquals(drive, forced.configured)
        assertNull(forced.fallback)
        assertEquals(RecordingDestinationStatus(configuredDrive = true, driveLabel = "Elements", continuedOnPhone = true),
            recordingDestinationStatus(forced, "Elements", 5L, continuedOnPhone = true))
        // Never "continued" while on the drive.
        assertFalse(recordingDestinationStatus(back, "Elements", 5L, continuedOnPhone = true).continuedOnPhone)
    }
}
