/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.format.Formatter
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.librestatic.opencinecam.CineGlyph
import com.librestatic.opencinecam.CineIcon
import com.librestatic.opencinecam.GalleryWarning
import com.librestatic.opencinecam.MediaDialogFrame
import com.librestatic.opencinecam.MediaDialogTake
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.SettingsAccent
import com.librestatic.opencinecam.SettingsMuted
import com.librestatic.opencinecam.SettingsPill
import com.librestatic.opencinecam.SettingsPillRow
import com.librestatic.opencinecam.SettingsSwitchRow
import com.librestatic.opencinecam.media.raw.NativeRawCodec
import com.librestatic.opencinecam.storage.CINEMADNG_EXPORT_PATH
import com.librestatic.opencinecam.storage.CINEMADNG_EXPORT_PREFS
import com.librestatic.opencinecam.storage.CINEMADNG_EXPORT_TREE_KEY
import com.librestatic.opencinecam.storage.LocalMediaTake
import com.librestatic.opencinecam.storage.safFreeBytes
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Where the operator chose to export to. */
internal sealed interface CinemaDngDestinationChoice {
    data object Documents : CinemaDngDestinationChoice

    /** A tree the app holds a persisted write permission for; [label] is its display name. */
    data class Folder(val uri: String, val label: String) : CinemaDngDestinationChoice
}

/** What the export dialog needs from the platform, behind a seam so the driver feeds it fakes. */
internal interface CinemaDngExportEnvironment {
    val lj92Available: Boolean
    val states: StateFlow<CinemaDngExportState>
    fun recording(): Boolean
    suspend fun plan(take: LocalMediaTake): CinemaDngExportPlan?
    suspend fun freeBytes(choice: CinemaDngDestinationChoice): Long?
    /** The folder chosen last time, if the app still holds its permission. */
    fun savedFolder(): CinemaDngDestinationChoice.Folder?
    /** Persists the permission for a folder the picker returned and remembers it. */
    fun adoptFolder(tree: Uri): CinemaDngDestinationChoice.Folder?
    fun start(request: CinemaDngExportRequest): Boolean
}

@Composable
internal fun rememberCinemaDngExportEnvironment(): CinemaDngExportEnvironment {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidCinemaDngExportEnvironment(context) }
}

private class AndroidCinemaDngExportEnvironment(private val context: Context) : CinemaDngExportEnvironment {
    private val preferences = context.getSharedPreferences(CINEMADNG_EXPORT_PREFS, Context.MODE_PRIVATE)
    override val lj92Available: Boolean get() = NativeRawCodec.available
    override val states get() = CinemaDngExportRuntime.states
    override fun recording() = CinemaDngExportRuntime.recording(context)

    override suspend fun plan(take: LocalMediaTake): CinemaDngExportPlan? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(take.primary.uri.toUri(), "r")?.use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).channel.use { channel ->
                    CinemaDngExporter(channel, CinemaDngExporter.takeName(take.primary.name)).plan()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("CinemaDngExport", "The take could not be indexed", failure)
            null
        }
    }

    override suspend fun freeBytes(choice: CinemaDngDestinationChoice): Long? = withContext(Dispatchers.IO) {
        when (choice) {
            CinemaDngDestinationChoice.Documents -> MediaStoreCinemaDngDestination(context).freeBytes()
            is CinemaDngDestinationChoice.Folder -> safFreeBytes(context.contentResolver, choice.uri.toUri())
        }
    }

    override fun savedFolder(): CinemaDngDestinationChoice.Folder? {
        val tree = preferences.getString(KEY_TREE, null) ?: return null
        val held = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isWritePermission }
        return if (held) folder(tree.toUri()) else null
    }

    override fun adoptFolder(tree: Uri): CinemaDngDestinationChoice.Folder? = runCatching {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, flags)
        val previous = preferences.getString(KEY_TREE, null)
        if (previous != null && previous != tree.toString()) {
            runCatching { context.contentResolver.releasePersistableUriPermission(previous.toUri(), flags) }
        }
        preferences.edit().putString(KEY_TREE, tree.toString()).apply()
        folder(tree)
    }.onFailure { Log.w("CinemaDngExport", "The folder permission could not be kept", it) }.getOrNull()

    override fun start(request: CinemaDngExportRequest) = CinemaDngExportRuntime.start(context, request)

    private fun folder(tree: Uri) = CinemaDngDestinationChoice.Folder(tree.toString(),
        safFolderLabel(context.contentResolver, tree) ?: tree.lastPathSegment?.substringAfterLast(':').orEmpty())

    private companion object {
        const val KEY_TREE = CINEMADNG_EXPORT_TREE_KEY
    }
}

/** Everything the export form shows; [plan] null with [loading] false means the take is unreadable. */
internal data class CinemaDngExportFormState(
    val takeName: String,
    val plan: CinemaDngExportPlan?,
    val loading: Boolean,
    val choice: CinemaDngDestinationChoice,
    val folder: CinemaDngDestinationChoice.Folder?,
    val lj92Available: Boolean,
    val compress: Boolean,
    val freeBytes: Long?,
    val recording: Boolean,
    val busy: Boolean,
) {
    private val compressing: Boolean get() = compress && lj92Available
    val fits: Boolean get() = plan?.fits(compressing, freeBytes) ?: false
    val canStart: Boolean get() = !loading && plan != null && plan.convertible > 0 && fits && !recording && !busy

    fun request(takeUri: String) = CinemaDngExportRequest(takeUri, takeName,
        (choice as? CinemaDngDestinationChoice.Folder)?.uri, compressing)
}

/** Choose where and how to export a RAW take, see the size against the free space, then start. */
@Composable
internal fun CinemaDngExportDialog(take: LocalMediaTake, environment: CinemaDngExportEnvironment, onDismiss: () -> Unit) {
    var plan by remember(take) { mutableStateOf<CinemaDngExportPlan?>(null) }
    var loading by remember(take) { mutableStateOf(true) }
    var folder by remember { mutableStateOf(environment.savedFolder()) }
    var choice by remember { mutableStateOf<CinemaDngDestinationChoice>(CinemaDngDestinationChoice.Documents) }
    var compress by rememberSaveable { mutableStateOf(false) }
    var free by remember { mutableStateOf<Long?>(null) }
    var recording by remember { mutableStateOf(environment.recording()) }
    val exports by environment.states.collectAsState()
    LaunchedEffect(take) { plan = environment.plan(take); loading = false }
    LaunchedEffect(choice) { free = null; free = environment.freeBytes(choice) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let(environment::adoptFolder)?.let { folder = it; choice = it }
    }
    val state = CinemaDngExportFormState(CinemaDngExporter.takeName(take.primary.name), plan, loading, choice, folder,
        environment.lj92Available, compress, free, recording, exports is CinemaDngExportState.Running)
    MediaDialogFrame("cinemadng-export-dialog", onDismiss) {
        CinemaDngExportForm(take, state,
            onChoice = { choice = it },
            onChooseFolder = { runCatching { picker.launch(null) } },
            onCompress = { compress = it },
            onStart = {
                recording = environment.recording()
                if (!recording && environment.start(state.request(take.primary.uri))) onDismiss()
            },
            onCancel = onDismiss)
    }
}

/** The dialog's content; the driver renders it directly, since screenshots leave dialogs out. */
@Composable
internal fun ColumnScope.CinemaDngExportForm(take: LocalMediaTake, state: CinemaDngExportFormState,
    onChoice: (CinemaDngDestinationChoice) -> Unit, onChooseFolder: () -> Unit, onCompress: (Boolean) -> Unit,
    onStart: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.cinemadng_export_title), style = MaterialTheme.typography.titleLarge)
        MediaDialogTake(take, "cinemadng-export-take")
        val plan = state.plan
        when {
            state.loading -> Text(stringResource(R.string.cinemadng_export_measuring), Modifier.fillMaxWidth().testTag("cinemadng-export-measuring"))
            plan == null -> Text(stringResource(R.string.cinemadng_export_unreadable), Modifier.fillMaxWidth().testTag("cinemadng-export-error"),
                color = MaterialTheme.colorScheme.error)
            plan.convertible == 0 -> Text(stringResource(R.string.cinemadng_export_no_frames), Modifier.fillMaxWidth().testTag("cinemadng-export-error"),
                color = MaterialTheme.colorScheme.error)
            else -> {
                val compressing = state.compress && state.lj92Available
                Text(stringResource(if (plan.hasAudio) R.string.cinemadng_export_frames_audio else R.string.cinemadng_export_frames, plan.convertible),
                    Modifier.fillMaxWidth().testTag("cinemadng-export-frames"))
                if (plan.skipped > 0) Text(stringResource(R.string.cinemadng_export_frames_skipped, plan.skipped),
                    Modifier.fillMaxWidth().testTag("cinemadng-export-skipped"), color = GalleryWarning, fontSize = 13.sp)
                val needed = size(plan.bytes(compressing))
                val (text, color) = when {
                    // A picked folder's provider may not report its free space: warn, but allow.
                    state.freeBytes == null -> stringResource(R.string.cinemadng_export_space_unknown, needed) to GalleryWarning
                    !state.fits -> stringResource(R.string.cinemadng_export_space_short, needed, size(state.freeBytes)) to
                        MaterialTheme.colorScheme.error
                    plan.isEstimate(compressing) -> stringResource(R.string.cinemadng_export_space_estimate, needed, size(state.freeBytes)) to
                        MaterialTheme.colorScheme.onSurface
                    else -> stringResource(R.string.cinemadng_export_space, needed, size(state.freeBytes)) to MaterialTheme.colorScheme.onSurface
                }
                Text(text, Modifier.fillMaxWidth().testTag("cinemadng-export-space"), color = color, fontSize = 13.sp)
            }
        }
        if (state.recording) Text(stringResource(R.string.cinemadng_export_recording), Modifier.fillMaxWidth().testTag("cinemadng-export-recording"),
            color = MaterialTheme.colorScheme.error)
        else if (state.busy) Text(stringResource(R.string.cinemadng_export_busy), Modifier.fillMaxWidth().testTag("cinemadng-export-busy"),
            color = GalleryWarning)

        Text(stringResource(R.string.cinemadng_export_destination), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        SettingsPillRow {
            SettingsPill(stringResource(R.string.cinemadng_export_documents), "cinemadng-export-documents",
                selected = state.choice == CinemaDngDestinationChoice.Documents) { onChoice(CinemaDngDestinationChoice.Documents) }
            state.folder?.let { folder ->
                SettingsPill(shortLabel(folder.label), "cinemadng-export-folder", selected = state.choice == folder) { onChoice(folder) }
            }
            SettingsPill(stringResource(R.string.cinemadng_export_choose_folder), "cinemadng-export-choose", selected = false,
                role = androidx.compose.ui.semantics.Role.Button, onClick = onChooseFolder)
        }
        val where = when (val choice = state.choice) {
            CinemaDngDestinationChoice.Documents -> "$CINEMADNG_EXPORT_PATH${state.takeName}/"
            is CinemaDngDestinationChoice.Folder -> "${choice.label}/${state.takeName}/"
        }
        Text(where, Modifier.fillMaxWidth().testTag("cinemadng-export-path"), color = SettingsMuted, fontSize = 12.sp)

        if (state.lj92Available) {
            SettingsSwitchRow(stringResource(R.string.cinemadng_export_compress), state.compress, onCompress,
                tag = "cinemadng-export-compress", labelTag = "cinemadng-export-compress-label")
            Text(stringResource(R.string.cinemadng_export_compress_help), Modifier.fillMaxWidth(), color = SettingsMuted, fontSize = 12.sp)
        }

        Text(stringResource(R.string.cinemadng_export_help), Modifier.fillMaxWidth(), color = SettingsMuted, fontSize = 13.sp)
    }
    val start = @Composable { modifier: Modifier ->
        Button(onStart, enabled = state.canStart, modifier = modifier.heightIn(min = 48.dp).testTag("cinemadng-export-start")) {
            Text(stringResource(R.string.cinemadng_export_start))
        }
    }
    val cancel = @Composable { modifier: Modifier ->
        OutlinedButton(onCancel, modifier = modifier.heightIn(min = 48.dp).testTag("cinemadng-export-dismiss")) {
            Text(stringResource(R.string.cinemadng_export_cancel))
        }
    }
    // A short window (a phone held sideways) keeps the buttons on one row, so the form gets the height.
    if (LocalConfiguration.current.screenHeightDp < 480) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            cancel(Modifier.weight(1f))
            start(Modifier.weight(1f))
        }
    } else {
        start(Modifier.fillMaxWidth())
        cancel(Modifier.fillMaxWidth())
    }
}

private fun shortLabel(label: String): String = if (label.length <= 24) label else label.take(23) + "…"

/** The export in progress on a card or in the inspector: frames, percent, a bar and Cancel. */
@Composable
internal fun CinemaDngExportProgressRow(state: CinemaDngExportState.Running, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val progress = state.progress?.takeIf { it.total > 0 }
    Column(modifier.fillMaxWidth().testTag("cinemadng-export-progress"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val label = when {
                state.cancelling -> stringResource(R.string.cinemadng_export_cancelling)
                progress == null -> stringResource(R.string.cinemadng_export_preparing)
                else -> stringResource(R.string.cinemadng_export_running, progress.done, progress.total, (progress.fraction * 100).toInt())
            }
            Text(label, Modifier.weight(1f).testTag("cinemadng-export-progress-label"), color = SettingsAccent, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold)
            TextButton(onCancel, enabled = !state.cancelling, modifier = Modifier.heightIn(min = 48.dp).testTag("cinemadng-export-cancel")) {
                Text(stringResource(R.string.cinemadng_export_cancel))
            }
        }
        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
    }
}

/** The last export's result above the gallery, until the operator dismisses it. */
@Composable
internal fun CinemaDngExportNotice(state: CinemaDngExportState.Finished, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember(state.outcome) { cinemaDngOutcomeText(context, state.outcome) }
    val good = state.outcome is CinemaDngExportOutcome.Exported
    val tint = if (good) LocalCineColors.current.ok else GalleryWarning
    Row(Modifier.fillMaxWidth().testTag("cinemadng-export-notice"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CineGlyph(if (good) CineIcon.CHECK else CineIcon.WARNING, tint, Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text.title, Modifier.testTag("cinemadng-export-notice-title"), color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            text.detail?.takeIf { it.isNotEmpty() }?.let {
                Text(it, Modifier.testTag("cinemadng-export-notice-detail"), color = SettingsMuted, fontSize = 12.sp)
            }
        }
        TextButton(onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("cinemadng-export-notice-dismiss")) {
            Text(stringResource(R.string.cinemadng_export_dismiss))
        }
    }
}
