/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One way to write a frame size everywhere: long side first, "×" between ("1280×960"). */
fun formatFrameSize(width: Int, height: Int): String = "${maxOf(width, height)}×${minOf(width, height)}"

private val FRAME_SIZE = Regex("""(?<![\d.])(\d{2,5})\s*[x×]\s*(\d{2,5})(?![\d.])""")

/** Rewrites every size in [text] ("960x1280", "1280 × 960") the way [formatFrameSize] writes it. */
fun normalizeFrameSizes(text: String): String =
    FRAME_SIZE.replace(text) { formatFrameSize(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

/**
 * Drops the exception class a failure message may carry, keeping the sentence the operator can act
 * on, and writes frame sizes the way the rest of the sheet does.
 */
internal fun operatorErrorText(message: String): String = normalizeFrameSizes(
    message.replace(Regex("""^(?:[a-z][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error)\s*:\s*"""), "").trim()
        .ifEmpty { message.trim() },
)

/** A recording size and rate the hardware encoder refused, from the engine's (English) message. */
data class EncoderRejection(val size: String, val fps: Int)

private val ENCODER_REJECTION = Regex("""encoder accepts\s+(\d{2,5})\s*[x×]\s*(\d{2,5})\s+at\s+(\d{1,4})\s*fps""", RegexOption.IGNORE_CASE)

/** The engine names the codec and its Surface input; the operator only needs the size and rate. */
fun encoderRejection(message: String?): EncoderRejection? = message?.let(ENCODER_REJECTION::find)?.let {
    EncoderRejection(formatFrameSize(it.groupValues[1].toInt(), it.groupValues[2].toInt()), it.groupValues[3].toInt())
}

/** What failed, in the operator's terms, from the engine's error code. */
enum class CameraErrorKind { RECORDING, SAVE, PREVIEW, PERMISSION, DISCONNECTED, OTHER }

fun cameraErrorKind(code: String?): CameraErrorKind {
    val c = code.orEmpty()
    return when {
        c == "camera-disconnected" -> CameraErrorKind.DISCONNECTED
        "permission" in c -> CameraErrorKind.PERMISSION
        c.endsWith("save-failed") || c.endsWith("save-busy") -> CameraErrorKind.SAVE
        // Photo, flash and still-sequence failures share suffixes with recording ones.
        c.startsWith("photo-") || c.startsWith("still-") -> CameraErrorKind.OTHER
        listOf("recording", "start-failed", "prepare-failed", "stop-failed", "finalize", "output-failed").any { it in c } ->
            CameraErrorKind.RECORDING
        "preview" in c || "session" in c -> CameraErrorKind.PREVIEW
        else -> CameraErrorKind.OTHER
    }
}

@StringRes
private fun CameraErrorKind.headline(): Int = when (this) {
    CameraErrorKind.RECORDING -> R.string.error_headline_recording
    CameraErrorKind.SAVE -> R.string.error_headline_save
    CameraErrorKind.PREVIEW -> R.string.error_headline_preview
    CameraErrorKind.PERMISSION -> R.string.error_headline_permission
    CameraErrorKind.DISCONNECTED -> R.string.error_headline_disconnected
    CameraErrorKind.OTHER -> R.string.camera_error
}

/**
 * Operational error sheet. The scrim swallows input so nothing behind it, REC included, reads as
 * actionable. The operator sees what failed in plain words; the raw text stays behind Details.
 * Back and Esc close the sheet by reopening the preview in the same mode: leaving the app from here
 * used to drop the operator into the startup mode on return.
 */
@Composable
internal fun BoxScope.CameraErrorSheet(
    state: CameraUiState,
    failed: KnownGoodCapture,
    restoreTarget: KnownGoodCapture?,
    onRetry: () -> Unit,
    onRestore: (() -> Unit)?,
    onOpenSettings: () -> Unit,
) {
    BackHandler(onBack = onRetry)
    ShortcutHandler { action -> if (action == ShortcutAction.DISMISS) onRetry(); true }
    var showDetails by rememberSaveable(state.message, state.errorCode) { mutableStateOf(false) }
    val raw = state.message.orEmpty()
    val colors = MaterialTheme.colorScheme
    val window = LocalAdaptiveWindow.current
    Box(
        Modifier
            .matchParentSize()
            .background(colors.scrim.copy(alpha = .72f))
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag("camera-error-scrim"),
    )
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("camera-error-sheet"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (window.heightClass != WindowHeightClass.COMPACT) CineGlyph(CineIcon.WARNING, colors.error, Modifier.size(32.dp))
        Text(stringResource(cameraErrorKind(state.errorCode).headline()), color = colors.onSurface, fontSize = 18.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        val message = encoderRejection(raw)?.let { stringResource(R.string.error_encoder_rejects_size, it.size, it.fps) }
            ?: operatorErrorText(raw)
        if (message.isNotBlank()) Text(message, color = colors.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.testTag("camera-error-message"))
        // Name the configuration that failed, so the operator can tell what the fix moves away from.
        Text(
            stringResource(R.string.error_failed_configuration, captureSummary(failed)),
            color = colors.onSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
            modifier = Modifier.testTag("camera-error-failed-config"),
        )
        // Retrying replays the configuration that failed, so settings the camera has run lead,
        // alone on their row; the lighter actions share the row below.
        val primary = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
        val outline = BorderStroke(1.dp, colors.outline)
        if (onRestore != null) {
            Button(onClick = onRestore, colors = primary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("camera-error-restore")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.error_try_supported_settings), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    restoreTarget?.let { Text(captureSummary(it), fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center) }
                }
            }
        }
        val retryLabel = stringResource(R.string.retry).let { if (window.hardwareKeyboard) withShortcut(it, ShortcutAction.DISMISS) else it }
        Row(Modifier.fillMaxWidth().testTag("camera-error-actions"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val action = Modifier.weight(1f).heightIn(min = 48.dp)
            if (onRestore != null) OutlinedButton(onClick = onRetry, border = outline, modifier = action) {
                Text(retryLabel, color = colors.onSurface, textAlign = TextAlign.Center)
            } else Button(onClick = onRetry, colors = primary, modifier = action) {
                Text(retryLabel, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onOpenSettings, border = outline, modifier = action.testTag("camera-error-settings")) {
                Text(stringResource(R.string.error_open_settings), color = colors.onSurface, textAlign = TextAlign.Center)
            }
        }
        if (raw.isNotBlank() || state.errorCode != null) {
            TextButton(onClick = { showDetails = !showDetails },
                modifier = Modifier.heightIn(min = 48.dp).testTag("camera-error-details-toggle")) {
                Text(stringResource(if (showDetails) R.string.error_details_hide else R.string.error_details_show), color = colors.secondary)
            }
            // The raw text can run long; it scrolls inside its own box so the actions stay in view.
            if (showDetails) SelectionContainer {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .background(colors.surfaceContainerLowest, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        listOfNotNull(state.errorCode, raw.takeIf { it.isNotBlank() }).joinToString("\n"),
                        color = colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().testTag("camera-error-details"),
                    )
                }
            }
        }
    }
}

@Composable
private fun captureSummary(capture: KnownGoodCapture): String {
    val geometry = capture.geometry()
    return listOfNotNull(
        geometry?.let { (w, h, _) -> formatFrameSize(w, h) },
        geometry?.third?.let { "$it fps" },
        modeLabel(capture.mode),
    ).joinToString(" · ")
}
