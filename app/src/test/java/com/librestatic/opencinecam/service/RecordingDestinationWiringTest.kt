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

    @Test fun aTakeWhoseDriveLeftWhileStartingIsStoppedOnceItRecords() {
        // The Leaving event arrived during CAPTURING: the flag stops the take even if the drive is back in the set.
        assertTrue(takeDriveLostAtStart(lostAtStart = true, drive, mounted = setOf("FFF1-7571"), monitored = true))
        // A missed event: the take's drive is no longer mounted (ids compared without case).
        assertTrue(takeDriveLostAtStart(lostAtStart = false, drive, mounted = emptySet(), monitored = true))
        assertFalse(takeDriveLostAtStart(lostAtStart = false, drive, mounted = setOf("fff1-7571"), monitored = true))
        // No volume monitor: nothing reads as unmounted.
        assertFalse(takeDriveLostAtStart(lostAtStart = false, drive, mounted = emptySet(), monitored = false))
        // A phone take, or no take, never stops for a drive.
        assertFalse(takeDriveLostAtStart(lostAtStart = true, phone, mounted = emptySet(), monitored = true))
        assertFalse(takeDriveLostAtStart(lostAtStart = true, null, mounted = emptySet(), monitored = true))
    }

    @Test fun recoveryRequestsCoalesceIntoOnePendingPassWithoutLosingAnItem() {
        val queued = ArrayDeque<Runnable>()
        val executor = java.util.concurrent.Executor { queued.addLast(it) }
        val passes = mutableListOf<List<String>>()
        val pass = CoalescedPass<String>(executor) { passes += it }
        assertTrue(pass.request("start"))
        assertTrue(pass.request("A mounted"))
        assertTrue(pass.request("B mounted"))
        assertEquals("a burst queues one pass", 1, queued.size)
        queued.removeFirst().run()
        assertEquals(listOf(listOf("start", "A mounted", "B mounted")), passes)
        // With nothing waiting, the next request queues a pass again.
        pass.request("take relinquished")
        assertEquals(1, queued.size)
        queued.removeFirst().run()
        assertEquals(listOf("take relinquished"), passes.last())
        // A pass that starts clears its mark before draining, so a request racing it queues a follow-up.
        lateinit var follow: CoalescedPass<String>
        follow = CoalescedPass(java.util.concurrent.Executor { queued.addLast(it) }) { items ->
            passes += items
            if (items == listOf("first")) follow.request("during")
        }
        follow.request("first")
        queued.removeFirst().run()
        assertEquals("the request during the run queued a follow-up", 1, queued.size)
        queued.removeFirst().run()
        assertEquals(listOf("during"), passes.last())
    }

    @Test fun aRefusedRecoveryPassKeepsItsItemsForTheNextRequest() {
        var refuse = true
        val queued = ArrayDeque<Runnable>()
        val executor = java.util.concurrent.Executor {
            if (refuse) throw java.util.concurrent.RejectedExecutionException("full") else queued.addLast(it)
        }
        val passes = mutableListOf<List<String>>()
        val pass = CoalescedPass<String>(executor) { passes += it }
        assertFalse(pass.request("A mounted"))
        refuse = false
        assertTrue(pass.request("B mounted"))
        queued.removeFirst().run()
        assertEquals(listOf(listOf("A mounted", "B mounted")), passes)
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

    @Test fun aPendingTakeSeenByTwoPassesIsCountedOnce() {
        val startup = RecordingRecoveryReport(unresolvedGroups = 1, deferredGroups = 2)
        // The mount pass meets the same journal entry again.
        val mounted = mergeRecordingRecovery(startup, RecordingRecoveryReport(unresolvedGroups = 1, incompleteGroups = 1))
        assertEquals(RecordingRecoveryReport(unresolvedGroups = 1, incompleteGroups = 1), mounted)
        // The RAW pass before a RAW take reads no journal: what is pending stays as the last journal pass left it.
        val raw = RawVideoRecoveryReport(1, 0, 0)
        assertEquals(startup.copy(rawTakes = raw), mergeRecordingRecovery(startup, RecordingRecoveryReport(rawTakes = raw), journalPass = false))
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

    @Test fun aPendingContinuationIsReadWithoutConsumingIt() {
        var now = 0L
        val token = DriveContinuation({ now }, ttlMs = 15_000L)
        assertNull(token.pendingRequest)
        assertTrue(token.arm(request()))
        assertEquals(request(), token.pendingRequest)
        assertEquals(request(), token.pendingRequest)
        now += 15_001L
        assertNull(token.pendingRequest)
    }

    @Test fun theRawGateKeepsTheDriveTakesConfigurationThroughItsContinuation() {
        val raw = request().copy(mode = CaptureMode.RAW_VIDEO, rawConfigKey = "full@24")
        // Automatic (an empty setting) would choose the phone's best; the continuation records what the drive take did.
        assertEquals("full@24", rawVideoGateKey("", raw))
        assertEquals("full@24", rawVideoGateKey("full@30", raw))
        assertEquals("", rawVideoGateKey("", null))
        assertEquals("full@30", rawVideoGateKey("full@30", null))
        // An encoded take's continuation says nothing about RAW.
        assertEquals("full@30", rawVideoGateKey("full@30", request()))
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

    @Test fun theDriveFreeSpaceIsSampledOffTheCallerOneAtATime() {
        val queued = ArrayDeque<Runnable>()
        val samples = ArrayDeque(listOf(500L, 400L))
        var calls = 0
        val cache = DriveFreeSpaceCache({ queued.addLast(it) }) { calls++; samples.removeFirst() }
        // Nothing before the first sample, and the read itself never samples.
        assertNull(cache.read(drive))
        assertEquals(0, calls)
        assertNull(cache.read(drive))
        assertEquals("one sample in flight", 1, queued.size)
        queued.removeFirst().run()
        assertEquals(500L, cache.read(drive))
        queued.removeFirst().run()
        assertEquals(400L, cache.read(drive))
        assertEquals(2, calls)
    }

    @Test fun anAdmissionReadWaitsForAFreshSampleButNeverForAStalledDrive() {
        val threads = java.util.concurrent.Executors.newSingleThreadExecutor()
        val release = java.util.concurrent.CountDownLatch(1)
        var stall = false
        try {
            val cache = DriveFreeSpaceCache(threads) { if (stall) { release.await(); 1L } else 700L }
            // A drive that answers: the fresh sample is returned and kept.
            assertEquals(700L, cache.readFresh(drive, 5_000L))
            assertEquals(700L, cache.read(drive))
            // A drive that stalls reads as unread within the wait, and the late sample is dropped.
            stall = true
            val started = System.nanoTime()
            assertNull(cache.readFresh(drive, 100L))
            assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(5))
            release.countDown()
            stall = false
        } finally {
            threads.shutdownNow()
        }
        // An executor that refuses the sample reads as unread at once.
        val refusing = DriveFreeSpaceCache({ throw java.util.concurrent.RejectedExecutionException() }) { 700L }
        assertNull(refusing.readFresh(drive, 5_000L))
    }

    @Test fun aDriveFreeSpaceSampleBelongsToItsDriveAndIsForgottenWhenItsVolumeChanges() {
        val queued = ArrayDeque<Runnable>()
        val other = RecordingDestination.UsbFolder(Uri.parse("content://$EXTERNAL_STORAGE_AUTHORITY/tree/0A1B-2C3D%3ARAW"), "0A1B-2C3D")
        val cache = DriveFreeSpaceCache({ queued.addLast(it) }) { if (it == drive) 500L else 900L }
        cache.store(drive, 500L)
        assertEquals(500L, cache.read(drive))
        // Another drive never reports the first one's figure, nor gets it from a sample still running for the first.
        assertNull(cache.read(other))
        queued.removeFirst().run()
        assertNull(cache.read(other))
        queued.removeFirst().run()
        assertEquals(900L, cache.read(other))
        // Its volume leaving or mounting again forgets the sample, and drops the one still running.
        cache.invalidate("0a1b-2c3d")
        queued.removeFirst().run()
        assertNull(cache.read(other))
        queued.removeFirst().run()
        // Another volume changing keeps it.
        cache.invalidate("FFF1-7571")
        assertEquals(900L, cache.read(other))
    }

    @Test fun aStalledDriveFreeSpaceSampleDoesNotHoldBackAnotherDrives() {
        val queued = ArrayDeque<Runnable>()
        val other = RecordingDestination.UsbFolder(Uri.parse("content://$EXTERNAL_STORAGE_AUTHORITY/tree/0A1B-2C3D%3ARAW"), "0A1B-2C3D")
        val cache = DriveFreeSpaceCache({ queued.addLast(it) }) { if (it == drive) 500L else 900L }
        assertNull(cache.read(drive))
        // The first drive's sample never returns; the drive chosen after it is sampled all the same.
        val stalled = queued.removeFirst()
        assertNull(cache.read(other))
        queued.removeFirst().run()
        assertEquals(900L, cache.read(other))
        queued.removeFirst().run()
        // When the stalled sample does return, it changes nothing and a sample in flight stays the only one.
        assertEquals(900L, cache.read(other))
        stalled.run()
        assertEquals(900L, cache.read(other))
        assertEquals(1, queued.size)
    }

    @Test fun aRefusedDriveFreeSpaceSampleLetsTheNextReadTryAgain() {
        var accept = false
        val queued = ArrayDeque<Runnable>()
        val cache = DriveFreeSpaceCache({ if (accept) queued.addLast(it) else throw java.util.concurrent.RejectedExecutionException() }) { 7L }
        assertNull(cache.read(drive))
        accept = true
        assertNull(cache.read(drive))
        queued.removeFirst().run()
        assertEquals(7L, cache.read(drive))
    }
}
