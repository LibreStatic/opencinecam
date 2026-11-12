/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawFrameRead
import com.librestatic.opencinecam.media.ocraw.OcrawFrameSource
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.roundToInt

/** An upright picture of a RAW take as packed ARGB_8888 pixels, top row first. */
class RawThumbnail(val width: Int, val height: Int, val argb: IntArray)

/**
 * The gallery picture of a `.ocraw` take: its first readable frame, developed like the review's
 * real-time path ([RawDevelop.developHalf]) and turned upright by the take's `orientation`.
 *
 * A take can run to thousands of frames, so only its start is read: the frame index stops after
 * [LEADING_FRAMES] frames (headers only, plus the metadata between them, where a late capture result
 * still lands), and at most [FRAMES_TRIED] of them are read and decoded, one slot at a time.
 * Compressed frames decode through [OcrawFrameSource], exactly as the review reads them.
 */
object RawThumbnails {
    /** The long side of the picture, at most; the grid's tiles are smaller than this. */
    const val MAX_EDGE = 256

    /** Frames the index walk lists before it stops. */
    const val LEADING_FRAMES = 16

    /** Damaged or undecodable leading frames are skipped up to this many; then there is no picture. */
    const val FRAMES_TRIED = 4

    /**
     * The picture of the take open on [channel] (left open), or null when it is no RAW take, has no
     * readable frame among the first [FRAMES_TRIED], or anything else goes wrong: a tile keeps its
     * placeholder rather than fail. Blocking file I/O and a CPU develop; call it off the main thread.
     */
    fun render(channel: FileChannel, maxEdge: Int = MAX_EDGE): RawThumbnail? = try {
        renderOrThrow(channel, maxEdge)
    } catch (interrupted: ClosedByInterruptException) {
        throw interrupted
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        // A full-resolution frame slot is a large direct buffer: no room for it is no thumbnail.
        null
    }

    private fun renderOrThrow(channel: FileChannel, maxEdge: Int): RawThumbnail? {
        require(maxEdge > 0) { "maxEdge must be positive" }
        val index = OcrawStreamReader(channel, scratchBytes = OcrawFrameHeader.BYTES).indexFrames(maxFrames = LEADING_FRAMES)
        if (index.frames.isEmpty()) return null
        val colour = RawStaticColour.from(index.metadata)
        val orientation = index.orientationDegrees
        OcrawFrameSource(channel, index, slotCount = 1).use { source ->
            val slot = source.acquire() ?: return null
            for (frame in 0 until minOf(FRAMES_TRIED, index.frames.size)) {
                val read = source.read(frame, slot) as? OcrawFrameRead.Frame ?: continue
                val params = RawDevelop.params(colour, read.metadata, read.neutral?.let { RawDevelop.parseNumbers(it, 3) })
                return develop(read, slot.buffer, params, orientation, maxEdge)
            }
        }
        return null
    }

    private fun develop(read: OcrawFrameRead.Frame, buffer: ByteBuffer, params: RawDevelopParams, orientation: Int,
        maxEdge: Int): RawThumbnail? {
        val header = read.header
        val step = stepFor(header.width, header.height, maxEdge)
        val (width, height) = RawDevelop.halfSize(header.width, header.height, step)
        if (width <= 0 || height <= 0) return null
        val rgb = RawDevelop.developHalf(buffer, header, params, step)
        return orient(rgb, width, height, orientation)
    }

    /** The smallest [RawDevelop.developHalf] step whose picture of a [width]×[height] frame fits [maxEdge]. */
    internal fun stepFor(width: Int, height: Int, maxEdge: Int): Int {
        val longSide = max(width / 2, height / 2)
        return max(1, (longSide + maxEdge - 1) / maxEdge)
    }

    /**
     * Interleaved sRGB [rgb] of a stored [width]×[height] picture as upright ARGB, turned clockwise by
     * [orientationDegrees] as [RawDevelop.orientationMatrix] turns the review's frame.
     */
    internal fun orient(rgb: FloatArray, width: Int, height: Int, orientationDegrees: Int): RawThumbnail {
        val shown = RawDevelop.displaySize(width, height, orientationDegrees)
        val shownWidth = shown[0]
        val shownHeight = shown[1]
        val argb = IntArray(shownWidth * shownHeight)
        for (y in 0 until shownHeight) for (x in 0 until shownWidth) {
            // The stored pixel shown at (x, y): see RawDevelop.orientationMatrix.
            val sx: Int
            val sy: Int
            when (orientationDegrees) {
                90 -> { sx = y; sy = height - 1 - x }
                180 -> { sx = width - 1 - x; sy = height - 1 - y }
                270 -> { sx = width - 1 - y; sy = x }
                else -> { sx = x; sy = y }
            }
            val at = (sy * width + sx) * 3
            argb[y * shownWidth + x] = (0xff shl 24) or (channel(rgb[at]) shl 16) or (channel(rgb[at + 1]) shl 8) or channel(rgb[at + 2])
        }
        return RawThumbnail(shownWidth, shownHeight, argb)
    }

    private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()
}
