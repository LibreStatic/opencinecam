/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */
package com.librestatic.opencinecam

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.storage.DestinationFallback
import com.librestatic.opencinecam.storage.DriveDisconnectPolicy
import com.librestatic.opencinecam.storage.DriveSpeed
import com.librestatic.opencinecam.storage.driveSpeedTestFits
import com.librestatic.opencinecam.storage.driveTooSlow
import com.librestatic.opencinecam.storage.RecordingDestination
import com.librestatic.opencinecam.storage.RecordingDestinationStore
import com.librestatic.opencinecam.storage.RecordingFolderAdoption
import com.librestatic.opencinecam.storage.RecordingFolderPicker
import com.librestatic.opencinecam.storage.RecordingFolderRejection
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import com.librestatic.opencinecam.ui.viewfinder.chromePanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val RECORDING_DESTINATION_SETTING_ID = "recording-destination"
internal const val RECORDING_DRIVE_DISCONNECT_SETTING_ID = "recording-drive-disconnect"

/** PLAN-072: the message for a folder the drive check turned down; one plain sentence each, with what to do. */
internal fun recordingFolderRejectionText(reason: RecordingFolderRejection): Int = when (reason) {
    RecordingFolderRejection.NOT_A_DRIVE -> R.string.recording_folder_rejected_not_a_drive
    RecordingFolderRejection.PHONE_STORAGE -> R.string.recording_folder_rejected_phone_storage
    RecordingFolderRejection.PERMISSION_DENIED -> R.string.recording_folder_rejected_permission
    RecordingFolderRejection.NOT_WRITABLE -> R.string.recording_folder_rejected_not_writable
    RecordingFolderRejection.FILE_SIZE_LIMIT -> R.string.recording_folder_rejected_fat32
    RecordingFolderRejection.NOT_ENOUGH_SPACE -> R.string.recording_folder_rejected_no_space
}

/**
 * How the configured drive stands, as Settings shows it. [STARTING]: attached but not mounted yet (a disk spinning up).
 * [RENAMED]: mounted under another volume name, so the folder must be chosen again ([DestinationFallback.DRIVE_RENAMED]).
 */
internal enum class RecordingDriveConnection { CONNECTED, NOT_CONNECTED, STARTING, RENAMED, ACCESS_LOST }

/**
 * The configured drive's state from the service's [status]; null when the service has not described a configured
 * drive yet (just after a folder was picked, or with no service), so Settings claims nothing.
 */
internal fun recordingDriveConnection(status: RecordingDestinationStatus): RecordingDriveConnection? = when {
    !status.configuredDrive -> null
    status.fallback == DestinationFallback.PERMISSION_LOST -> RecordingDriveConnection.ACCESS_LOST
    status.fallback == DestinationFallback.DRIVE_NOT_CONNECTED -> RecordingDriveConnection.NOT_CONNECTED
    status.fallback == DestinationFallback.DRIVE_STARTING -> RecordingDriveConnection.STARTING
    status.fallback == DestinationFallback.DRIVE_RENAMED -> RecordingDriveConnection.RENAMED
    else -> RecordingDriveConnection.CONNECTED
}

/** The HUD's destination chip: the drive in use, or the phone standing in for a configured drive. */
internal enum class CaptureDestinationChipKind { DRIVE, PHONE_FALLBACK }

/**
 * Null with no drive configured, so the HUD stays as it was for operators without one. Every recording mode, RAW video
 * included (PLAN-072 Delivery 3), writes where the chip says.
 */
@Suppress("UNUSED_PARAMETER")
internal fun captureDestinationChip(mode: CaptureMode, status: RecordingDestinationStatus): CaptureDestinationChipKind? = when {
    status.onDrive -> CaptureDestinationChipKind.DRIVE
    // A continuation (PLAN-072 Delivery 4) records on the phone in place of the drive it lost.
    status.configuredDrive && (status.fallback != null || status.continuedOnPhone) -> CaptureDestinationChipKind.PHONE_FALLBACK
    else -> null
}

/**
 * The free space the status line and the time left use: the running take's destination while recording (the service
 * feeds it), the drive's own figure when the next take, encoded or RAW, goes to the drive (null when its provider gives
 * none: better no figure than the phone's), otherwise the phone's.
 */
internal fun captureStatusFreeBytes(state: CameraUiState): Long? = when {
    state.phase == CameraUiPhase.RECORDING -> state.availableStorageBytes
    state.recordingDestination.onDrive -> state.recordingDestination.driveFreeBytes
    else -> state.availableStorageBytes
}

/** A running take may be writing to the configured folder, whose permission a change would release. */
internal fun recordingDestinationChangeAllowed(state: CameraUiState): Boolean =
    !state.structuralSettingsFrozen && state.phase != CameraUiPhase.CAPTURING

/**
 * What the Recording destination rows show of the store (with the configured drive's last measured [speed]), plus the
 * drive check or speed measurement in progress and the last refusal.
 */
internal data class RecordingDestinationSettingsState(
    val driveConfigured: Boolean = false,
    val label: String? = null,
    val policy: DriveDisconnectPolicy = DriveDisconnectPolicy.DEFAULT,
    val checking: Boolean = false,
    val rejection: RecordingFolderRejection? = null,
    val speed: DriveSpeed? = null,
    val measuring: Boolean = false,
)

/** The store's side of the rows; blocking, called off the main thread except [read] and [setPolicy]. */
internal interface RecordingDestinationSettingsBackend {
    fun read(): RecordingDestinationSettingsState
    fun adopt(tree: Uri): RecordingFolderAdoption
    fun clear()
    fun setPolicy(policy: DriveDisconnectPolicy)
    /** Measures the configured drive's write speed and keeps it; null when it could not be measured. */
    fun measure(): DriveSpeed?
}

private class StoreBackend(private val store: RecordingDestinationStore) : RecordingDestinationSettingsBackend {
    override fun read() = RecordingDestinationSettingsState(
        driveConfigured = store.configured() is RecordingDestination.UsbFolder, label = store.label(), policy = store.policy(),
        speed = store.speed())
    override fun adopt(tree: Uri) = store.adopt(tree)
    override fun measure() = store.measure()
    override fun clear() = store.clear()
    override fun setPolicy(policy: DriveDisconnectPolicy) = store.setPolicy(policy)
}

/**
 * The rows' state and actions. [adopt] runs the drive check (it writes a 4 GiB sparse file) on the IO dispatcher and
 * finishes even if Settings closes meanwhile, so the service is always told ([onChanged]). An adopted drive is then
 * measured (about six seconds): the service is told first, so it uses the drive at once, and a slow or failed
 * measurement never undoes the adoption.
 */
@Stable
internal class RecordingDestinationSettingsHolder(private val backend: RecordingDestinationSettingsBackend) {
    var state by mutableStateOf(backend.read())
        private set

    suspend fun adopt(tree: Uri, onChanged: () -> Unit) = withContext(NonCancellable) {
        state = state.copy(checking = true, rejection = null)
        val result = withContext(Dispatchers.IO) {
            try { backend.adopt(tree) } catch (failure: CancellationException) { throw failure } catch (failure: Exception) {
                android.util.Log.e("RecordingDestination", "The drive check failed", failure)
                RecordingFolderAdoption.Rejected(RecordingFolderRejection.NOT_WRITABLE)
            }
        }
        if (result is RecordingFolderAdoption.Rejected) {
            state = withContext(Dispatchers.IO) { backend.read() }.copy(rejection = result.reason)
            return@withContext
        }
        if (result is RecordingFolderAdoption.Superseded) {
            // A later choice (another folder, or the phone) stands and has told the service itself.
            state = withContext(Dispatchers.IO) { backend.read() }
            return@withContext
        }
        state = withContext(Dispatchers.IO) { backend.read() }.copy(measuring = true)
        withContext(Dispatchers.IO) { onChanged() }
        measureAndPublish()
    }

    /** Measures the configured drive again (the "Measure speed" button); no-op while a check or measurement runs. */
    suspend fun remeasure() = withContext(NonCancellable) {
        if (state.checking || state.measuring || !state.driveConfigured) return@withContext
        state = state.copy(measuring = true, rejection = null)
        measureAndPublish()
    }

    /** A failed measurement keeps the previous speed (or none). */
    private suspend fun measureAndPublish() {
        val (measured, stored) = withContext(Dispatchers.IO) {
            val speed = try { backend.measure() } catch (failure: CancellationException) { throw failure } catch (failure: Exception) {
                android.util.Log.e("RecordingDestination", "The drive speed test failed", failure)
                null
            }
            speed to backend.read()
        }
        state = stored.copy(speed = measured ?: stored.speed, measuring = false)
    }

    suspend fun clear(onChanged: () -> Unit) {
        state = state.copy(rejection = null)
        val next = withContext(Dispatchers.IO + NonCancellable) {
            backend.clear()
            onChanged()
            backend.read()
        }
        state = next
    }

    fun setPolicy(policy: DriveDisconnectPolicy, onChanged: () -> Unit) {
        backend.setPolicy(policy)
        state = state.copy(policy = policy)
        onChanged()
    }
}

/** Lets the driver render the rows with a fake store; production leaves it null and reads [RecordingDestinationStore]. */
internal val LocalRecordingDestinationSettings = compositionLocalOf<RecordingDestinationSettingsHolder?> { null }

@Composable
internal fun rememberRecordingDestinationSettings(): RecordingDestinationSettingsHolder {
    val provided = LocalRecordingDestinationSettings.current
    val context = LocalContext.current
    return provided ?: remember(context) { RecordingDestinationSettingsHolder(StoreBackend(RecordingDestinationStore(context))) }
}

/**
 * Settings > Recording: Phone or USB drive. Choosing the drive with none set opens the system folder picker; the
 * picked folder is checked before it is used. [onChanged] tells the service to re-read the destination.
 */
@Composable
internal fun RecordingDestinationSettings(
    holder: RecordingDestinationSettingsHolder,
    status: RecordingDestinationStatus,
    enabled: Boolean,
    requiredBytesPerSecond: Long?,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val changed by rememberUpdatedState(onChanged)
    val picker = rememberLauncherForActivityResult(RecordingFolderPicker()) { tree ->
        tree?.let { scope.launch { holder.adopt(it) { changed() } } }
    }
    val pick = { runCatching { picker.launch(null) }.onFailure { android.util.Log.w("RecordingDestination", "No folder picker", it) }; Unit }
    RecordingDestinationSettingsContent(holder.state, recordingDriveConnection(status), status.driveFreeBytes, enabled,
        requiredBytesPerSecond,
        onPhone = { scope.launch { holder.clear { changed() } } },
        onPickFolder = pick,
        onMeasure = { scope.launch { holder.remeasure() } })
}

/** The Recording destination row without the store: the driver renders it with fake state. */
@Composable
internal fun RecordingDestinationSettingsContent(
    state: RecordingDestinationSettingsState,
    connection: RecordingDriveConnection?,
    freeBytes: Long?,
    enabled: Boolean,
    requiredBytesPerSecond: Long?,
    onPhone: () -> Unit,
    onPickFolder: () -> Unit,
    onMeasure: () -> Unit = {},
) {
    val context = LocalContext.current
    // A measurement writes to the folder, so neither the destination nor the folder changes under it.
    val usable = enabled && !state.checking && !state.measuring
    Column(Modifier.fillMaxWidth().testTag("settings-recording-destination"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsOptionRow(
            title = stringResource(R.string.recording_destination_title),
            summary = stringResource(if (state.driveConfigured) R.string.recording_destination_summary_usb else R.string.recording_destination_summary_phone),
            choices = listOf(false, true),
            selected = state.driveConfigured,
            label = { usb -> stringResource(if (usb) R.string.recording_destination_usb else R.string.recording_destination_phone) },
            tag = { usb -> if (usb) "recording-destination-usb" else "recording-destination-phone" },
            onSelect = { usb ->
                when {
                    usb && !state.driveConfigured -> onPickFolder()
                    !usb && state.driveConfigured -> onPhone()
                }
            },
            enabled = usable,
        )
        if (state.checking) Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag("recording-destination-checking"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.recording_destination_checking), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        }
        if (state.measuring) Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag("recording-destination-measuring"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.recording_destination_measuring), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        }
        if (state.driveConfigured) {
            Column(Modifier.fillMaxWidth().testTag("recording-destination-drive"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(state.label ?: stringResource(R.string.recording_destination_usb), color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("recording-destination-label"))
                val colors = LocalCineColors.current
                val (text, color) = when (connection) {
                    RecordingDriveConnection.CONNECTED -> (freeBytes?.let {
                        stringResource(R.string.recording_destination_connected_free, Formatter.formatShortFileSize(context, it))
                    } ?: stringResource(R.string.recording_destination_connected)) to colors.ok
                    RecordingDriveConnection.NOT_CONNECTED -> stringResource(R.string.recording_destination_not_connected) to colors.pending
                    RecordingDriveConnection.STARTING -> stringResource(R.string.recording_destination_starting) to colors.pending
                    RecordingDriveConnection.RENAMED -> stringResource(R.string.recording_destination_renamed) to colors.pending
                    RecordingDriveConnection.ACCESS_LOST -> stringResource(R.string.recording_destination_access_lost) to MaterialTheme.colorScheme.error
                    null -> null to Color.Unspecified
                }
                if (text != null) Text(text, color = color, fontSize = 14.sp, modifier = Modifier.testTag("recording-destination-status"))
                state.speed?.let { speed ->
                    // A no-break space keeps "10.0 MB/s" on one line when the sentence wraps.
                    val rate = { bytesPerSecond: Long -> megabytesPerSecond(bytesPerSecond).replace(' ', '\u00A0') }
                    val measured = rate(speed.medianBytesPerSecond)
                    if (requiredBytesPerSecond != null && driveTooSlow(speed, requiredBytesPerSecond)) {
                        Text(stringResource(R.string.recording_destination_speed_slow, measured, rate(requiredBytesPerSecond)),
                            color = colors.pending, fontSize = 14.sp, modifier = Modifier.testTag("recording-destination-speed-slow"))
                    } else {
                        Text(stringResource(R.string.recording_destination_speed, measured), color = SettingsMuted, fontSize = 14.sp,
                            modifier = Modifier.testTag("recording-destination-speed"))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFolder, enabled = usable, modifier = Modifier.heightIn(min = 48.dp).testTag("recording-destination-change")) {
                    Text(stringResource(R.string.recording_destination_change))
                }
                if (connection == RecordingDriveConnection.CONNECTED) {
                    TextButton(onClick = onMeasure, enabled = usable && driveSpeedTestFits(freeBytes), modifier = Modifier.heightIn(min = 48.dp).testTag("recording-destination-measure")) {
                        Text(stringResource(R.string.recording_destination_measure))
                    }
                }
            }
            // The measurement writes a few GB; below that it would be skipped, so say why the button is off.
            if (connection == RecordingDriveConnection.CONNECTED && !driveSpeedTestFits(freeBytes)) {
                Text(stringResource(R.string.recording_destination_measure_no_space), color = SettingsMuted, fontSize = 13.sp,
                    modifier = Modifier.testTag("recording-destination-measure-no-space"))
            }
        }
        state.rejection?.let { reason ->
            Text(stringResource(recordingFolderRejectionText(reason)), color = MaterialTheme.colorScheme.error, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("recording-destination-error"))
        }
        SettingsHelp(stringResource(R.string.recording_destination_help_summary), stringResource(R.string.recording_destination_help),
            tag = "recording-destination-help")
    }
}

/**
 * Settings > Recording, shown with a drive configured: what a take does when the drive drops out. The summary describes
 * the chosen policy; the help also says which takes cannot continue on the phone (PLAN-072 Delivery 4).
 */
@Composable
internal fun RecordingDriveDisconnectSettings(policy: DriveDisconnectPolicy, enabled: Boolean, onSelect: (DriveDisconnectPolicy) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("settings-recording-drive-disconnect"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsOptionRow(
            title = stringResource(R.string.recording_drive_disconnect_title),
            summary = stringResource(if (policy == DriveDisconnectPolicy.CONTINUE_ON_PHONE) R.string.recording_drive_disconnect_continue_summary
                else R.string.recording_drive_disconnect_summary),
            choices = DriveDisconnectPolicy.entries,
            selected = policy,
            label = { stringResource(if (it == DriveDisconnectPolicy.STOP_AND_SAVE) R.string.recording_drive_disconnect_stop else R.string.recording_drive_disconnect_continue) },
            tag = { "recording-drive-disconnect-${it.name.lowercase()}" },
            onSelect = onSelect,
            enabled = enabled,
        )
        SettingsHelp(stringResource(R.string.recording_drive_disconnect_help_summary), stringResource(R.string.recording_drive_disconnect_help),
            tag = "recording-drive-disconnect-help")
    }
}

/** The status line's destination chip: `USB` on the drive (never `SSD`: the drive may be a hard disk or a stick), an amber `PHONE` when a configured drive cannot be used. */
@Composable
internal fun CaptureDestinationChip(state: CameraUiState, modifier: Modifier = Modifier) {
    val kind = captureDestinationChip(state.selectedMode, state.recordingDestination) ?: return
    val drive = kind == CaptureDestinationChipKind.DRIVE
    val description = if (drive) stringResource(R.string.capture_destination_drive_description)
        else stringResource(R.string.capture_destination_phone_description)
    Text(
        if (drive) "USB" else stringResource(R.string.capture_destination_phone_chip),
        color = if (drive) MaterialTheme.colorScheme.onSurface else LocalCineColors.current.pending,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        fontWeight = if (drive) FontWeight.SemiBold else FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f).chromePanel(), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics { contentDescription = description }
            .testTag("capture-destination-chip"),
    )
}

/** Why the next take goes to the phone, idle only: a running take says it in its recording warning. */
internal fun captureDestinationFallbackText(state: CameraUiState, recording: Boolean): Int? {
    if (recording || captureDestinationChip(state.selectedMode, state.recordingDestination) != CaptureDestinationChipKind.PHONE_FALLBACK) return null
    return when (state.recordingDestination.fallback) {
        DestinationFallback.DRIVE_NOT_CONNECTED -> R.string.recording_drive_fallback_not_connected
        DestinationFallback.DRIVE_STARTING -> R.string.recording_drive_fallback_starting
        DestinationFallback.DRIVE_RENAMED -> R.string.recording_drive_fallback_renamed
        DestinationFallback.PERMISSION_LOST -> R.string.recording_drive_fallback_permission_lost
        null -> null
    }
}

/** PLAN-072 Delivery 4: the running take continues on the phone after its drive was lost; shown until it stops. */
internal fun captureDestinationContinued(state: CameraUiState): Boolean =
    state.recordingDestination.continuedOnPhone && state.phase in setOf(CameraUiPhase.RECORDING, CameraUiPhase.CAPTURING)

/** Placed like the fallback notice, in the instrument stack under the zoom. */
@Composable
internal fun CaptureDestinationContinuedNotice(modifier: Modifier = Modifier) =
    InstrumentNotice(stringResource(R.string.recording_drive_continued), CineIcon.WARNING, LocalCineColors.current.pending,
        "capture-destination-continued", modifier)

/** Placed like the graph fallback notice, in the instrument stack under the zoom. */
@Composable
internal fun CaptureDestinationFallbackNotice(text: Int, modifier: Modifier = Modifier) =
    InstrumentNotice(stringResource(text), CineIcon.WARNING, LocalCineColors.current.pending, "capture-destination-fallback", modifier)
