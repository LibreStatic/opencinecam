/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.capture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.librestatic.opencinecam.camera.MonitoringSignalDomain
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.monitoringDisplayPoint
import com.librestatic.opencinecam.rawvideo.RawVideoCrop
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * PLAN-076: where a window of the RAW output sits on the viewfinder, and how the crop editor reshapes it. Every
 * editing operation works in sensor pixels and ends in [RawVideoCrop.snapped], so whatever the fingers do the result
 * is a window a take can write.
 */

/** An aspect the crop editor can lock a window to, as width over height in sensor pixels; [FREE] locks nothing. */
internal enum class RawCropAspect(val ratio: Float?, val tag: String, val label: String) {
    FREE(null, "free", ""),
    WIDE(16f / 9f, "16x9", "16:9"),
    UNIVISIUM(2f, "2x1", "2:1"),
    SCOPE(2.39f, "239x1", "2.39:1"),
    CLASSIC(4f / 3f, "4x3", "4:3"),
    SQUARE(1f, "1x1", "1:1"),
}

/** A corner of a window as the screen shows it. */
internal enum class RawCropCorner(val tag: String) { TOP_LEFT("tl"), TOP_RIGHT("tr"), BOTTOM_LEFT("bl"), BOTTOM_RIGHT("br") }

/**
 * Maps windows of a [sensorWidth] x [sensorHeight] RAW output onto [image], the recorded picture on screen. The RAW
 * output is sensor-oriented, like the ISP's analysis raster, so a point turns by [sensorOrientation] against the
 * display's [displayDegrees] and mirrors for a [frontFacing] camera exactly as [monitoringOverlayPoint] places the
 * analysis grid; the viewfinder shows the RAW output's whole field (PLAN-076 delivery 3a), so the map is linear.
 */
internal data class RawCropMapping(
    val image: Rect,
    val sensorWidth: Int,
    val sensorHeight: Int,
    val sensorOrientation: Int,
    val displayDegrees: Int,
    val frontFacing: Boolean,
) {
    private val rotation: Int
        get() = (sensorOrientation + if (frontFacing) displayDegrees else 360 - displayDegrees) % 360

    /** A point of the RAW output in sensor pixels, on screen. */
    fun screenPoint(x: Float, y: Float): Offset {
        val p = monitoringDisplayPoint(x / sensorWidth, y / sensorHeight, MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR,
            sensorOrientation, displayDegrees, frontFacing)
        return Offset(image.left + p.first * image.width, image.top + p.second * image.height)
    }

    /** The screen point [point] in sensor pixels of the RAW output: the inverse of [screenPoint]. */
    fun sensorPoint(point: Offset): Offset {
        val w = image.width.takeIf { it > 0f } ?: 1f
        val h = image.height.takeIf { it > 0f } ?: 1f
        var u = (point.x - image.left) / w
        val v = (point.y - image.top) / h
        if (frontFacing) u = 1f - u
        val (x, y) = when (rotation) {
            90 -> v to 1f - u
            180 -> 1f - u to 1f - v
            270 -> 1f - v to u
            else -> u to v
        }
        return Offset(x * sensorWidth, y * sensorHeight)
    }

    /** A screen movement from [from] to [to] in sensor pixels. */
    fun sensorDelta(from: Offset, to: Offset): Offset = sensorPoint(to) - sensorPoint(from)

    /** [crop] on screen. */
    fun screenRect(crop: RawVideoCrop): Rect = screenRect(crop.left, crop.top, crop.width, crop.height)

    /** The window at [left], [top] of [width] x [height] sensor pixels, on screen. */
    fun screenRect(left: Int, top: Int, width: Int, height: Int): Rect {
        val a = screenPoint(left.toFloat(), top.toFloat())
        val b = screenPoint((left + width).toFloat(), (top + height).toFloat())
        return Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }

    /**
     * Which sensor edges the screen [corner] of [crop] lies on: (left, top) are true when it is the window's left
     * edge and top edge in sensor pixels. The corner opposite stays put while it is dragged ([rawCropResized]).
     */
    fun sensorCorner(crop: RawVideoCrop, corner: RawCropCorner): Pair<Boolean, Boolean> {
        val rect = screenRect(crop)
        val screen = when (corner) {
            RawCropCorner.TOP_LEFT -> rect.topLeft
            RawCropCorner.TOP_RIGHT -> rect.topRight
            RawCropCorner.BOTTOM_LEFT -> rect.bottomLeft
            RawCropCorner.BOTTOM_RIGHT -> rect.bottomRight
        }
        val p = sensorPoint(screen)
        return (p.x < crop.left + crop.width / 2f) to (p.y < crop.top + crop.height / 2f)
    }
}

/** The narrowest window the editor keeps on a [sensorWidth]-wide output. */
private fun minWidth(sensorWidth: Int) = min(RawVideoCrop.MIN_WIDTH, sensorWidth)

/** The shortest window the editor keeps on a [sensorHeight]-tall output. */
private fun minHeight(sensorHeight: Int) = min(RawVideoCrop.MIN_HEIGHT, sensorHeight)

/** [crop] moved by ([dx], [dy]) sensor pixels and kept whole inside the sensor. */
internal fun rawCropMoved(crop: RawVideoCrop, dx: Float, dy: Float, sensorWidth: Int, sensorHeight: Int): RawVideoCrop =
    RawVideoCrop.snapped(sensorWidth, sensorHeight, (crop.left + dx).roundToInt(), (crop.top + dy).roundToInt(), crop.width, crop.height)

/**
 * [crop] resized by dragging the corner on its [movingLeft] / [movingTop] edges by ([dx], [dy]) sensor pixels while the
 * opposite corner stays put. The corner never crosses the anchor (the window keeps its minimum size) and stops at the
 * sensor's edge. With an [aspect] ratio the window keeps it: it follows whichever side the drag makes larger, then
 * shrinks to fit the room on the anchor's side.
 */
internal fun rawCropResized(
    crop: RawVideoCrop,
    movingLeft: Boolean,
    movingTop: Boolean,
    dx: Float,
    dy: Float,
    aspect: Float?,
    sensorWidth: Int,
    sensorHeight: Int,
): RawVideoCrop {
    val right = crop.left + crop.width
    val bottom = crop.top + crop.height
    val anchorX = if (movingLeft) right else crop.left
    val anchorY = if (movingTop) bottom else crop.top
    val maxW = (if (movingLeft) anchorX else sensorWidth - anchorX).toFloat()
    val maxH = (if (movingTop) anchorY else sensorHeight - anchorY).toFloat()
    val minW = minWidth(sensorWidth).toFloat()
    val minH = minHeight(sensorHeight).toFloat()
    val wantedW = if (movingLeft) crop.width - dx else crop.width + dx
    val wantedH = if (movingTop) crop.height - dy else crop.height + dy
    var w: Float
    var h: Float
    if (aspect == null) {
        w = wantedW.coerceIn(minW, max(minW, maxW))
        h = wantedH.coerceIn(minH, max(minH, maxH))
    } else {
        w = max(wantedW, wantedH * aspect)
        w = max(w, max(minW, minH * aspect))
        h = w / aspect
        if (h > maxH) { h = maxH; w = h * aspect }
        if (w > maxW) { w = maxW; h = w / aspect }
    }
    val left = if (movingLeft) anchorX - w else anchorX.toFloat()
    val top = if (movingTop) anchorY - h else anchorY.toFloat()
    return RawVideoCrop.snapped(sensorWidth, sensorHeight, left.roundToInt(), top.roundToInt(), w.roundToInt(), h.roundToInt())
}

/**
 * The window of [aspect] the editor switches [crop] to: [crop]'s width with the height the aspect gives, shrunk until it
 * fits the sensor, centred on [crop]'s centre and moved only as far as the sensor's edges require.
 */
internal fun rawCropFitAspect(crop: RawVideoCrop, aspect: Float, sensorWidth: Int, sensorHeight: Int): RawVideoCrop {
    var w = crop.width.toFloat()
    var h = w / aspect
    if (h > sensorHeight) { h = sensorHeight.toFloat(); w = h * aspect }
    if (w > sensorWidth) { w = sensorWidth.toFloat(); h = w / aspect }
    val minW = minWidth(sensorWidth).toFloat()
    val minH = minHeight(sensorHeight).toFloat()
    if (w < minW || h < minH) {
        val grow = max(minW / w, minH / h)
        w = min(w * grow, sensorWidth.toFloat())
        h = min(h * grow, sensorHeight.toFloat())
    }
    return rawCropCentredAt(crop.left + crop.width / 2f, crop.top + crop.height / 2f, w, h, sensorWidth, sensorHeight)
}

/**
 * [crop] grown ([grow]) or shrunk by [RAW_CROP_SCALE_STEP] about its centre; with an [aspect] it keeps that ratio,
 * otherwise its own. It stops at the sensor and at the minimum size.
 */
internal fun rawCropScaled(crop: RawVideoCrop, grow: Boolean, aspect: Float?, sensorWidth: Int, sensorHeight: Int): RawVideoCrop {
    val factor = if (grow) RAW_CROP_SCALE_STEP else 1f / RAW_CROP_SCALE_STEP
    val ratio = aspect ?: (crop.width.toFloat() / crop.height)
    var w = crop.width * factor
    var h = w / ratio
    if (h > sensorHeight) { h = sensorHeight.toFloat(); w = h * ratio }
    if (w > sensorWidth) { w = sensorWidth.toFloat(); h = w / ratio }
    val minW = minWidth(sensorWidth).toFloat()
    val minH = minHeight(sensorHeight).toFloat()
    if (w < minW || h < minH) {
        val up = max(minW / w, minH / h)
        w = min(w * up, sensorWidth.toFloat())
        h = min(h * up, sensorHeight.toFloat())
    }
    return rawCropCentredAt(crop.left + crop.width / 2f, crop.top + crop.height / 2f, w, h, sensorWidth, sensorHeight)
}

/** How much one grow or shrink step scales a window by (10 %). */
internal const val RAW_CROP_SCALE_STEP = 1.1f

/** A [width] x [height] window centred on ([cx], [cy]) in sensor pixels, moved inside the sensor and snapped. */
internal fun rawCropCentredAt(cx: Float, cy: Float, width: Float, height: Float, sensorWidth: Int, sensorHeight: Int): RawVideoCrop =
    RawVideoCrop.snapped(sensorWidth, sensorHeight, (cx - width / 2f).roundToInt(), (cy - height / 2f).roundToInt(),
        width.roundToInt(), height.roundToInt())

/** The window "New crop" starts from on the full sensor: 16:9, half the sensor's width, centred. */
internal fun rawCropDefaultWindow(sensorWidth: Int, sensorHeight: Int): RawVideoCrop =
    rawCropCentredAt(sensorWidth / 2f, sensorHeight / 2f, sensorWidth / 2f, sensorWidth / 2f * 9f / 16f, sensorWidth, sensorHeight)

/** True when [crop] covers the whole [sensorWidth] x [sensorHeight] output. */
internal fun rawCropIsFullSensor(crop: RawVideoCrop, sensorWidth: Int, sensorHeight: Int): Boolean =
    crop.left == 0 && crop.top == 0 && crop.width == sensorWidth && crop.height == sensorHeight

/**
 * The RAW video key of [crop] on a [sensorWidth] x [sensorHeight] output at [fps], block-pack when [compressed]: its
 * plain configuration key when centred ([RawVideoConfigKeys.key]), the origin written out otherwise.
 */
internal fun rawCropKey(crop: RawVideoCrop, sensorWidth: Int, sensorHeight: Int, fps: Int, compressed: Boolean): String {
    val config = RawVideoConfig("0", sensorWidth, sensorHeight, crop.width, crop.height, fps,
        if (compressed) RawVideoConfig.ENCODING_BLOCK_PACK else RawVideoConfig.ENCODING_RAW10)
    return RawVideoConfigKeys.key(config, crop)
}

/** The frame rate a RAW video key names (`1920x816+1080+1122@24+bp` reads 24); null when it names none. */
internal fun rawKeyFps(key: String?): Int? =
    key?.substringAfter('@', "")?.takeWhile { it.isDigit() }?.toIntOrNull()

/** True when a RAW video key names a block-pack configuration. */
internal fun rawKeyBlockPack(key: String?): Boolean = key?.endsWith(RawVideoConfigKeys.BLOCK_PACK_SUFFIX) == true

/** The uncompressed rate of the whole [sensorWidth] x [sensorHeight] output at [fps], what a window's saving is told against. */
internal fun rawFullSensorBytesPerSecond(sensorWidth: Int, sensorHeight: Int, fps: Int): Long =
    RawVideoConfig("0", sensorWidth, sensorHeight, sensorWidth, sensorHeight, fps).streamBytesPerSecond

/** How much less [bytesPerSecond] writes than the full sensor's [fullBytesPerSecond], in whole percent (0 to 100). */
internal fun rawCropSavingPercent(bytesPerSecond: Long, fullBytesPerSecond: Long): Int =
    if (fullBytesPerSecond <= 0L) 0
    else (100.0 - bytesPerSecond * 100.0 / fullBytesPerSecond).roundToInt().coerceIn(0, 100)

/**
 * The viewport that enlarges the picture in [viewport] uniformly until [window] (its screen rect under [viewport]) fills
 * [area], centred in it, and so letterboxed along one axis when their aspects differ. The surface, the monitoring
 * overlay and tap to focus all follow the result; what falls outside [area] is clipped. [viewport] itself when
 * [window] or [area] is empty.
 */
internal fun rawCropEnlargedViewport(viewport: PreviewViewport, window: Rect, area: Rect): PreviewViewport {
    if (!(window.width > 0f && window.height > 0f && area.width > 0f && area.height > 0f)) return viewport
    val scale = min(area.width / window.width, area.height / window.height)
    val centre = window.center
    return PreviewViewport(
        left = area.center.x - (centre.x - viewport.left) * scale,
        top = area.center.y - (centre.y - viewport.top) * scale,
        width = viewport.width * scale,
        height = viewport.height * scale,
    )
}

/** The part of [viewport] inside [bounds], what the frame shows when it overflows the window (a filled viewfinder). */
internal fun previewVisibleArea(viewport: PreviewViewport, bounds: Rect? = null): Rect {
    val frame = Rect(viewport.left, viewport.top, viewport.right, viewport.bottom)
    return bounds?.let { frame.intersect(it) }?.takeIf { it.width > 0f && it.height > 0f } ?: frame
}

/** [position] as a fraction of the picture in [viewport], what tap to focus sends, enlarged or not. */
internal fun rawCropTapPoint(viewport: PreviewViewport, position: Offset): Offset =
    Offset((position.x - viewport.left) / viewport.width, (position.y - viewport.top) / viewport.height)
