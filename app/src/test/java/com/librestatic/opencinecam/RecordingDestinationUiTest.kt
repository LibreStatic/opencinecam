/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.storage.DestinationFallback
import com.librestatic.opencinecam.storage.DriveDisconnectPolicy
import com.librestatic.opencinecam.storage.DriveSpeed
import com.librestatic.opencinecam.storage.RecordingFolderAdoption
import com.librestatic.opencinecam.storage.RecordingFolderRejection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Robolectric: the holder's test hands it a [Uri] and it logs. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecordingDestinationUiTest {
    private val onDrive = RecordingDestinationStatus(configuredDrive = true, onDrive = true, driveLabel = "WD", driveFreeBytes = 500L)
    private val notConnected = RecordingDestinationStatus(configuredDrive = true, fallback = DestinationFallback.DRIVE_NOT_CONNECTED)
    private val accessLost = RecordingDestinationStatus(configuredDrive = true, fallback = DestinationFallback.PERMISSION_LOST)
    private val starting = RecordingDestinationStatus(configuredDrive = true, fallback = DestinationFallback.DRIVE_STARTING)
    private val renamed = RecordingDestinationStatus(configuredDrive = true, fallback = DestinationFallback.DRIVE_RENAMED)

    @Test fun everyRejectionHasItsOwnMessage() {
        val texts = RecordingFolderRejection.entries.map(::recordingFolderRejectionText)
        assertEquals(texts.size, texts.toSet().size)
        assertEquals(R.string.recording_folder_rejected_fat32, recordingFolderRejectionText(RecordingFolderRejection.FILE_SIZE_LIMIT))
        assertEquals(R.string.recording_folder_rejected_phone_storage, recordingFolderRejectionText(RecordingFolderRejection.PHONE_STORAGE))
        assertEquals(R.string.recording_folder_rejected_not_a_drive, recordingFolderRejectionText(RecordingFolderRejection.NOT_A_DRIVE))
    }

    @Test fun connectionFollowsTheServiceStatus() {
        assertNull(recordingDriveConnection(RecordingDestinationStatus()))
        assertEquals(RecordingDriveConnection.CONNECTED, recordingDriveConnection(onDrive))
        assertEquals(RecordingDriveConnection.NOT_CONNECTED, recordingDriveConnection(notConnected))
        assertEquals(RecordingDriveConnection.ACCESS_LOST, recordingDriveConnection(accessLost))
        // A drive spinning up is not usable yet: never CONNECTED, so Measure speed stays hidden.
        assertEquals(RecordingDriveConnection.STARTING, recordingDriveConnection(starting))
        // Nor is one mounted under another volume name: its folder must be chosen again.
        assertEquals(RecordingDriveConnection.RENAMED, recordingDriveConnection(renamed))
    }

    @Test fun noChipWithoutADrive() {
        for (mode in CaptureMode.entries) assertNull(mode.name, captureDestinationChip(mode, RecordingDestinationStatus()))
    }

    @Test fun chipShowsTheDriveOrTheFallback() {
        assertEquals(CaptureDestinationChipKind.DRIVE, captureDestinationChip(CaptureMode.VIDEO, onDrive))
        assertEquals(CaptureDestinationChipKind.DRIVE, captureDestinationChip(CaptureMode.PHOTO, onDrive))
        assertEquals(CaptureDestinationChipKind.PHONE_FALLBACK, captureDestinationChip(CaptureMode.LOG, notConnected))
        assertEquals(CaptureDestinationChipKind.PHONE_FALLBACK, captureDestinationChip(CaptureMode.VIDEO, starting))
        assertEquals(CaptureDestinationChipKind.PHONE_FALLBACK, captureDestinationChip(CaptureMode.VIDEO, renamed))
        assertEquals(CaptureDestinationChipKind.PHONE_FALLBACK, captureDestinationChip(CaptureMode.VIDEO, accessLost))
    }

    @Test fun rawVideoShowsTheDriveLikeEveryMode() {
        // PLAN-072 Delivery 3: RAW takes go to the drive too, so the chip and the fallback notice show in RAW video.
        assertEquals(CaptureDestinationChipKind.DRIVE, captureDestinationChip(CaptureMode.RAW_VIDEO, onDrive))
        assertEquals(CaptureDestinationChipKind.PHONE_FALLBACK, captureDestinationChip(CaptureMode.RAW_VIDEO, notConnected))
        assertEquals(R.string.recording_drive_fallback_not_connected,
            captureDestinationFallbackText(CameraUiState(selectedMode = CaptureMode.RAW_VIDEO, recordingDestination = notConnected), recording = false))
    }

    @Test fun aContinuedTakeShowsThePhoneAndItsNoticeWhileItRecords() {
        // PLAN-072 Delivery 4: the drive was lost and the take goes on on the phone, with no fallback reason.
        val continued = RecordingDestinationStatus(configuredDrive = true, driveLabel = "WD", continuedOnPhone = true)
        assertEquals(CaptureDestinationChipKind.PHONE_FALLBACK, captureDestinationChip(CaptureMode.VIDEO, continued))
        assertEquals(CaptureDestinationChipKind.PHONE_FALLBACK, captureDestinationChip(CaptureMode.RAW_VIDEO, continued))
        val recording = CameraUiState(selectedMode = CaptureMode.VIDEO, phase = CameraUiPhase.RECORDING, recordingDestination = continued)
        assertTrue(captureDestinationContinued(recording))
        assertTrue(captureDestinationContinued(recording.copy(selectedMode = CaptureMode.RAW_VIDEO, phase = CameraUiPhase.CAPTURING)))
        assertFalse(captureDestinationContinued(recording.copy(phase = CameraUiPhase.SAVED)))
        assertFalse(captureDestinationContinued(recording.copy(recordingDestination = onDrive)))
        // The idle fallback notice never doubles it.
        assertNull(captureDestinationFallbackText(recording, recording = true))
    }

    @Test fun fallbackNoticeOnlyWhenIdle() {
        val idle = CameraUiState(selectedMode = CaptureMode.VIDEO, recordingDestination = notConnected)
        assertEquals(R.string.recording_drive_fallback_not_connected, captureDestinationFallbackText(idle, recording = false))
        // A running take says it in its recording warning already.
        assertNull(captureDestinationFallbackText(idle, recording = true))
        assertEquals(R.string.recording_drive_fallback_permission_lost,
            captureDestinationFallbackText(idle.copy(recordingDestination = accessLost), recording = false))
        assertNull(captureDestinationFallbackText(idle.copy(recordingDestination = onDrive), recording = false))
        assertEquals(R.string.recording_drive_fallback_starting,
            captureDestinationFallbackText(idle.copy(recordingDestination = starting), recording = false))
        assertEquals(R.string.recording_drive_fallback_renamed,
            captureDestinationFallbackText(idle.copy(recordingDestination = renamed), recording = false))
    }

    @Test fun statusFreeSpaceIsTheDestinations() {
        val phone = CameraUiState(selectedMode = CaptureMode.VIDEO, availableStorageBytes = 100L)
        assertEquals(100L, captureStatusFreeBytes(phone))
        // Idle with the next take on the drive: the drive's figure, or none rather than the phone's.
        assertEquals(500L, captureStatusFreeBytes(phone.copy(recordingDestination = onDrive)))
        assertNull(captureStatusFreeBytes(phone.copy(recordingDestination = onDrive.copy(driveFreeBytes = null))))
        // While recording the service feeds the running take's destination.
        assertEquals(100L, captureStatusFreeBytes(phone.copy(phase = CameraUiPhase.RECORDING, recordingDestination = onDrive)))
        // RAW video writes to the drive too: idle, its figure; recording, the running take's.
        assertEquals(500L, captureStatusFreeBytes(phone.copy(selectedMode = CaptureMode.RAW_VIDEO, recordingDestination = onDrive)))
        assertEquals(100L, captureStatusFreeBytes(phone.copy(selectedMode = CaptureMode.RAW_VIDEO, phase = CameraUiPhase.RECORDING, recordingDestination = onDrive)))
        assertEquals(100L, captureStatusFreeBytes(phone.copy(selectedMode = CaptureMode.RAW_VIDEO, recordingDestination = notConnected)))
        assertEquals(100L, captureStatusFreeBytes(phone.copy(recordingDestination = notConnected)))
    }

    @Test fun changesWaitForTheTakeToEnd() {
        assertTrue(recordingDestinationChangeAllowed(CameraUiState(phase = CameraUiPhase.PREVIEWING)))
        assertFalse(recordingDestinationChangeAllowed(CameraUiState(phase = CameraUiPhase.RECORDING)))
    }

    @Test fun catalogHasBothRowsInRecordingAndFindsThemInBothLanguages() {
        for (id in listOf(RECORDING_DESTINATION_SETTING_ID, RECORDING_DRIVE_DISCONNECT_SETTING_ID)) {
            assertTrue(SettingsCatalog.entries.any { it.id == id && it.category == SettingsCategory.RECORDING })
        }
        for (query in listOf("ssd", "usb", "disco externo", "exfat", "carpeta", "almacenamiento", "destino")) {
            assertTrue(query, RECORDING_DESTINATION_SETTING_ID in SettingsCatalog.search(query, null) { "" })
        }
    }

    private class FakeBackend(var adoption: RecordingFolderAdoption) : RecordingDestinationSettingsBackend {
        var stored = RecordingDestinationSettingsState()
        /** What the next measurement gives; null is a failed one. */
        var nextSpeed: DriveSpeed? = null
        val events = mutableListOf<String>()
        /** The holder's state as the measurement starts. */
        var stateAtMeasure: RecordingDestinationSettingsState? = null
        var holder: RecordingDestinationSettingsHolder? = null
        override fun read() = stored
        override fun adopt(tree: Uri): RecordingFolderAdoption {
            events += "adopt"
            (adoption as? RecordingFolderAdoption.Adopted)?.let { stored = stored.copy(driveConfigured = true, label = it.label) }
            return adoption
        }
        override fun clear() { stored = stored.copy(driveConfigured = false, label = null, speed = null) }
        override fun setPolicy(policy: DriveDisconnectPolicy) { stored = stored.copy(policy = policy) }
        override fun measure(): DriveSpeed? {
            events += "measure"
            stateAtMeasure = holder?.state
            nextSpeed?.let { stored = stored.copy(speed = it) }
            return nextSpeed
        }
    }

    private val fast = DriveSpeed(412_000_000L, 400_000_000L, 1L)
    private val slow = DriveSpeed(9_000_000L, 8_000_000L, 2L)

    @Test fun adoptionTellsTheServiceBeforeMeasuring() = runBlocking {
        val backend = FakeBackend(RecordingFolderAdoption.Adopted("1A2B-3C4D", "WD Elements")).apply { nextSpeed = fast }
        val holder = RecordingDestinationSettingsHolder(backend).also { backend.holder = it }
        holder.adopt(Uri.EMPTY) { backend.events += "changed" }
        assertEquals(listOf("adopt", "changed", "measure"), backend.events)
        val during = backend.stateAtMeasure!!
        assertTrue(during.measuring)
        assertFalse(during.checking)
        assertTrue(during.driveConfigured)
        assertEquals(fast, holder.state.speed)
        assertFalse(holder.state.measuring)
        assertFalse(holder.state.checking)
    }

    @Test fun aRejectedFolderIsNeverMeasured() = runBlocking {
        val backend = FakeBackend(RecordingFolderAdoption.Rejected(RecordingFolderRejection.NOT_ENOUGH_SPACE)).apply { nextSpeed = fast }
        val holder = RecordingDestinationSettingsHolder(backend)
        holder.adopt(Uri.EMPTY) { backend.events += "changed" }
        assertEquals(listOf("adopt"), backend.events)
        assertNull(holder.state.speed)
        assertFalse(holder.state.measuring)
    }

    @Test fun aFailedMeasurementKeepsThePreviousSpeedAndRemeasureUpdatesIt() = runBlocking {
        val backend = FakeBackend(RecordingFolderAdoption.Adopted("1A2B-3C4D", "WD")).apply { nextSpeed = fast }
        val holder = RecordingDestinationSettingsHolder(backend)
        holder.adopt(Uri.EMPTY) {}
        assertEquals(fast, holder.state.speed)

        backend.nextSpeed = null
        holder.remeasure()
        assertEquals(fast, holder.state.speed)
        assertFalse(holder.state.measuring)

        backend.nextSpeed = slow
        holder.remeasure()
        assertEquals(slow, holder.state.speed)
        assertFalse(holder.state.measuring)
        // A failed measurement after an adoption keeps the slow figure too; a slow drive is still adopted.
        backend.nextSpeed = null
        holder.adopt(Uri.EMPTY) {}
        assertEquals(slow, holder.state.speed)
        assertTrue(holder.state.driveConfigured)
        assertNull(holder.state.rejection)
    }

    @Test fun remeasureNeedsADrive() = runBlocking {
        val backend = FakeBackend(RecordingFolderAdoption.Adopted("1A2B-3C4D", "WD")).apply { nextSpeed = fast }
        val holder = RecordingDestinationSettingsHolder(backend)
        holder.remeasure()
        assertTrue(backend.events.isEmpty())
        assertNull(holder.state.speed)
    }

    @Test fun holderKeepsTheRejectionAndTellsTheServiceOnlyOnChange() = runBlocking {
        val backend = FakeBackend(RecordingFolderAdoption.Rejected(RecordingFolderRejection.FILE_SIZE_LIMIT))
        val holder = RecordingDestinationSettingsHolder(backend)
        var changes = 0
        holder.adopt(Uri.EMPTY) { changes++ }
        assertEquals(RecordingFolderRejection.FILE_SIZE_LIMIT, holder.state.rejection)
        assertFalse(holder.state.driveConfigured)
        assertFalse(holder.state.checking)
        assertEquals(0, changes)

        backend.adoption = RecordingFolderAdoption.Adopted("1A2B-3C4D", "WD Elements")
        holder.adopt(Uri.EMPTY) { changes++ }
        assertNull(holder.state.rejection)
        assertTrue(holder.state.driveConfigured)
        assertEquals("WD Elements", holder.state.label)
        assertEquals(1, changes)

        holder.clear { changes++ }
        assertFalse(holder.state.driveConfigured)
        assertEquals(2, changes)
    }
}
