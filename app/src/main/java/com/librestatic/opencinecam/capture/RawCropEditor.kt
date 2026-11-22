/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.capture

import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.monitoringPreviewScale
import com.librestatic.opencinecam.gallery.MediaDialogFrame
import com.librestatic.opencinecam.rawvideo.RawVideoCrop
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.storage.RawCropStore
import com.librestatic.opencinecam.storage.RawSavedCrop
import com.librestatic.opencinecam.ui.HapticEvent
import com.librestatic.opencinecam.ui.LocalAppHaptics
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import com.librestatic.opencinecam.usesLogGraph
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * PLAN-076 delivery 3b: the crop guide over the viewfinder, the crop editor (its window on the frame and its panel in the
 * RES/FPS pane) and the custom crops of the RAW video chooser.
 */

/** What the crop editor and the custom crops ask of the capture service ([CaptureService.LocalBinder]); all on the main thread. */
internal interface RawCropActions {
    /** Selects the RAW video configuration [key] names; false when the service refuses it. */
    fun select(key: String): Boolean

    /** The gate's read-out of [key] ([CaptureService.LocalBinder.rawVideoCropVerdict]); null when it cannot judge it. */
    fun verdict(key: String): RawVideoCropPreview?

    /** Saves the window [key] names under [name]. */
    fun save(name: String, key: String): Boolean

    /** Renames the saved window [id]. */
    fun rename(id: String, name: String): Boolean

    /** Deletes the saved window [id]. */
    fun delete(id: String): Boolean
}

/** The crop actions of the capture chrome: the service's, or a headless render's fake. */
internal val LocalRawCropActions = staticCompositionLocalOf<RawCropActions?> { null }

/** The crop editor the capture chrome holds, read by the RES/FPS pane and the frame layer. */
internal val LocalRawCropEditor = compositionLocalOf<RawCropEditor?> { null }

/** [RawCropActions] on the capture service. */
internal fun binderRawCropActions(binder: CaptureService.LocalBinder): RawCropActions = object : RawCropActions {
    override fun select(key: String) = binder.selectRawVideoConfig(key)
    override fun verdict(key: String) = binder.rawVideoCropVerdict(key)
    override fun save(name: String, key: String) = binder.saveRawCrop(name, key)
    override fun rename(id: String, name: String) = binder.renameRawCrop(id, name)
    override fun delete(id: String) = binder.deleteRawCrop(id)
}

/**
 * A window being shaped in the editor on a [sensorWidth] x [sensorHeight] RAW output: the window itself, the aspect it is
 * locked to, and the rate and encoding it would record at. [key] is what Apply selects and Save as stores.
 */
@Stable
internal class RawCropEditState(val sensorWidth: Int, val sensorHeight: Int, window: RawVideoCrop, fps: Int, compressed: Boolean) {
    var window by mutableStateOf(window)
    var aspect by mutableStateOf(RawCropAspect.FREE)
    var fps by mutableIntStateOf(fps)
    var compressed by mutableStateOf(compressed)

    val key: String get() = rawCropKey(window, sensorWidth, sensorHeight, fps, compressed)

    /** Locks the window to [choice], reshaping it to that aspect about its centre. */
    fun chooseAspect(choice: RawCropAspect) {
        aspect = choice
        choice.ratio?.let { window = rawCropFitAspect(window, it, sensorWidth, sensorHeight) }
    }
}

/** Whether the crop editor is open, and on what; the capture chrome holds one and shares it through [LocalRawCropEditor]. */
@Stable
internal class RawCropEditor {
    var editing: RawCropEditState? by mutableStateOf(null)
        private set

    /**
     * Opens the editor on the window [state] selects, or with [fresh] on a new default window ([rawCropEditStateFor]);
     * false when it cannot open now.
     */
    fun open(state: CameraUiState, fresh: Boolean = false): Boolean {
        editing = rawCropEditStateFor(state, fresh) ?: return false
        return true
    }

    fun close() {
        editing = null
    }
}

/** The crop editor can open: RAW video is qualified and selected, its window known, and the configuration may change now. */
internal fun rawCropEditorAvailable(state: CameraUiState): Boolean =
    state.selectedMode == CaptureMode.RAW_VIDEO && state.modeGates[CaptureMode.RAW_VIDEO] == ModeGateState.AVAILABLE &&
        state.rawVideoCrop != null && rawVideoConfigChangeAllowed(state)

/**
 * Where the editor starts: the selected window, or on the full sensor or when [fresh] (New crop) a centred 16:9 window
 * half its width ([rawCropDefaultWindow]); the selected rate (else the highest custom rate up to 30 fps) and its encoding.
 */
internal fun rawCropEditStateFor(state: CameraUiState, fresh: Boolean = false): RawCropEditState? {
    if (!rawCropEditorAvailable(state)) return null
    val ui = state.rawVideoCrop ?: return null
    val crop = RawVideoCrop.snapped(ui.sensorWidth, ui.sensorHeight, ui.left, ui.top, ui.width, ui.height)
    val window = if (fresh || rawCropIsFullSensor(crop, ui.sensorWidth, ui.sensorHeight)) rawCropDefaultWindow(ui.sensorWidth, ui.sensorHeight) else crop
    val rates = state.rawVideoCustomRates
    val fps = rawKeyFps(state.rawVideoSelectedKey) ?: rates.lastOrNull { it <= 30 } ?: rates.firstOrNull() ?: 30
    val compressed = rawKeyBlockPack(state.rawVideoSelectedKey) && state.rawVideoOptions.any { it.isBlockPack }
    return RawCropEditState(ui.sensorWidth, ui.sensorHeight, window, fps, compressed)
}

/** The rates the editor offers: the camera's custom rates and the one it started at, ascending. */
internal fun rawCropEditorRates(customRates: List<Int>, current: Int): List<Int> = (customRates + current).distinct().sorted()

/**
 * Closes the editor once it no longer applies: the RES/FPS pane closed ([paneOpen] false), the mode or camera changed,
 * or the configuration may not change now (a take started).
 */
@Composable
internal fun RawCropEditorGuard(state: CameraUiState, editor: RawCropEditor, paneOpen: Boolean) {
    val edit = editor.editing ?: return
    val sensor = state.rawVideoCrop
    val keep = paneOpen && rawCropEditorAvailable(state) && sensor != null &&
        sensor.sensorWidth == edit.sensorWidth && sensor.sensorHeight == edit.sensorHeight
    if (!keep) SideEffect { editor.close() }
}

/** True when [crop] covers its whole sensor. */
internal fun RawVideoCropUi.isFullSensor(): Boolean = left == 0 && top == 0 && width == sensorWidth && height == sensorHeight

/** The saved window [state]'s selection draws, when the selection is one of them; window only, whatever the rate. */
internal fun rawSavedCropSelected(state: CameraUiState): RawSavedCrop? {
    val ui = state.rawVideoCrop ?: return null
    return state.rawVideoSavedCrops.firstOrNull { it.matches(ui) }
}

/** True when this saved window is [ui]'s window on the same sensor. */
internal fun RawSavedCrop.matches(ui: RawVideoCropUi): Boolean =
    sensorWidth == ui.sensorWidth && sensorHeight == ui.sensorHeight && left == ui.left && top == ui.top && width == ui.width && height == ui.height

/** What a saved crop's tile records at: its [key] at [fps], block-pack when [compressed], and the gate's read-out of it. */
internal data class RawSavedCropChoice(val key: String, val fps: Int, val compressed: Boolean, val preview: RawVideoCropPreview?) {
    val refused: Boolean get() = preview?.status == RawVideoCropStatus.REFUSED
    val risky: Boolean get() = preview?.status == RawVideoCropStatus.RISKY
}

/**
 * The rates a saved crop's tile tries, in order: the selected rate and encoding, then each custom rate from the highest
 * with that encoding, then, when it is block-pack, each from the highest uncompressed.
 */
internal fun rawSavedCropCandidates(currentFps: Int?, currentCompressed: Boolean, customRates: List<Int>): List<Pair<Int, Boolean>> {
    val descending = customRates.sortedDescending()
    return (listOfNotNull(currentFps?.let { it to currentCompressed }) + descending.map { it to currentCompressed } +
        if (currentCompressed) descending.map { it to false } else emptyList()).distinct()
}

/**
 * The rate a saved crop's tile records at: the first of [candidates] the gate does not refuse (one it cannot judge
 * counts as usable, the service judges it again on selection), else the first, refused, with its reason.
 */
internal fun rawSavedCropChoice(crop: RawSavedCrop, candidates: List<Pair<Int, Boolean>>, verdict: (String) -> RawVideoCropPreview?): RawSavedCropChoice? {
    var first: RawSavedCropChoice? = null
    for ((fps, compressed) in candidates) {
        val key = rawCropKey(crop.crop, crop.sensorWidth, crop.sensorHeight, fps, compressed)
        val choice = RawSavedCropChoice(key, fps, compressed, verdict(key))
        if (!choice.refused) return choice
        if (first == null) first = choice
    }
    return first
}

/** "the phone was too hot at this rate", "storage too slow for this rate": why the gate refuses a window at [fps]. */
@Composable
internal fun rawCropRefusedReason(reason: RawVideoLeftOutReason?, fps: Int): String = when (reason) {
    RawVideoLeftOutReason.HEAT -> stringResource(R.string.raw_crop_refused_heat)
    RawVideoLeftOutReason.CAMERA_LIMIT -> stringResource(R.string.raw_qualification_item_camera_limit, fps)
    RawVideoLeftOutReason.STORAGE -> stringResource(R.string.raw_qualification_item_risky)
    RawVideoLeftOutReason.NOT_QUALIFIED, null -> stringResource(R.string.raw_crop_refused_not_qualified)
}

/** "1920 × 816 · +1080 +1122": a window's size and its origin in the RAW output. */
@Composable
internal fun rawCropSizeText(crop: RawVideoCrop): String = stringResource(R.string.raw_crop_size, crop.width, crop.height, crop.left, crop.top)

/**
 * How a window of the RAW output maps onto the picture in [viewport] (the frame's coordinates) for the camera of [state]
 * and the display's rotation [displayDegrees]: the picture is the GPU viewfinder's aspect-fit quad (with [squeeze]), or
 * the whole viewport. Null without a camera or with an empty viewport.
 */
internal fun rawCropMappingFor(state: CameraUiState, viewport: PreviewViewport, sensorWidth: Int, sensorHeight: Int, displayDegrees: Int,
    squeeze: Float): RawCropMapping? {
    val descriptor = state.descriptor ?: return null
    if (!(viewport.width > 0f && viewport.height > 0f)) return null
    val front = descriptor.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
    val source = descriptor.rawVideoViewfinderSize
    val scale = if (state.gpuViewfinder || state.selectedMode.usesLogGraph) {
        monitoringPreviewScale(source.width, source.height, viewport.width.roundToInt().coerceAtLeast(1), viewport.height.roundToInt().coerceAtLeast(1),
            descriptor.sensorOrientation, displayDegrees, front, squeeze)
    } else 1f to 1f
    val image = monitoringImageRect(viewport.width, viewport.height, scale.first, scale.second).translate(viewport.left, viewport.top)
    return RawCropMapping(image, sensorWidth, sensorHeight, descriptor.sensorOrientation, displayDegrees, front)
}

/** The selected RAW window's rect over the picture in [viewport], or null outside RAW video or on the full sensor. */
internal fun rawCropWindowRect(state: CameraUiState, viewport: PreviewViewport, displayDegrees: Int, squeeze: Float): Rect? {
    if (state.selectedMode != CaptureMode.RAW_VIDEO) return null
    val crop = state.rawVideoCrop?.takeUnless { it.isFullSensor() } ?: return null
    return rawCropMappingFor(state, viewport, crop.sensorWidth, crop.sensorHeight, displayDegrees, squeeze)
        ?.screenRect(crop.left, crop.top, crop.width, crop.height)
}

/**
 * The enlarged crop view (PLAN-076) can be offered: RAW video on a selected window smaller than the RAW output, with
 * the crop editor closed.
 */
internal fun rawCropViewAvailable(state: CameraUiState, editorOpen: Boolean): Boolean =
    !editorOpen && state.selectedMode == CaptureMode.RAW_VIDEO && state.rawVideoCrop?.isFullSensor() == false

/**
 * The RAW video window over the viewfinder, in the frame's coordinates ([viewport]): the editor's window while it is open,
 * else the guide of a selected window smaller than the sensor. Nothing outside RAW video. Only the editor takes touches.
 * [enlargedArea] is the frame's visible area while the enlarged crop view is on: the guide is then only the window's
 * frame line inside it.
 */
@Composable
internal fun RawCropFrameLayer(
    state: CameraUiState,
    settings: CameraSettings,
    viewport: PreviewViewport,
    modifier: Modifier = Modifier,
    enlargedArea: Rect? = null,
) {
    if (state.selectedMode != CaptureMode.RAW_VIDEO) return
    val edit = LocalRawCropEditor.current?.editing
    val crop = state.rawVideoCrop
    if (edit == null && (crop == null || crop.isFullSensor())) return
    val displayDegrees = HorizonRollMath.surfaceRotationDegrees(LocalView.current.display?.rotation ?: Surface.ROTATION_0)
    val squeeze = settings.anamorphicSqueeze.factor
    if (edit != null) {
        val mapping = rawCropMappingFor(state, viewport, edit.sensorWidth, edit.sensorHeight, displayDegrees, squeeze) ?: return
        RawCropEditorFrame(edit, mapping, modifier)
    } else if (crop != null) {
        val mapping = rawCropMappingFor(state, viewport, crop.sensorWidth, crop.sensorHeight, displayDegrees, squeeze) ?: return
        RawCropGuide(crop, mapping, enlargedArea, modifier)
    }
}

/**
 * The guide of [crop] on the picture [image] of the monitoring overlay, for the layouts whose chrome does not draw it
 * ([RawCropFrameLayer]): a hinge split and the minimal self-recording controls.
 */
@Composable
internal fun RawCropOverlayGuide(state: CameraUiState, image: Rect, sensorOrientation: Int, displayDegrees: Int, frontFacing: Boolean,
    modifier: Modifier = Modifier) {
    if (state.selectedMode != CaptureMode.RAW_VIDEO) return
    val crop = state.rawVideoCrop?.takeUnless { it.isFullSensor() } ?: return
    RawCropGuide(crop, RawCropMapping(image, crop.sensorWidth, crop.sensorHeight, sensorOrientation, displayDegrees, frontFacing), null, modifier)
}

/** How dark the picture outside the window is. */
private const val RAW_CROP_SHADE_ALPHA = 0.55f

/**
 * The window [crop] on screen: the picture dimmed around it, a thin frame and corner marks. With [enlargedArea] (the
 * enlarged crop view) nothing outside the window shows, so only the frame line is drawn, inset to stay inside the area.
 * It takes no touches.
 */
@Composable
private fun RawCropGuide(crop: RawVideoCropUi, mapping: RawCropMapping, enlargedArea: Rect?, modifier: Modifier) {
    val window = mapping.screenRect(crop.left, crop.top, crop.width, crop.height)
    val description = stringResource(R.string.raw_crop_guide_description, crop.width, crop.height)
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            if (enlargedArea != null) {
                val stroke = 1.dp.toPx()
                val line = window.intersect(enlargedArea).deflate(stroke / 2f)
                if (line.width > 0f && line.height > 0f) {
                    drawRect(Color.White.copy(alpha = 0.8f), line.topLeft, line.size, style = Stroke(stroke))
                }
            } else {
                drawRawCropWindow(mapping.image, window, Color.White.copy(alpha = 0.8f), 1.dp.toPx(), 14.dp.toPx(), 2.dp.toPx())
            }
        }
        RectNode(window, Modifier.testTag("raw-crop-guide").semantics { contentDescription = description })
    }
}

/** An empty node laid over [rect], for the semantics of what the canvas draws there. */
@Composable
private fun RectNode(rect: Rect, modifier: Modifier) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .then(modifier),
    )
}

/** Dims [image] outside [window], then frames the window and marks its corners. */
private fun DrawScope.drawRawCropWindow(image: Rect, window: Rect, frame: Color, frameWidth: Float, markLength: Float, markWidth: Float) {
    val shade = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(image)
        addRect(window)
    }
    drawPath(shade, Color.Black.copy(alpha = RAW_CROP_SHADE_ALPHA))
    drawRect(frame, topLeft = window.topLeft, size = window.size, style = Stroke(frameWidth))
    val mark = min(markLength, min(window.width, window.height) / 2f)
    listOf(window.topLeft to Offset(1f, 1f), window.topRight to Offset(-1f, 1f), window.bottomLeft to Offset(1f, -1f),
        window.bottomRight to Offset(-1f, -1f)).forEach { (corner, inward) ->
        drawLine(frame, corner, corner + Offset(inward.x * mark, 0f), markWidth)
        drawLine(frame, corner, corner + Offset(0f, inward.y * mark), markWidth)
    }
}

/**
 * How far from a corner a touch still grabs it: the far corner of its handle's 48 dp target, which sits
 * [RAW_CROP_HANDLE_OUTSET] outward (36 dp each way, about 51 dp), so a touch anywhere on a handle resizes.
 */
private val RAW_CROP_HANDLE_REACH = 52.dp

/** How far each handle's 48 dp target sits outward from its corner, so it leaves the window's middle free for moving. */
private val RAW_CROP_HANDLE_OUTSET = 12.dp

/**
 * The editor's window on the frame. A drag inside moves it, a drag by a corner resizes it against the opposite corner;
 * every touch on the layer stops here, so focus and zoom gestures wait until the editor closes. The window node carries
 * the same edits as custom accessibility actions, and the four handles say which corner they resize.
 */
@Composable
private fun RawCropEditorFrame(edit: RawCropEditState, mapping: RawCropMapping, modifier: Modifier) {
    val density = LocalDensity.current
    val window = mapping.screenRect(edit.window)
    val currentMapping by rememberUpdatedState(mapping)
    val reach = with(density) { RAW_CROP_HANDLE_REACH.toPx() }
    val haptics = LocalAppHaptics.current
    Box(modifier.pointerInput(edit, reach) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val map = currentMapping
            val start = edit.window
            val rect = map.screenRect(start)
            val corner = rawCropGrabbedCorner(rect, down.position, reach)
            val moving = corner == null && rect.contains(down.position)
            val edges = corner?.let { map.sensorCorner(start, it) }
            if (corner != null || moving) haptics.perform(HapticEvent.Select)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                event.changes.forEach { it.consume() }
                if (change == null || !change.pressed) break
                val delta = map.sensorDelta(down.position, change.position)
                if (edges != null) {
                    edit.window = rawCropResized(start, edges.first, edges.second, delta.x, delta.y, edit.aspect.ratio, edit.sensorWidth, edit.sensorHeight)
                } else if (moving) {
                    edit.window = rawCropMoved(start, delta.x, delta.y, edit.sensorWidth, edit.sensorHeight)
                }
            }
        }
    }) {
        Canvas(Modifier.matchParentSize()) {
            // The whole picture's outline, so the sensor's limits show over a dark scene too.
            drawRect(Color.White.copy(alpha = 0.35f), mapping.image.topLeft, mapping.image.size, style = Stroke(1.dp.toPx()))
            drawRawCropWindow(mapping.image, window, Color.White, 1.5.dp.toPx(), 22.dp.toPx(), 4.dp.toPx())
        }
        // The panel has no room for the gesture hint, so the window says it.
        val description = stringResource(R.string.raw_crop_frame_description) + ". " + stringResource(R.string.raw_crop_editor_hint)
        val size = rawCropSizeText(edit.window)
        val actions = rawCropNudgeActions(edit, mapping)
        RectNode(window, Modifier.testTag("raw-crop-editor-frame").semantics {
            contentDescription = description
            stateDescription = size
            customActions = actions
        })
        val outset = with(density) { RAW_CROP_HANDLE_OUTSET.toPx() }
        val half = with(density) { 24.dp.toPx() }
        RawCropCorner.entries.forEach { corner ->
            val centre = when (corner) {
                RawCropCorner.TOP_LEFT -> window.topLeft + Offset(-outset, -outset)
                RawCropCorner.TOP_RIGHT -> window.topRight + Offset(outset, -outset)
                RawCropCorner.BOTTOM_LEFT -> window.bottomLeft + Offset(-outset, outset)
                RawCropCorner.BOTTOM_RIGHT -> window.bottomRight + Offset(outset, outset)
            }
            val handle = stringResource(when (corner) {
                RawCropCorner.TOP_LEFT -> R.string.raw_crop_handle_tl
                RawCropCorner.TOP_RIGHT -> R.string.raw_crop_handle_tr
                RawCropCorner.BOTTOM_LEFT -> R.string.raw_crop_handle_bl
                RawCropCorner.BOTTOM_RIGHT -> R.string.raw_crop_handle_br
            })
            RectNode(Rect(centre.x - half, centre.y - half, centre.x + half, centre.y + half),
                Modifier.testTag("raw-crop-handle-${corner.tag}").semantics { contentDescription = handle })
        }
    }
}

/**
 * The corner of [rect] a touch at [point] grabs: the nearest within [reach], unless the touch is well inside the window,
 * where it moves it instead (a small window keeps a middle to move by).
 */
internal fun rawCropGrabbedCorner(rect: Rect, point: Offset, reach: Float): RawCropCorner? {
    val corners = mapOf(RawCropCorner.TOP_LEFT to rect.topLeft, RawCropCorner.TOP_RIGHT to rect.topRight,
        RawCropCorner.BOTTOM_LEFT to rect.bottomLeft, RawCropCorner.BOTTOM_RIGHT to rect.bottomRight)
    val (corner, distance) = corners.map { (c, p) -> c to hypot(point.x - p.x, point.y - p.y) }.minBy { it.second }
    if (distance > reach) return null
    val inside = rect.contains(point) && distance > min(reach, min(rect.width, rect.height) * 0.3f)
    return if (inside) null else corner
}

/** The editor's accessible edits: a step each way on screen (a fiftieth of the picture), and grow and shrink. */
@Composable
private fun rawCropNudgeActions(edit: RawCropEditState, mapping: RawCropMapping): List<CustomAccessibilityAction> {
    val labels = listOf(R.string.raw_crop_move_left, R.string.raw_crop_move_right, R.string.raw_crop_move_up, R.string.raw_crop_move_down)
        .map { stringResource(it) }
    val grow = stringResource(R.string.raw_crop_grow)
    val shrink = stringResource(R.string.raw_crop_shrink)
    val currentMapping by rememberUpdatedState(mapping)
    return remember(edit, labels, grow, shrink) {
        fun nudge(dx: Float, dy: Float): Boolean {
            val map = currentMapping
            val step = maxOf(map.image.width, map.image.height) / 50f
            val centre = map.image.center
            val delta = map.sensorDelta(centre, centre + Offset(dx * step, dy * step))
            edit.window = rawCropMoved(edit.window, delta.x, delta.y, edit.sensorWidth, edit.sensorHeight)
            return true
        }
        fun scale(up: Boolean): Boolean {
            edit.window = rawCropScaled(edit.window, up, edit.aspect.ratio, edit.sensorWidth, edit.sensorHeight)
            return true
        }
        listOf(
            CustomAccessibilityAction(labels[0]) { nudge(-1f, 0f) },
            CustomAccessibilityAction(labels[1]) { nudge(1f, 0f) },
            CustomAccessibilityAction(labels[2]) { nudge(0f, -1f) },
            CustomAccessibilityAction(labels[3]) { nudge(0f, 1f) },
            CustomAccessibilityAction(grow) { scale(true) },
            CustomAccessibilityAction(shrink) { scale(false) },
        )
    }
}

/**
 * The editor's panel in the RES/FPS pane, compact so the frame above keeps its height: the window's size and origin, the
 * aspect lock and the rate on one row each, the encoding, what the gate says of the result, and Cancel, Save as and
 * Apply pinned at the bottom (the rest scrolls on a short window). The verdict is read in an effect, keyed on the
 * window's key. Back and Cancel return to the chooser; Apply selects the window and closes the pane.
 */
@Composable
internal fun RawCropEditorPanel(
    state: CameraUiState,
    settings: CameraSettings,
    editor: RawCropEditor,
    edit: RawCropEditState,
    select: (String) -> Boolean,
    onClose: () -> Unit,
) {
    BackHandler { editor.close() }
    val actions = LocalRawCropActions.current
    val key = edit.key
    val verdict by produceState<Pair<String, RawVideoCropPreview?>?>(null, key, actions) { value = key to actions?.verdict(key) }
    val judged = verdict?.first == key
    val preview = verdict?.takeIf { judged }?.second
    val allowed = rawVideoConfigChangeAllowed(state)
    val nearLossless = rawVideoNearLosslessActive(state, settings)
    var naming by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("raw-crop-editor-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RawCropEditorHeader(edit, onClose)
            val aspectLabel = stringResource(R.string.raw_crop_aspect)
            RawCropPillRow(RawCropAspect.entries, aspectLabel, Modifier.testTag("raw-crop-aspects"),
                // "2.39:1" needs more room than the other names to stay on one line.
                weight = { if (it.label.length > 4) 1.4f else 1f }) { aspect, modifier ->
                val name = if (aspect.ratio == null) stringResource(R.string.raw_crop_aspect_free) else aspect.label
                val spoken = stringResource(R.string.raw_crop_aspect_value, name)
                ChoiceTile(name, edit.aspect == aspect, modifier.testTag("raw-crop-aspect-${aspect.tag}").semantics { contentDescription = spoken },
                    enabled = allowed) { edit.chooseAspect(aspect) }
            }
            val rates = rawCropEditorRates(state.rawVideoCustomRates, edit.fps)
            RawCropPillRow(rates, stringResource(R.string.raw_crop_fps), Modifier.testTag("raw-crop-rates")) { fps, modifier ->
                val spoken = stringResource(R.string.raw_crop_fps_value, fps)
                ChoiceTile(fps.toString(), edit.fps == fps, modifier.testTag("raw-crop-fps-$fps").semantics { contentDescription = spoken },
                    enabled = allowed) { edit.fps = fps }
            }
            if (state.rawVideoOptions.any { it.isBlockPack }) {
                val haptics = LocalAppHaptics.current
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(edit.compressed, enabled = allowed, role = Role.Switch) { haptics.perform(HapticEvent.Toggle); edit.compressed = it }
                        .testTag("raw-crop-compressed"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(rawCompressedWord(nearLossless), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    Switch(edit.compressed, onCheckedChange = null, enabled = allowed)
                }
            }
            RawCropEditorStatus(edit, preview, judged)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ editor.close() }, Modifier.testTag("raw-crop-cancel")) { Text(stringResource(android.R.string.cancel), maxLines = 1) }
            // Save as takes what Cancel and Apply leave (a weighted child is measured last), so Apply never truncates.
            OutlinedButton({ naming = true }, Modifier.weight(1f, fill = false).testTag("raw-crop-save"),
                enabled = allowed && actions != null && state.rawVideoSavedCrops.size < RawCropStore.MAX_CROPS,
                contentPadding = RAW_CROP_BUTTON_PADDING) {
                Text(stringResource(R.string.raw_crop_save_as), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button({ if (select(key)) { editor.close(); onClose() } }, Modifier.testTag("raw-crop-apply"),
                enabled = allowed && preview != null && preview.status != RawVideoCropStatus.REFUSED,
                contentPadding = RAW_CROP_BUTTON_PADDING) {
                Text(stringResource(R.string.raw_crop_apply), maxLines = 1)
            }
        }
    }
    if (naming) {
        RawCropNameDialog(stringResource(R.string.raw_crop_name_title), "", onDismiss = { naming = false }) { name ->
            naming = false
            if (actions?.save(name, key) == true) {
                // Saving a window the gate accepts also selects it; a refused one is only kept for later.
                if (preview != null && preview.status != RawVideoCropStatus.REFUSED) select(key)
                editor.close()
            }
        }
    }
}

/** The editor's title row: the panel's name over the window's size and origin (`raw-crop-editor-size`), and the close key. */
@Composable
private fun RawCropEditorHeader(edit: RawCropEditState, onClose: () -> Unit) {
    val title = stringResource(R.string.raw_crop_editor_title)
    val closeDescription = stringResource(R.string.close_panel, title)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.semantics { heading() })
            Text(rawCropSizeText(edit.window), Modifier.testTag("raw-crop-editor-size"), color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Box(
            Modifier.size(48.dp).semantics { contentDescription = closeDescription }.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("×", color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp) }
    }
}

/** The editor's buttons are narrower than Material's defaults so the three fit one row in German at 384 dp. */
private val RAW_CROP_BUTTON_PADDING = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

/** Narrowest pill of a one-row choice before the row scrolls sideways instead. */
private val RAW_CROP_PILL_MIN_WIDTH = 48.dp

/**
 * [items] as pills on one row named [label] for accessibility: widths in proportion to [weight] when a weight of 1
 * gets at least [RAW_CROP_PILL_MIN_WIDTH], else as wide as their labels in a row that scrolls sideways.
 */
@Composable
private fun <T> RawCropPillRow(
    items: List<T>,
    label: String,
    modifier: Modifier = Modifier,
    weight: (T) -> Float = { 1f },
    pill: @Composable (T, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = label; isTraversalGroup = true }) {
        val gap = 6.dp
        val totalWeight = items.sumOf { weight(it).toDouble() }.toFloat().coerceAtLeast(1f)
        val fits = (maxWidth - gap * (items.size - 1)) / totalWeight >= RAW_CROP_PILL_MIN_WIDTH
        if (fits) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                items.forEach { pill(it, Modifier.weight(weight(it)).fillMaxHeight()) }
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(gap)) {
                // Each pill as wide as its label on one line ("2.39:1" must not wrap), never narrower than the minimum.
                items.forEach { pill(it, Modifier.widthIn(min = RAW_CROP_PILL_MIN_WIDTH + 8.dp).fillMaxHeight()) }
            }
        }
    }
}

/**
 * The editor's read-out, a polite live region on at most two lines: the data rate and how much less it writes than the
 * full sensor, then whether it records (ready, reaching a lower rate, risky with its burst) or why it is refused.
 */
@Composable
private fun RawCropEditorStatus(edit: RawCropEditState, preview: RawVideoCropPreview?, judged: Boolean) {
    val colors = LocalCineColors.current
    val text: AnnotatedString = when {
        !judged -> AnnotatedString("")
        preview == null -> AnnotatedString(stringResource(R.string.raw_crop_status_unknown))
        else -> {
            val written = if (edit.compressed) preview.capacityBytesPerSecond / 1_000_000L else null
            val rate = rawRateText((preview.bytesPerSecond / 1_000_000.0).roundToInt().toLong(), written)
            val saving = stringResource(R.string.raw_crop_saving,
                rawCropSavingPercent(preview.bytesPerSecond, rawFullSensorBytesPerSecond(edit.sensorWidth, edit.sensorHeight, edit.fps)))
            val (verdictText, color) = when (preview.status) {
                RawVideoCropStatus.PASSED -> (preview.achievedFps?.let { stringResource(R.string.raw_crop_status_reduced, it) }
                    ?: stringResource(R.string.raw_crop_status_ready)) to colors.ok
                RawVideoCropStatus.RISKY -> (preview.burstSeconds?.let { stringResource(R.string.raw_video_risky_notice_burst, rawBurstDuration(it)) }
                    ?: stringResource(R.string.raw_video_risky_notice)) to colors.pending
                RawVideoCropStatus.REFUSED -> stringResource(R.string.raw_crop_status_refused, rawCropRefusedReason(preview.refusedReason, edit.fps)) to
                    MaterialTheme.colorScheme.error
            }
            buildAnnotatedString {
                append("$rate · $saving. ")
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(verdictText) }
            }
        }
    }
    Text(text, Modifier.fillMaxWidth().testTag("raw-crop-editor-status").semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 3)
}

/** A crop's name for Save as or Rename: 1 to [RawCropStore.MAX_NAME_LENGTH] characters once trimmed. */
@Composable
internal fun RawCropNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val valid = RawCropStore.validName(name)
    MediaDialogFrame("raw-crop-name-dialog", onDismiss) {
        Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it.take(RawCropStore.MAX_NAME_LENGTH) }, label = { Text(stringResource(R.string.raw_crop_name_label)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("raw-crop-name-field"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onDismiss, Modifier.testTag("raw-crop-name-cancel")) { Text(stringResource(android.R.string.cancel)) }
            TextButton({ valid?.let(onConfirm) }, Modifier.testTag("raw-crop-name-confirm"), enabled = valid != null) {
                Text(stringResource(R.string.raw_crop_name_save))
            }
        }
    }
}

/** A saved crop's long-press dialog: rename it or delete it. */
@Composable
private fun RawCropManageDialog(crop: RawSavedCrop, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    MediaDialogFrame("raw-crop-manage-dialog", onDismiss) {
        Text(crop.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge)
        Text(rawCropSizeText(crop.crop), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onDismiss, Modifier.testTag("raw-crop-manage-cancel")) { Text(stringResource(android.R.string.cancel)) }
            TextButton(onDelete, Modifier.testTag("raw-crop-manage-delete")) {
                Text(stringResource(R.string.raw_crop_delete), color = MaterialTheme.colorScheme.error)
            }
            TextButton(onRename, Modifier.testTag("raw-crop-manage-rename")) { Text(stringResource(R.string.raw_crop_rename)) }
        }
    }
}

/** A tile of the chooser's custom crop section. */
private sealed interface CustomCropTile {
    data class Saved(val crop: RawSavedCrop) : CustomCropTile
    data object Current : CustomCropTile
    data object Edit : CustomCropTile
    data object New : CustomCropTile
}

/**
 * The chooser's CUSTOM CROP section: a tile per saved crop at the rate [rawSavedCropChoice] picks (long-press to rename
 * or delete it), the unsaved custom selection, Edit crop when the selection is custom, and New crop.
 */
@Composable
internal fun RawVideoCustomCropSection(
    state: CameraUiState,
    chosenKey: String,
    nearLossless: Boolean,
    enabled: Boolean,
    select: (String) -> Boolean,
    onClose: () -> Unit,
) {
    val sensor = state.rawVideoCrop ?: return
    val editor = LocalRawCropEditor.current
    val actions = LocalRawCropActions.current
    val saved = state.rawVideoSavedCrops
    val currentFps = rawKeyFps(state.rawVideoSelectedKey)
    val currentCompressed = rawKeyBlockPack(state.rawVideoSelectedKey)
    val candidates = rawSavedCropCandidates(currentFps, currentCompressed, state.rawVideoCustomRates)
    val judged by produceState<Map<String, RawSavedCropChoice?>>(emptyMap(), saved, candidates, actions) {
        value = saved.associate { crop -> crop.id to rawSavedCropChoice(crop, candidates) { actions?.verdict(it) } }
    }
    val activeCustom = rawVideoActiveOption(state)?.takeIf { it.isCustom && it.key == chosenKey }
    val selectedSaved = if (chosenKey.isNotEmpty()) saved.firstOrNull { it.matches(sensor) } else null
    val tiles = saved.map { CustomCropTile.Saved(it) } +
        listOfNotNull(CustomCropTile.Current.takeIf { activeCustom != null && selectedSaved == null }, CustomCropTile.Edit.takeIf { activeCustom != null }) +
        CustomCropTile.New
    var managing by remember { mutableStateOf<RawSavedCrop?>(null) }
    var renaming by remember { mutableStateOf<RawSavedCrop?>(null) }
    val manageLabel = stringResource(R.string.raw_video_crop_manage)
    val canOpen = enabled && editor != null && rawCropEditorAvailable(state)
    PanelSectionLabel(stringResource(R.string.raw_video_group_custom))
    ChoiceGrid(tiles, minTileWidth = 150.dp, maxColumns = 3) { tile, modifier ->
        when (tile) {
            is CustomCropTile.Saved -> {
                val crop = tile.crop
                val choice = judged[crop.id] ?: rawSavedCropChoice(crop, candidates) { null }
                val label = choice?.let {
                    rawLocalizedLabel(RawVideoConfigOption.of(RawVideoConfig("0", crop.sensorWidth, crop.sensorHeight, crop.width, crop.height, it.fps,
                        if (it.compressed) RawVideoConfig.ENCODING_BLOCK_PACK else RawVideoConfig.ENCODING_RAW10)).label, nearLossless && it.compressed)
                } ?: "${crop.width}×${crop.height}"
                val detail = if (choice?.refused == true) stringResource(R.string.raw_crop_status_refused,
                    rawCropRefusedReason(choice.preview?.refusedReason, choice.fps)) else label
                ChoiceTile(crop.name, crop == selectedSaved, modifier.testTag("raw-video-crop-${crop.id}"),
                    enabled = enabled && choice != null && !choice.refused, detail = detail,
                    warning = if (choice?.risky == true) stringResource(R.string.raw_video_risky_label, crop.name) else null,
                    onLongClick = { managing = crop }, longClickLabel = manageLabel) {
                    if (choice != null && select(choice.key)) onClose()
                }
            }
            CustomCropTile.Current -> activeCustom?.let { option ->
                ChoiceTile(stringResource(R.string.raw_video_crop_custom), true, modifier.testTag("raw-video-crop-current"), enabled = enabled,
                    detail = rawOptionBaseLabel(option, nearLossless), warning = rawOptionWarning(option, nearLossless)) { onClose() }
            }
            CustomCropTile.Edit -> ChoiceTile(stringResource(R.string.raw_video_crop_edit), false, modifier.testTag("raw-video-crop-edit"),
                enabled = canOpen, detail = stringResource(R.string.raw_video_crop_edit_detail)) { editor?.open(state) }
            CustomCropTile.New -> ChoiceTile(stringResource(R.string.raw_video_crop_new), false, modifier.testTag("raw-video-crop-new"),
                enabled = canOpen, detail = stringResource(R.string.raw_video_crop_new_detail)) { editor?.open(state, fresh = true) }
        }
    }
    managing?.let { crop ->
        RawCropManageDialog(crop, onDismiss = { managing = null }, onRename = { managing = null; renaming = crop }) {
            managing = null
            actions?.delete(crop.id)
        }
    }
    renaming?.let { crop ->
        RawCropNameDialog(stringResource(R.string.raw_crop_rename_title), crop.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            actions?.rename(crop.id, name)
        }
    }
}
