/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.librestatic.opencinecam.ProxySettings
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Independent extractor evidence. Encoder callbacks alone never authorize publication. */
internal data class ProxyTrack(
    val mime: String, val timestampsUs: List<Long>, val durationUs: Long,
    val packetDigest: String, val codecData: List<String>, val sampleRate: Int, val channels: Int,
)
internal data class ProxyMediaProbe(val video: ProxyTrack, val audio: ProxyTrack?, val geometry: VideoDisplayGeometry,
    val visibleRaster: VideoDisplayGeometry = geometry)

internal suspend fun probeProxyMedia(context: Context, uri: Uri): ProxyMediaProbe {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
        val videoIndex = formats.indices.single { formats[it].getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val audioIndices = formats.indices.filter { formats[it].getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        require(audioIndices.size <= 1 && formats.size == 1 + audioIndices.size) { "Proxy requires one video and at most one audio track; extra tracks need an explicit mapping" }
        val f = formats[videoIndex]
        fun int(key: String, default: Int) = if (f.containsKey(key)) f.getInteger(key) else default
        val width = f.getInteger(MediaFormat.KEY_WIDTH); val height = f.getInteger(MediaFormat.KEY_HEIGHT)
        require(f.containsKey("sar-width") == f.containsKey("sar-height"))
        val geometry = videoDisplayGeometry(width, height,
            int("crop-left", 0), int("crop-top", 0), int("crop-right", width - 1), int("crop-bottom", height - 1),
            int("sar-width", 1), int("sar-height", 1), int(MediaFormat.KEY_ROTATION, 0),
            if (!f.containsKey("sar-width") && f.containsKey("display-width")) f.getInteger("display-width") else null,
            if (!f.containsKey("sar-height") && f.containsKey("display-height")) f.getInteger("display-height") else null)
        suspend fun track(index: Int): ProxyTrack {
            val format = formats[index]
            extractor.selectTrack(index); extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val times = mutableListOf<Long>(); val digest = MessageDigest.getInstance("SHA-256")
            var buffer = ByteBuffer.allocate(64 * 1024)
            while (extractor.sampleTime >= 0) {
                currentCoroutineContext().ensureActive()
                require(times.size < VideoFrameTimeline.MAX_FRAMES) { "Proxy exceeds sample verification bound" }
                val size = extractor.sampleSize
                require(size in 1..(64L * 1024 * 1024)) { "Invalid proxy sample size" }
                require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted proxy input" }
                if (buffer.capacity() < size) buffer = ByteBuffer.allocate(size.toInt())
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0); check(read.toLong() == size)
                times += extractor.sampleTime
                digest.update(ByteBuffer.allocate(4).putInt(read).array())
                buffer.position(0); buffer.limit(read); digest.update(buffer)
                if (!extractor.advance()) break
            }
            extractor.unselectTrack(index)
            require(times.isNotEmpty()) { "Empty proxy track" }
            val csd = (0..2).mapNotNull { n -> format.getByteBuffer("csd-$n")?.duplicate()?.let { data ->
                MessageDigest.getInstance("SHA-256").apply { update(data) }.digest().proxyHex()
            } }
            return ProxyTrack(requireNotNull(format.getString(MediaFormat.KEY_MIME)), times,
                format.getLong(MediaFormat.KEY_DURATION), digest.digest().proxyHex(), csd,
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0,
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0)
        }
        val visibleWidth = int("crop-right", width - 1) - int("crop-left", 0) + 1
        val visibleHeight = int("crop-bottom", height - 1) - int("crop-top", 0) + 1
        val raster = if (int(MediaFormat.KEY_ROTATION, 0) % 180 == 0) VideoDisplayGeometry(visibleWidth, visibleHeight)
            else VideoDisplayGeometry(visibleHeight, visibleWidth)
        return ProxyMediaProbe(track(videoIndex), audioIndices.singleOrNull()?.let { track(it) }, geometry, raster)
    } finally { extractor.release() }
}

/** Even output raster, no upscale. FIT retains framing when rounding introduces subpixel bands. */
internal fun proxyDimensions(source: VideoDisplayGeometry, settings: ProxySettings,
    visibleRaster: VideoDisplayGeometry = source): VideoDisplayGeometry {
    require(source.width >= 2 && source.height >= 2)
    require(visibleRaster.width >= 2 && visibleRaster.height >= 2)
    // DAR may contain unreduced SAR numerators; it is not a pixel-allocation size.
    val scale = minOf(visibleRaster.width.toDouble() / source.width, visibleRaster.height.toDouble() / source.height,
        settings.maxLongEdge.toDouble() / maxOf(source.width, source.height))
    fun even(value: Int) = ((value * scale).toInt() / 2 * 2).coerceAtLeast(2)
    return VideoDisplayGeometry(even(source.width), even(source.height))
}

internal fun verifyProxyCorrespondence(before: ProxyMediaProbe, after: ProxyMediaProbe, target: VideoDisplayGeometry) {
    verifyProxyVideoCorrespondence(before, after, target)
    require(before.audio == after.audio) { "Proxy changed audio packets, codec data, layout, duration or timestamps" }
}

internal fun verifyProxyVideoCorrespondence(before: ProxyMediaProbe, after: ProxyMediaProbe, target: VideoDisplayGeometry) {
    require(after.video.mime == "video/avc" && after.geometry == target) { "Proxy codec or presentation dimensions differ" }
    val sourceTimes = VideoFrameTimeline(before.video.timestampsUs).timestampsUs
    val proxyTimes = VideoFrameTimeline(after.video.timestampsUs).timestampsUs
    require(sourceTimes == proxyTimes) { "Proxy changed video presentation timestamps or frame count: original=$sourceTimes proxy=$proxyTimes" }
    // MP4 track duration includes the last frame duration. Do not replace it with last PTS.
    require(kotlin.math.abs(before.video.durationUs - after.video.durationUs) <= 1_000) { "Proxy changed video duration" }
}

internal fun ByteArray.proxyHex(): String = joinToString("") { "%02x".format(it) }
internal suspend fun proxyHash(context: Context, uri: Uri): String {
    val digest = MessageDigest.getInstance("SHA-256")
    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
        val bytes = ByteArray(128 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(bytes); if (read < 0) break
            digest.update(bytes, 0, read)
        }
    }
    return digest.digest().proxyHex()
}

/** Positional read-only descriptor access; never loads media payloads or changes the source. */
internal fun probeProxyVideoEndUs(context: Context, uri: Uri): Long {
    val descriptor = requireNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r"))
    return descriptor.use { asset ->
        asset.createInputStream().use { input ->
            val channel = input.channel
            val length = if (asset.declaredLength >= 0) asset.declaredLength else channel.size() - asset.startOffset
            require(length > 0)
            inspectProxyVideoEndUs(object : com.librestatic.opencinecam.camera.ProjectMp4File {
                override val size = length
                override fun read(offset: Long, length: Int): ByteArray {
                    val bytes = ByteArray(length); val buffer = ByteBuffer.wrap(bytes)
                    var position = Math.addExact(asset.startOffset, offset)
                    while (buffer.hasRemaining()) {
                        val count = channel.read(buffer, position); require(count > 0)
                        position = Math.addExact(position, count.toLong())
                    }
                    return bytes
                }
                override fun write(offset: Long, bytes: ByteArray): Unit = error("Read-only proxy source")
            })
        }
    }
}
