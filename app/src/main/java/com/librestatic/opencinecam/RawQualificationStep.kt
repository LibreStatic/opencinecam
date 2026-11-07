/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import com.librestatic.opencinecam.rawvideo.RawQualificationSpaceEstimate
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import kotlin.math.ceil

/*
 * OCC-PLAN-071: the RAW video qualification as the operator meets it. Choosing RAW video on a camera
 * that is not qualified asks first (RawVideoQualifyPrompt); accepting, or "Now" in the onboarding's
 * last question, opens RawQualificationStep in the wizard's look, which checks the battery, the charger
 * and the temperature, runs the qualification and shows what it found.
 */

/** Whole minutes, rounded up, at least one: what "about N min" reads. */
internal fun rawQualificationMinutes(durationMs: Long): Int = ceil(durationMs.coerceAtLeast(0) / 60_000.0).toInt().coerceAtLeast(1)

/** The battery and thermal state, live while shown: the sticky battery broadcast and a thermal listener. */
@Composable
internal fun rememberRawQualificationConditions(): RawQualificationConditions {
    val context = LocalContext.current
    var conditions by remember(context) { mutableStateOf(readRawQualificationConditions(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                conditions = conditions.copy(batteryPercent = batteryPercentOf(intent))
            }
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = PowerManager.OnThermalStatusChangedListener { status -> conditions = conditions.copy(thermalStatus = status) }
        runCatching { power?.addThermalStatusListener(ContextCompat.getMainExecutor(context), thermal) }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { power?.removeThermalStatusListener(thermal) }
        }
    }
    return conditions
}

/** The duration, space and side effects of a qualification, as the prompt, the question and the step list them. */
@Composable
private fun rawQualificationFacts(info: RawVideoQualificationInfo?): List<String> {
    val context = LocalContext.current
    val space = info?.space
    return buildList {
        if (space != null && !space.enoughSpace) {
            add(stringResource(R.string.raw_qualification_no_space, Formatter.formatShortFileSize(context, space.minimumFreeBytes),
                Formatter.formatShortFileSize(context, space.freeBytes)))
            return@buildList
        }
        if (space != null) add(stringResource(R.string.raw_qualification_duration, rawQualificationMinutes(space.estimate.durationMs)))
        if (space != null && space.skippedRuns > 0) {
            add(stringResource(R.string.raw_qualification_space_partial, Formatter.formatShortFileSize(context, space.freeBytes)))
        }
        add(stringResource(R.string.raw_qualification_temp_files))
        add(stringResource(R.string.raw_qualification_camera_busy))
    }
}

/**
 * Choosing RAW video on a CANDIDATE camera: what a qualification does and costs. [onQualify] opens the
 * qualification step; without enough free space only [onDismiss] is offered. Back, outside taps and
 * "Not now" change nothing.
 */
@Composable
internal fun RawVideoQualifyPrompt(info: RawVideoQualificationInfo?, onQualify: () -> Unit, onDismiss: () -> Unit) {
    val noSpace = info?.space?.enoughSpace == false
    val facts = rawQualificationFacts(info)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("raw-video-qualify-prompt"),
        title = { Text(stringResource(R.string.raw_qualification_prompt_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info?.previousFailure == true) Text(stringResource(R.string.raw_qualification_previous_failure), fontWeight = FontWeight.SemiBold)
                if (!noSpace) Text(stringResource(R.string.raw_qualification_prompt_body))
                facts.forEach { Text(it) }
            }
        },
        confirmButton = {
            if (noSpace) {
                TextButton(onDismiss, Modifier.testTag("raw-video-qualify-later")) { Text(stringResource(R.string.raw_qualification_prompt_dismiss)) }
            } else {
                TextButton(onQualify, Modifier.testTag("raw-video-qualify-start")) { Text(stringResource(R.string.raw_qualification_prompt_start)) }
            }
        },
        dismissButton = if (noSpace) null else {
            { TextButton(onDismiss, Modifier.testTag("raw-video-qualify-later")) { Text(stringResource(R.string.raw_qualification_prompt_later)) } }
        },
    )
}

/**
 * The wizard's frame for the RAW stages: the label on top, [heading] and [body] in one scrolling
 * column with [footer] under it, or, where the wizard uses two panes, [heading] and [footer] beside
 * [body].
 */
@Composable
private fun RawStageScaffold(
    tag: String,
    heading: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    chips: (@Composable () -> Unit)? = null,
) {
    val label: @Composable (Modifier) -> Unit = { labelModifier ->
        Text(stringResource(R.string.onb_raw_label), color = OnbMuted, fontSize = 13.sp, modifier = labelModifier.heightIn(min = 48.dp).padding(top = 14.dp))
    }
    val footerColumn: @Composable (Modifier) -> Unit = { footerModifier ->
        Column(footerModifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
    }
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag(tag), contentAlignment = Alignment.TopCenter) {
        val window = AdaptiveWindow(maxWidth.value, maxHeight.value)
        if (onboardingLayout(window) == OnboardingLayout.TWO_PANE) {
            Row(
                Modifier.widthIn(max = TwoPaneMaxWidth).fillMaxSize().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Column(Modifier.weight(0.42f).fillMaxHeight()) {
                    label(Modifier.fillMaxWidth().padding(top = 8.dp))
                    FadingScroll(Modifier.weight(1f).fillMaxWidth(), Arrangement.Center, heading)
                    footerColumn(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp))
                }
                // The chips lead the wider pane: under the heading they would push it past the footer.
                FadingScroll(Modifier.weight(0.58f).fillMaxHeight().padding(vertical = 16.dp), Arrangement.Center) {
                    chips?.invoke()
                    body()
                }
            }
        } else {
            val column = Modifier.widthIn(max = PageMaxWidth + 48.dp).fillMaxWidth().padding(horizontal = 24.dp)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                label(column.padding(top = 8.dp))
                FadingScroll(column.weight(1f).padding(top = 4.dp)) {
                    heading()
                    chips?.invoke()
                    Spacer(Modifier.height(8.dp))
                    body()
                }
                footerColumn(column.padding(top = 12.dp, bottom = 16.dp))
            }
        }
    }
}

@Composable
private fun StageTitle(title: String, body: String? = null) {
    Text(title, color = OnbText, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 33.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
    if (body != null) Text(body, color = OnbMuted, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun SecondaryAction(text: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) {
        Text(text, color = OnbText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

private val CardShape = RoundedCornerShape(24.dp)

/** A rounded surface card in the wizard's style; [content] lays out its own rows. */
@Composable
private fun StageCard(modifier: Modifier = Modifier, color: Color = OnbSurface, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp).clip(CardShape).background(color).padding(padding), content = content)
}

@Composable
private fun CardDivider() = HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/** The step's own small glyphs, drawn like [CineGlyph]: line icons on a 24-unit grid. */
private enum class StepGlyph { CLOCK, CAMERA_OFF, THERMOMETER, STORAGE, BLOCK, CHECK_CIRCLE, ALERT_CIRCLE, TESTING, INFO, WARNING, HOURGLASS }

@Composable
private fun StepIcon(glyph: StepGlyph, tint: Color, modifier: Modifier = Modifier, on: Color = MaterialTheme.colorScheme.surface) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, h * y)
        fun line(ax: Float, ay: Float, bx: Float, by: Float, color: Color = tint) = drawLine(color, p(ax, ay), p(bx, by), strokeWidth = sw, cap = StrokeCap.Round)
        when (glyph) {
            StepGlyph.CLOCK -> {
                drawCircle(tint, radius = w * 0.40f, center = p(.5f, .5f), style = stroke)
                line(.5f, .5f, .5f, .28f); line(.5f, .5f, .66f, .6f)
            }
            StepGlyph.CAMERA_OFF -> {
                drawRoundRect(tint, topLeft = p(.08f, .30f), size = Size(w * .58f, h * .42f), cornerRadius = CornerRadius(w * .08f), style = stroke)
                val lens = Path().apply { moveTo(w * .66f, h * .44f); lineTo(w * .92f, h * .32f); lineTo(w * .92f, h * .70f); lineTo(w * .66f, h * .58f); close() }
                drawPath(lens, tint, style = stroke)
                line(.10f, .12f, .90f, .88f)
            }
            StepGlyph.THERMOMETER -> {
                drawRoundRect(tint, topLeft = p(.40f, .08f), size = Size(w * .20f, h * .58f), cornerRadius = CornerRadius(w * .10f), style = stroke)
                drawCircle(tint, radius = w * .16f, center = p(.5f, .76f), style = stroke)
                drawCircle(tint, radius = w * .08f, center = p(.5f, .76f))
                line(.5f, .40f, .5f, .70f)
            }
            StepGlyph.STORAGE -> {
                val oval = Size(w * .72f, h * .24f)
                drawOval(tint, topLeft = p(.14f, .10f), size = oval, style = stroke)
                drawArc(tint, 0f, 180f, false, topLeft = p(.14f, .38f), size = oval, style = stroke)
                drawArc(tint, 0f, 180f, false, topLeft = p(.14f, .66f), size = oval, style = stroke)
                line(.14f, .22f, .14f, .78f); line(.86f, .22f, .86f, .78f)
            }
            StepGlyph.BLOCK -> {
                drawCircle(tint, radius = w * .40f, center = p(.5f, .5f), style = stroke)
                line(.22f, .22f, .78f, .78f)
            }
            StepGlyph.CHECK_CIRCLE -> {
                drawCircle(tint, radius = w * .46f, center = p(.5f, .5f))
                val check = Path().apply { moveTo(w * .28f, h * .52f); lineTo(w * .44f, h * .67f); lineTo(w * .73f, h * .36f) }
                drawPath(check, on, style = Stroke(width = w * .11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            StepGlyph.ALERT_CIRCLE -> {
                drawCircle(tint, radius = w * .46f, center = p(.5f, .5f))
                drawLine(on, p(.5f, .26f), p(.5f, .56f), strokeWidth = w * .11f, cap = StrokeCap.Round)
                drawCircle(on, radius = w * .065f, center = p(.5f, .74f))
            }
            StepGlyph.TESTING -> {
                drawCircle(tint.copy(alpha = 0.25f), radius = w * .38f, center = p(.5f, .5f), style = stroke)
                drawArc(tint, -90f, 120f, false, topLeft = p(.12f, .12f), size = Size(w * .76f, h * .76f), style = stroke)
            }
            StepGlyph.INFO -> {
                drawCircle(tint, radius = w * .40f, center = p(.5f, .5f), style = stroke)
                line(.5f, .46f, .5f, .72f)
                drawCircle(tint, radius = w * .05f, center = p(.5f, .30f))
            }
            StepGlyph.HOURGLASS -> {
                line(.24f, .10f, .76f, .10f); line(.24f, .90f, .76f, .90f)
                val glass = Path().apply {
                    moveTo(w * .30f, h * .10f); lineTo(w * .30f, h * .26f); lineTo(w * .5f, h * .5f); lineTo(w * .30f, h * .74f)
                    lineTo(w * .30f, h * .90f); moveTo(w * .70f, h * .10f); lineTo(w * .70f, h * .26f); lineTo(w * .5f, h * .5f)
                    lineTo(w * .70f, h * .74f); lineTo(w * .70f, h * .90f)
                }
                drawPath(glass, tint, style = stroke)
                val sand = Path().apply { moveTo(w * .38f, h * .84f); lineTo(w * .5f, h * .70f); lineTo(w * .62f, h * .84f); close() }
                drawPath(sand, tint)
            }
            StepGlyph.WARNING -> {
                val triangle = Path().apply { moveTo(w * .5f, h * .10f); lineTo(w * .93f, h * .86f); lineTo(w * .07f, h * .86f); close() }
                drawPath(triangle, tint, style = stroke)
                line(.5f, .38f, .5f, .60f)
                drawCircle(tint, radius = w * .05f, center = p(.5f, .73f))
            }
        }
    }
}

/** A compact fact in a pill: an icon and one or two short lines. */
@Composable
private fun FactChip(glyph: StepGlyph, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(OnbSurface).padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StepIcon(glyph, OnbText, Modifier.size(16.dp))
        // One line each: the row wraps whole chips when a locale does not fit, never a chip's text.
        Text(text, color = OnbText, fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

/** A status pill on a configuration row. */
@Composable
private fun StatusPill(text: String, container: Color, content: Color, outlined: Boolean = false, border: Color? = null) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        color = content,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(container)
            .then(if (outlined) Modifier.border(1.dp, border ?: MaterialTheme.colorScheme.outline, shape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Why a qualification stopped or could not run, in words; null for the operator's own cancel. */
@Composable
private fun rawQualificationReasonText(reason: String?): String? = when (reason) {
    null, RawQualificationStopReasons.USER -> null
    RawQualificationStopReasons.BATTERY -> stringResource(R.string.raw_qualification_reason_battery, RAW_QUALIFICATION_ABORT_BATTERY_PERCENT)
    RawQualificationStopReasons.APP_LEFT, RawQualificationStopReasons.PREVIEW_LOST, RawQualificationStopReasons.SERVICE_CLOSED ->
        stringResource(R.string.raw_qualification_reason_app_left)
    RawQualificationRefusals.BUSY, RawQualificationRefusals.RUNNING -> stringResource(R.string.raw_qualification_reason_busy)
    RawQualificationRefusals.NOT_READY -> stringResource(R.string.raw_qualification_reason_not_ready)
    RawQualificationRefusals.UNSUPPORTED -> stringResource(R.string.raw_qualification_reason_unsupported)
    else -> stringResource(R.string.raw_qualification_reason_error)
}

@Composable
private fun formatBytes(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** Duration, camera and heat as three chips; the duration only when the estimate is known. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactChips(info: RawVideoQualificationInfo?) {
    val space = info?.space
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("raw-qualification-chips"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (space != null && space.enoughSpace) {
            FactChip(StepGlyph.CLOCK, stringResource(R.string.raw_qualification_chip_duration, rawQualificationMinutes(space.estimate.durationMs)))
        }
        FactChip(StepGlyph.CAMERA_OFF, stringResource(R.string.raw_qualification_chip_camera))
        FactChip(StepGlyph.THERMOMETER, stringResource(R.string.raw_qualification_chip_warm))
    }
}

/** A short notice card: an icon and its text. */
@Composable
private fun NoticeCard(text: String, modifier: Modifier = Modifier, glyph: StepGlyph = StepGlyph.INFO, tint: Color = OnbAmber, textColor: Color = OnbText) {
    StageCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StepIcon(glyph, tint, Modifier.size(24.dp))
            Text(text, color = textColor, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
        }
    }
}

/** The onboarding's last question: qualify RAW video now, or later from the mode selector. */
@Composable
internal fun OnboardingRawQuestion(info: RawVideoQualificationInfo?, onNow: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val noSpace = info?.space?.enoughSpace == false
    RawStageScaffold(
        tag = "onb-raw-question",
        modifier = modifier,
        heading = {
            StageTitle(stringResource(R.string.onb_raw_question_title), stringResource(R.string.onb_raw_question_body))
        },
        chips = { FactChips(info) },
        body = {
            if (info?.previousFailure == true) NoticeCard(stringResource(R.string.raw_qualification_previous_failure))
            if (noSpace) NoSpaceCard(info?.space)
            else info?.space?.takeIf { it.skippedRuns > 0 }?.let { NoticeCard(stringResource(R.string.raw_qualification_space_title), glyph = StepGlyph.STORAGE) }
        },
        footer = {
            PrimaryButton(stringResource(R.string.onb_raw_now), testTag = "onb-raw-now", onClick = onNow)
            SecondaryAction(stringResource(R.string.onb_raw_later), "onb-raw-later", onLater)
        },
    )
}

/**
 * The qualification step, from the onboarding or from the mode selector: the live preconditions and
 * Start, the run with its progress and Cancel, then the result with Done. [state] is
 * [CameraUiState.rawVideoQualification]; a cancelled run shows the preconditions again with a note.
 * [onLeave] gets whether something passed. While it runs the screen stays on, and the app going to
 * the background cancels it ([onCancel] with [RawQualificationStopReasons.APP_LEFT]).
 */
@Composable
internal fun RawQualificationStep(
    info: RawVideoQualificationInfo?,
    conditions: RawQualificationConditions,
    state: RawVideoQualificationUi?,
    onStart: () -> Unit,
    onCancel: (String) -> Unit,
    onLeave: (passed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = state?.running == true
    val outcome = state?.outcome
    val currentRunning by rememberUpdatedState(running)
    val currentCancel by rememberUpdatedState(onCancel)
    val view = LocalView.current
    DisposableEffect(view, running) {
        val before = view.keepScreenOn
        if (running) view.keepScreenOn = true
        onDispose { if (running) view.keepScreenOn = before }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && currentRunning) currentCancel(RawQualificationStopReasons.APP_LEFT)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Back never cancels a run by accident; elsewhere it leaves as Later or Done does.
    BackHandler {
        if (!running) onLeave(outcome == RawQualificationOutcomeKind.PASSED)
    }
    Box(modifier.fillMaxSize().testTag("raw-qualification-step")) {
        when {
            running -> RunningStage(state!!, onCancel = { onCancel(RawQualificationStopReasons.USER) })
            outcome == RawQualificationOutcomeKind.PASSED || outcome == RawQualificationOutcomeKind.NO_CONFIG ||
                outcome == RawQualificationOutcomeKind.FAILED_TO_RUN ->
                FinishedStage(state!!, onDone = { onLeave(outcome == RawQualificationOutcomeKind.PASSED) })
            else -> ReadyStage(info, conditions, cancelled = state, onStart = onStart, onLater = { onLeave(false) })
        }
    }
}

/** One precondition row: a check or a warning, what it needs, and the reading or what to do. */
@Composable
private fun CheckRow(title: String, passed: Boolean, detail: String?, tag: String, warning: Boolean = false) {
    val success = LocalCineColors.current.success
    val warn = LocalCineColors.current.pending
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (passed && warning) StepIcon(StepGlyph.WARNING, warn, Modifier.size(26.dp))
        else if (passed) StepIcon(StepGlyph.CHECK_CIRCLE, success.onContainer, Modifier.size(26.dp), on = success.container)
        else StepIcon(StepGlyph.ALERT_CIRCLE, MaterialTheme.colorScheme.error, Modifier.size(26.dp), on = MaterialTheme.colorScheme.onError)
        Column(Modifier.weight(1f)) {
            Text(title, color = OnbText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            val detailColor = when {
                !passed -> MaterialTheme.colorScheme.error
                warning -> warn
                else -> OnbMuted
            }
            if (detail != null) Text(detail, color = detailColor, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun Checklist(conditions: RawQualificationConditions) {
    val blockers = conditions.blockers
    val battery = conditions.batteryPercent
    StageCard(Modifier.testTag("raw-qualification-checklist"), padding = 14.dp) {
        CheckRow(
            title = stringResource(R.string.raw_qualification_check_battery, RAW_QUALIFICATION_MIN_BATTERY_PERCENT),
            passed = RawQualificationBlocker.BATTERY_LOW !in blockers,
            detail = when {
                RawQualificationBlocker.BATTERY_LOW in blockers ->
                    stringResource(R.string.raw_qualification_check_battery_fix, RAW_QUALIFICATION_MIN_BATTERY_PERCENT, battery ?: 0)
                conditions.batteryWarning && battery != null -> stringResource(R.string.raw_qualification_check_battery_warn, battery)
                battery != null -> stringResource(R.string.raw_qualification_check_battery_ok, battery)
                else -> null
            },
            tag = "raw-qualification-check-battery",
            warning = conditions.batteryWarning,
        )
        CardDivider()
        CheckRow(
            title = stringResource(R.string.raw_qualification_check_cool),
            passed = RawQualificationBlocker.TOO_WARM !in blockers,
            detail = if (RawQualificationBlocker.TOO_WARM in blockers) stringResource(R.string.raw_qualification_check_cool_fix) else null,
            tag = "raw-qualification-check-thermal",
        )
    }
}

@Composable
private fun Bullet(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(end = 12.dp).size(5.dp).clip(CircleShape).background(OnbText))
        Text(rawConfigLabelNoBreak(text, rawCompressedWord()), color = OnbText, fontSize = 14.sp)
        if (trailing != null) Text(" · $trailing", color = OnbMuted, fontSize = 14.sp)
    }
}

/**
 * The space section: what the free space holds against what the whole plan needs, which
 * configurations will be tested and which are skipped, each with the space it needs.
 */
@Composable
private fun SpaceSection(space: RawQualificationSpaceEstimate) {
    val items = rawQualificationPlanItems(space)
    val tested = items.filter { it.status != RawQualificationItemStatus.SKIPPED_SPACE }
    val skipped = items.filter { it.status == RawQualificationItemStatus.SKIPPED_SPACE }
    val success = LocalCineColors.current.ok
    StageCard(Modifier.testTag("raw-qualification-space")) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                StepIcon(StepGlyph.STORAGE, MaterialTheme.colorScheme.onPrimaryContainer, Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.raw_qualification_space_title), color = OnbText, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp)
                Text(stringResource(R.string.raw_qualification_space_body), color = OnbMuted, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text(stringResource(R.string.raw_qualification_space_free, formatBytes(space.freeBytes)), color = OnbText, fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp).testTag("raw-qualification-free"))
        val share = if (space.fullPlanFreeBytes > 0) (space.freeBytes.toFloat() / space.fullPlanFreeBytes).coerceIn(0f, 1f) else 1f
        LinearProgressIndicator(
            progress = { share },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = OnbAmber,
            trackColor = OnbAmber.copy(alpha = 0.25f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        StageCard(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CineGlyph(CineIcon.CHECK, success, Modifier.size(18.dp))
                Text(stringResource(R.string.raw_qualification_space_tested), color = success, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Column(Modifier.padding(top = 4.dp, bottom = 8.dp).testTag("raw-qualification-tested")) { tested.forEach { Bullet(it.label) } }
            CardDivider()
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StepIcon(StepGlyph.BLOCK, OnbText, Modifier.size(18.dp))
                Text(stringResource(R.string.raw_qualification_space_skipped), color = OnbText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Column(Modifier.padding(top = 4.dp, bottom = 8.dp).testTag("raw-qualification-skipped")) {
                skipped.forEach { Bullet(it.label, stringResource(R.string.raw_qualification_needs, formatBytes(it.neededBytes))) }
            }
            Text(stringResource(R.string.raw_qualification_space_footer), color = OnbMuted, fontSize = 13.sp)
        }
    }
}

/** Not enough space for even the sweep or the smallest run: nothing can start. */
@Composable
private fun NoSpaceCard(space: RawQualificationSpaceEstimate?) {
    space ?: return
    NoticeCard(
        stringResource(R.string.raw_qualification_no_space, formatBytes(space.minimumFreeBytes), formatBytes(space.freeBytes)),
        Modifier.testTag("raw-qualification-no-space"),
        glyph = StepGlyph.STORAGE,
        tint = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ReadyStage(
    info: RawVideoQualificationInfo?,
    conditions: RawQualificationConditions,
    cancelled: RawVideoQualificationUi?,
    onStart: () -> Unit,
    onLater: () -> Unit,
) {
    val blockers = conditions.blockers
    val space = info?.space
    val noSpace = space?.enoughSpace == false
    RawStageScaffold(
        tag = "raw-qualification-ready",
        chips = { FactChips(info) },
        heading = {
            StageTitle(stringResource(R.string.raw_qualification_title), stringResource(R.string.raw_qualification_intro))
            if (cancelled != null) {
                val reason = rawQualificationReasonText(cancelled.reason)
                NoticeCard(listOfNotNull(stringResource(R.string.raw_qualification_cancelled_note), reason).joinToString(" "),
                    Modifier.testTag("raw-qualification-cancelled"))
            }
        },
        body = {
            if (info?.previousFailure == true) NoticeCard(stringResource(R.string.raw_qualification_previous_failure))
            Checklist(conditions)
            when {
                noSpace -> NoSpaceCard(space)
                space != null && space.skippedRuns > 0 -> SpaceSection(space)
                space != null -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("raw-qualification-free"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StepIcon(StepGlyph.STORAGE, OnbMuted, Modifier.size(18.dp))
                    Text(stringResource(R.string.raw_qualification_space_free, formatBytes(space.freeBytes)), color = OnbMuted, fontSize = 14.sp)
                }
            }
            if (blockers.isNotEmpty()) {
                NoticeCard(stringResource(R.string.raw_qualification_blocked), Modifier.testTag("raw-qualification-blocked"),
                    glyph = StepGlyph.INFO, tint = MaterialTheme.colorScheme.error)
            }
        },
        footer = {
            Button(
                onClick = onStart,
                enabled = blockers.isEmpty() && !noSpace,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("raw-qualification-start"),
            ) { Text(stringResource(R.string.raw_qualification_start), fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            SecondaryAction(stringResource(R.string.raw_qualification_later), "raw-qualification-later", onLater)
        },
    )
}

/**
 * The rate a qualified or risky [item]'s camera achieved ("59.9 fps"), worded as a shortfall below
 * its target ("Reaches 51.9 fps"); null for any other item or an unknown rate.
 */
@Composable
private fun rawAchievedRate(item: RawQualificationItem): String? {
    if (item.status != RawQualificationItemStatus.PASSED && item.status != RawQualificationItemStatus.RISKY &&
        item.status != RawQualificationItemStatus.MEASURED) return null
    val fps = item.achievedFps ?: return null
    return stringResource(if (item.reducedRate) R.string.raw_qualification_item_reaches else R.string.raw_qualification_item_fps, fps)
}

/** A configuration's icon, pill and row highlight while the run goes. */
@Composable
private fun ItemRow(item: RawQualificationItem) {
    val colors = MaterialTheme.colorScheme
    val success = LocalCineColors.current.success
    val testing = item.status == RawQualificationItemStatus.TESTING
    // An encode benchmark streaming a judged configuration highlights its row like a test, status kept.
    val active = testing || item.benchmarking
    val risky = LocalCineColors.current.pending
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) OnbAmber.copy(alpha = 0.16f) else Color.Transparent)
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("raw-qualification-item-${item.spec}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val iconModifier = Modifier.size(22.dp)
        when (item.status) {
            RawQualificationItemStatus.PASSED -> StepIcon(StepGlyph.CHECK_CIRCLE, success.onContainer, iconModifier, on = success.container)
            RawQualificationItemStatus.RISKY -> StepIcon(StepGlyph.WARNING, risky, iconModifier)
            RawQualificationItemStatus.TESTING -> StepIcon(StepGlyph.TESTING, OnbAmber, iconModifier)
            RawQualificationItemStatus.PENDING -> StepIcon(StepGlyph.CLOCK, OnbMuted, iconModifier)
            RawQualificationItemStatus.MEASURED -> StepIcon(StepGlyph.CHECK_CIRCLE, OnbMuted, iconModifier)
            RawQualificationItemStatus.FAILED -> StepIcon(StepGlyph.ALERT_CIRCLE, colors.error, iconModifier, on = colors.onError)
            RawQualificationItemStatus.NOT_QUALIFIED, RawQualificationItemStatus.SKIPPED_SPACE -> StepIcon(StepGlyph.BLOCK, OnbMuted, iconModifier)
            RawQualificationItemStatus.SKIPPED_HEAT -> StepIcon(StepGlyph.THERMOMETER, OnbMuted, iconModifier)
            RawQualificationItemStatus.CAMERA_LIMIT -> StepIcon(StepGlyph.CAMERA_OFF, OnbMuted, iconModifier)
        }
        Column(Modifier.weight(1f)) {
            // Never truncated: the label may only break before its compressed suffix.
            Text(rawConfigLabelNoBreak(item.label, rawCompressedWord()), color = OnbText, fontSize = 15.sp, lineHeight = 19.sp)
            // The rate the camera delivers now, in the achieved rate's slot: a TESTING row has none, and a
            // benchmarked row shows it again once its benchmark ends.
            val live = item.currentFps.takeIf { active }
            val target = item.targetFps
            if (live == null || target == null) {
                rawAchievedRate(item)?.let { Text(it, color = OnbMuted, fontSize = 13.sp, lineHeight = 17.sp) }
            } else {
                Text(
                    stringResource(R.string.raw_qualification_item_live_fps, live, target),
                    color = if (item.liveFpsShort) risky else success.onContainer,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    modifier = Modifier.testTag("raw-qualification-live-fps"),
                )
            }
        }
        when (item.status) {
            RawQualificationItemStatus.PASSED -> StatusPill(stringResource(R.string.raw_qualification_status_ready), success.container, success.onContainer)
            RawQualificationItemStatus.RISKY ->
                StatusPill(stringResource(R.string.raw_qualification_status_risky), Color.Transparent, risky, outlined = true, border = risky)
            RawQualificationItemStatus.TESTING -> StatusPill(stringResource(R.string.raw_qualification_status_testing), OnbAmber, colors.onPrimary)
            RawQualificationItemStatus.PENDING -> StatusPill(stringResource(R.string.raw_qualification_status_pending), colors.surfaceContainerHighest, OnbText)
            RawQualificationItemStatus.MEASURED ->
                StatusPill(stringResource(R.string.raw_qualification_status_measured), Color.Transparent, OnbText, outlined = true, border = OnbMuted)
            RawQualificationItemStatus.SKIPPED_SPACE ->
                StatusPill(stringResource(R.string.raw_qualification_status_skipped), Color.Transparent, OnbMuted, outlined = true)
            RawQualificationItemStatus.SKIPPED_HEAT ->
                StatusPill(stringResource(R.string.raw_qualification_status_too_hot), Color.Transparent, OnbMuted, outlined = true)
            RawQualificationItemStatus.FAILED -> StatusPill(stringResource(R.string.raw_qualification_status_failed), colors.errorContainer, colors.onErrorContainer)
            RawQualificationItemStatus.CAMERA_LIMIT ->
                StatusPill(stringResource(R.string.raw_qualification_status_camera_limit), Color.Transparent, OnbMuted, outlined = true)
            RawQualificationItemStatus.NOT_QUALIFIED ->
                StatusPill(stringResource(R.string.raw_qualification_status_not_qualified), colors.surfaceContainerHighest, OnbMuted)
        }
    }
}

/**
 * A configuration label as the running header shows it: the size and rate on one line, shrunk to
 * fit rather than broken, and a compressed suffix on its own line when it does not fit beside it.
 */
@Composable
private fun ConfigHeadline(label: String, modifier: Modifier = Modifier) {
    val (base, suffix) = rawConfigLabelParts(label, rawCompressedWord())
    val style = TextStyle(color = OnbText, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp)
    Column(modifier) {
        BasicText(base, style = style, maxLines = 1, softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 22.sp))
        if (suffix != null) Text(suffix, color = OnbMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun RunningStage(state: RawVideoQualificationUi, onCancel: () -> Unit) {
    val stepText = when (rawQualificationStepText(state.phase)) {
        RawQualificationStepText.PREPARING -> stringResource(R.string.raw_qualification_step_preparing)
        RawQualificationStepText.STORAGE -> stringResource(R.string.raw_qualification_step_storage)
        RawQualificationStepText.RESTING -> stringResource(R.string.raw_qualification_step_resting)
        RawQualificationStepText.CONFIG -> null
        RawQualificationStepText.CAMERA -> stringResource(R.string.raw_qualification_step_camera)
        RawQualificationStepText.COMPRESSION -> stringResource(R.string.raw_qualification_step_compression)
        RawQualificationStepText.COOLING -> stringResource(R.string.raw_qualification_step_cooling)
        RawQualificationStepText.WAITING_TO_COOL -> stringResource(R.string.raw_qualification_step_waiting)
        RawQualificationStepText.SAVING -> stringResource(R.string.raw_qualification_step_saving)
    }
    val headline = state.specLabel ?: stepText ?: stringResource(R.string.raw_qualification_step_preparing)
    // The ring counts the camera sessions once they start, and keeps the count through the storage
    // rest and sweep; before the first (preparing) it shows the overall percentage, as the labelled
    // bar does. The bar always shows the overall progress.
    val inRuns = state.runIndex > 0 && state.runCount > 0
    val percent = (state.fraction.coerceIn(0f, 1f) * 100).toInt()
    RawStageScaffold(
        tag = "raw-qualification-running",
        heading = {
            StageTitle(stringResource(R.string.raw_qualification_running_title))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(108.dp).testTag("raw-qualification-ring"), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { if (inRuns) state.runIndex.toFloat() / state.runCount else state.fraction },
                        modifier = Modifier.fillMaxSize(),
                        color = OnbAmber,
                        strokeWidth = 10.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        gapSize = 0.dp,
                    )
                    Text(if (inRuns) "${state.runIndex} / ${state.runCount}" else "$percent %", color = OnbText, fontSize = 23.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Box(Modifier.padding(horizontal = 16.dp).width(1.dp).height(88.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.specLabel != null) ConfigHeadline(state.specLabel, Modifier.testTag("raw-qualification-step-text"))
                    else Text(headline, color = OnbText, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp,
                        modifier = Modifier.testTag("raw-qualification-step-text"))
                    if (state.specLabel != null && stepText != null) Text(stepText, color = OnbMuted, fontSize = 15.sp)
                    state.remainingMs?.let { remaining ->
                        Text(stringResource(R.string.raw_qualification_remaining, "${rawQualificationMinutes(remaining)} min"),
                            color = OnbMuted, fontSize = 15.sp, modifier = Modifier.testTag("raw-qualification-remaining"))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp).testTag("raw-qualification-overall"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.raw_qualification_overall), color = OnbMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("$percent %", color = OnbText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).height(6.dp).testTag("raw-qualification-progress"),
                color = OnbAmber,
                trackColor = OnbAmber.copy(alpha = 0.25f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        },
        body = {
            if (state.items.isNotEmpty()) {
                // The row in progress leads, the rest heaviest first; when another row starts, the list
                // scrolls back so it shows.
                val ordered = remember(state.items) { rawQualificationItemsRunningOrder(state.items) }
                val current = ordered.firstOrNull()?.takeIf { it.inProgress }?.spec
                val currentInView = remember { BringIntoViewRequester() }
                LaunchedEffect(current) { if (current != null) currentInView.bringIntoView() }
                StageCard(Modifier.testTag("raw-qualification-items"), padding = 8.dp) {
                    ordered.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        if (index == 0 && current != null) Box(Modifier.bringIntoViewRequester(currentInView)) { ItemRow(item) } else ItemRow(item)
                    }
                }
            }
            NoticeCard(stringResource(R.string.raw_qualification_running_notice, RAW_QUALIFICATION_ABORT_BATTERY_PERCENT),
                Modifier.testTag("raw-qualification-notice"))
        },
        footer = {
            OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(56.dp).testTag("raw-qualification-cancel")) {
                Text(stringResource(R.string.raw_qualification_cancel), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = OnbText)
            }
        },
    )
}

/** A muted note under the result, with an icon (info by default): how to try left-out configurations again. */
@Composable
private fun FinishedHint(text: String, tag: String, glyph: StepGlyph = StepGlyph.INFO) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        StepIcon(glyph, OnbMuted, Modifier.size(22.dp))
        Text(text, color = OnbMuted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f).testTag(tag))
    }
}

/**
 * The result of a run stopped after a sweep that found the write cache still flushing
 * ([rawQualificationStorageBusy]): rest the phone, then qualify again; any earlier qualification stays.
 */
@Composable
private fun StorageBusyStage(state: RawVideoQualificationUi, onDone: () -> Unit) {
    RawStageScaffold(
        tag = "raw-qualification-result",
        heading = { StageTitle(stringResource(R.string.raw_qualification_rest_title), stringResource(R.string.raw_qualification_rest_body)) },
        body = {
            FinishedHint(stringResource(R.string.raw_qualification_rest_hint), "raw-qualification-rest-hint", StepGlyph.HOURGLASS)
            if (state.previousKept) FinishedHint(stringResource(R.string.raw_qualification_rest_kept), "raw-qualification-rest-kept")
        },
        footer = { PrimaryButton(stringResource(R.string.raw_qualification_done), arrow = false, testTag = "raw-qualification-done", onClick = onDone) },
    )
}

@Composable
private fun FinishedStage(state: RawVideoQualificationUi, onDone: () -> Unit) {
    if (state.outcome == RawQualificationOutcomeKind.FAILED_TO_RUN && rawQualificationStorageBusy(state.reason)) {
        StorageBusyStage(state, onDone)
        return
    }
    val success = LocalCineColors.current.success
    val leftOut = rawQualificationItemsHeaviestFirst(state.items).filter {
        it.status == RawQualificationItemStatus.SKIPPED_SPACE || it.status == RawQualificationItemStatus.FAILED ||
            it.status == RawQualificationItemStatus.NOT_QUALIFIED || it.status == RawQualificationItemStatus.SKIPPED_HEAT ||
            it.status == RawQualificationItemStatus.CAMERA_LIMIT
    }
    val risky = rawQualificationItemsHeaviestFirst(state.items).filter { it.status == RawQualificationItemStatus.RISKY }
    val passedLabels = remember(state.passedLabels) { state.passedLabels.sortedWith(rawQualificationHeaviestFirst) }
    val riskyTint = LocalCineColors.current.pending
    val anySkippedForSpace = leftOut.any { it.status == RawQualificationItemStatus.SKIPPED_SPACE }
    val anySkippedForHeat = leftOut.any { it.status == RawQualificationItemStatus.SKIPPED_HEAT }
    RawStageScaffold(
        tag = "raw-qualification-result",
        heading = {
            when (state.outcome) {
                RawQualificationOutcomeKind.PASSED -> StageTitle(
                    pluralStringResource(R.plurals.raw_qualification_done_passed, state.passedLabels.size, state.passedLabels.size),
                    stringResource(R.string.raw_qualification_done_passed_body),
                )
                RawQualificationOutcomeKind.NO_CONFIG -> if (risky.isNotEmpty()) StageTitle(
                    stringResource(R.string.raw_qualification_done_risky_only), stringResource(R.string.raw_qualification_done_risky_only_body),
                ) else StageTitle(
                    stringResource(R.string.raw_qualification_done_none), stringResource(R.string.raw_qualification_done_none_body))
                else -> StageTitle(stringResource(R.string.raw_qualification_done_failed), rawQualificationReasonText(state.reason))
            }
        },
        body = {
            if (state.outcome == RawQualificationOutcomeKind.PASSED && state.passedLabels.isNotEmpty()) {
                StageCard(Modifier.testTag("raw-qualification-passed"), padding = 12.dp) {
                    passedLabels.forEachIndexed { index, label ->
                        if (index > 0) CardDivider()
                        val item = state.items.firstOrNull { it.label == label && it.status == RawQualificationItemStatus.PASSED }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(item?.let { Modifier.testTag("raw-qualification-item-${it.spec}") } ?: Modifier),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StepIcon(StepGlyph.CHECK_CIRCLE, success.onContainer, Modifier.size(24.dp), on = success.container)
                            Column(Modifier.weight(1f)) {
                                Text(rawConfigLabelNoBreak(label, rawCompressedWord()), color = OnbText, fontSize = 17.sp)
                                item?.let { rawAchievedRate(it) }?.let { Text(it, color = OnbMuted, fontSize = 14.sp, lineHeight = 20.sp) }
                            }
                        }
                    }
                }
            }
            // Choosable by hand, with a warning: the storage is too slow for their rate. Never left out.
            if (risky.isNotEmpty() && state.outcome != RawQualificationOutcomeKind.CANCELLED) {
                StageCard(Modifier.testTag("raw-qualification-risky"), padding = 12.dp) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StepIcon(StepGlyph.WARNING, riskyTint, Modifier.size(20.dp))
                        Text(stringResource(R.string.raw_qualification_risky_body), color = OnbText, fontSize = 14.sp, lineHeight = 20.sp,
                            modifier = Modifier.weight(1f).testTag("raw-qualification-risky-body"))
                    }
                    risky.forEach { item ->
                        CardDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp).testTag("raw-qualification-item-${item.spec}"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StepIcon(StepGlyph.WARNING, riskyTint, Modifier.size(24.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rawConfigLabelNoBreak(item.label, rawCompressedWord()), color = OnbText, fontSize = 17.sp)
                                rawAchievedRate(item)?.let { Text(it, color = OnbMuted, fontSize = 14.sp, lineHeight = 20.sp) }
                                val burst = item.burstSeconds
                                Text(if (burst == null) stringResource(R.string.raw_qualification_item_risky)
                                    else stringResource(R.string.raw_qualification_item_risky_burst, rawBurstDuration(burst)),
                                    color = OnbMuted, fontSize = 14.sp, lineHeight = 20.sp)
                            }
                            // No pill here: the card's body already names the risk, and at 384 dp a pill
                            // beside a long label breaks "fps" (es, de).
                        }
                    }
                }
            }
            if (leftOut.isNotEmpty()) {
                StageCard(Modifier.testTag("raw-qualification-left-out"), padding = 12.dp) {
                    leftOut.forEachIndexed { index, item ->
                        if (index > 0) CardDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            val glyph = when (item.status) {
                                RawQualificationItemStatus.SKIPPED_HEAT -> StepGlyph.THERMOMETER
                                RawQualificationItemStatus.CAMERA_LIMIT -> StepGlyph.CAMERA_OFF
                                else -> StepGlyph.BLOCK
                            }
                            StepIcon(glyph, OnbMuted, Modifier.size(24.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rawConfigLabelNoBreak(item.label, rawCompressedWord()), color = OnbText, fontSize = 17.sp)
                                Text(
                                    when (item.status) {
                                        RawQualificationItemStatus.SKIPPED_SPACE ->
                                            stringResource(R.string.raw_qualification_item_skipped, formatBytes(item.neededBytes))
                                        RawQualificationItemStatus.SKIPPED_HEAT -> stringResource(R.string.raw_qualification_item_too_hot)
                                        RawQualificationItemStatus.CAMERA_LIMIT -> rawQualificationSpecFps(item.spec)
                                            ?.let { stringResource(R.string.raw_qualification_item_camera_limit, it) }
                                            ?: stringResource(R.string.raw_qualification_item_not_qualified)
                                        else -> stringResource(R.string.raw_qualification_item_not_qualified)
                                    },
                                    color = OnbMuted, fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
            }
            if (anySkippedForHeat) FinishedHint(stringResource(R.string.raw_qualification_heat_hint), "raw-qualification-heat-hint")
            if (anySkippedForSpace) FinishedHint(stringResource(R.string.raw_qualification_retry_hint), "raw-qualification-retry-hint")
        },
        footer = { PrimaryButton(stringResource(R.string.raw_qualification_done), arrow = false, testTag = "raw-qualification-done", onClick = onDone) },
    )
}

/**
 * [RawQualificationStep] wired to the service: its estimate, the live conditions, and the start,
 * cancel and dismiss calls. [onLeave] runs after the result is dismissed; [selectOnPass] false (a
 * requalification from Settings) keeps the mode when something passes.
 */
@Composable
internal fun RawQualificationStepHost(
    binder: CaptureService.LocalBinder?,
    state: RawVideoQualificationUi?,
    onLeave: (passed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    selectOnPass: Boolean = true,
) {
    // Read again after a run: a cancelled or failed run may have changed the free space or the records.
    val info = remember(binder, state?.outcome) { binder?.rawVideoQualificationEstimate() }
    val conditions = rememberRawQualificationConditions()
    RawQualificationStep(
        info = info,
        conditions = conditions,
        state = state,
        onStart = { binder?.startRawVideoQualification(selectOnPass) },
        onCancel = { reason -> binder?.cancelRawVideoQualification(reason) },
        onLeave = { passed ->
            binder?.dismissRawVideoQualification()
            onLeave(passed)
        },
        modifier = modifier,
    )
}

/**
 * The wizard's shapes, still: the onboarding backdrop on its reduced-motion path, so a run of
 * several minutes with the camera and storage at full load spends no frames on it.
 */
@Composable
internal fun RawQualificationBackdrop(modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalReducedMotion provides true) {
        OnboardingBackdrop(
            progress = { OnboardingPage.DONE.ordinal.toFloat() + 1f },
            beat = { OnboardingPage.DONE.ordinal + 1 },
            intro = { 1f },
            logoBounds = null,
            dispersed = false,
            onDispersed = {},
            modifier = modifier.fillMaxSize(),
        )
    }
}

/**
 * The wizard's look on its own, over the app: the backdrop's shapes and [content] clear of the
 * hinge. The mode selector opens the qualification step in it.
 */
@Composable
internal fun RawQualificationFrame(hinge: FoldHinge?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // The empty pointer handler takes every touch, so none reaches the capture controls underneath.
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {}.testTag("raw-qualification-screen")) {
        RawQualificationBackdrop()
        HingeSafeSettingsPane(hinge, tag = "raw-qualification-pane") { content() }
    }
}
