/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exploratory RAW-video feasibility probe: storage ceiling with a zero-copy writer, and every public
 * RAW10 size at several frame rates (including above the advertised minimum frame duration).
 * Results go to logcat under [TAG] as one JSON object per case; it asserts nothing.
 */
@RunWith(AndroidJUnit4::class)
class RawVideoProbeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = requireNotNull(context.getExternalFilesDir(null))
    private val ring = File(dir, "occ-raw-probe-ring.bin")

    @After fun cleanup() {
        ring.delete()
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }

    @Test
    fun storageCeiling() {
        val ringBytes = ringBytes()
        for (syncEveryMb in listOf(0, 256)) {
            RandomAccessFile(ring, "rw").use { file ->
                file.setLength(ringBytes)
                val channel = file.channel
                val block = ByteBuffer.allocateDirect(8 shl 20)
                while (block.hasRemaining()) block.put((block.position() * 31).toByte())
                val perSecond = mutableListOf<Long>()
                var position = 0L
                var sinceSync = 0L
                var windowBytes = 0L
                val start = SystemClock.elapsedRealtime()
                var windowStart = start
                while (SystemClock.elapsedRealtime() - start < STORAGE_SECONDS * 1000L) {
                    block.clear()
                    if (position + block.capacity() > ringBytes) position = 0L
                    while (block.hasRemaining()) position += channel.write(block, position)
                    windowBytes += block.capacity()
                    sinceSync += block.capacity()
                    if (syncEveryMb > 0 && sinceSync >= syncEveryMb.toLong() shl 20) { channel.force(false); sinceSync = 0 }
                    val now = SystemClock.elapsedRealtime()
                    if (now - windowStart >= 1000L) {
                        perSecond += windowBytes * 1000L / (now - windowStart)
                        windowBytes = 0; windowStart = now
                    }
                }
                channel.force(false)
                emit(JSONObject().put("case", "storage").put("syncEveryMb", syncEveryMb).put("ringMb", ringBytes shr 20)
                    .put("mbps", stats(perSecond)))
            }
            ring.delete()
            SystemClock.sleep(5_000)
        }
    }

    @Test
    fun rawMatrix() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val manager = context.getSystemService(CameraManager::class.java)
        RandomAccessFile(ring, "rw").use { it.setLength(ringBytes()) }
        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val sizes = map.getOutputSizes(ImageFormat.RAW10)?.toList().orEmpty()
            val aeRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
            emit(JSONObject().put("case", "camera").put("id", id)
                .put("facing", characteristics.get(CameraCharacteristics.LENS_FACING))
                .put("raw10", JSONArray(sizes.map { "${it.width}x${it.height}@${fpsOf(map.getOutputMinFrameDuration(ImageFormat.RAW10, it))}" }))
                .put("aeRanges", JSONArray(aeRanges.map { it.toString() })))
            for (size in sizes) {
                for (fps in FPS_TARGETS) {
                    for (crop in if (fps == 30 && size.width > 1920) listOf(null, Size(1920, 1080)) else listOf(null)) {
                        val result = runCatching { probe(manager, id, characteristics, size, fps, crop) }
                            .getOrElse { JSONObject().put("error", "${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}") }
                        emit(result.put("case", "raw").put("id", id).put("size", "${size.width}x${size.height}")
                            .put("targetFps", fps).put("crop", crop?.let { "${it.width}x${it.height}" } ?: JSONObject.NULL))
                        SystemClock.sleep(1_500)
                    }
                }
            }
        }
    }

    private fun probe(manager: CameraManager, id: String, characteristics: CameraCharacteristics, size: Size, fps: Int, crop: Size?, withPreview: Boolean = false): JSONObject {
        val thread = HandlerThread("occ-raw-probe").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW10, READER_IMAGES)
        val queue = LinkedBlockingQueue<Image>()
        val delivered = AtomicInteger()
        val readerDrops = AtomicInteger()
        val captureFailures = AtomicInteger()
        val completed = AtomicInteger()
        val written = AtomicLong()
        val timestamps = LongArray(4096)
        val timestampCount = AtomicInteger()
        val perSecondWrite = mutableListOf<Long>()
        var maxBacklog = 0
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var error: String? = null
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val ringBytes = ring.length()
        val cropBuffer = crop?.let { ByteBuffer.allocateDirect(it.width * 5 / 4 * it.height) }
        // The production graph: a GPU viewfinder texture and a small YUV analysis stream beside RAW.
        val previewTexture = if (withPreview) android.graphics.SurfaceTexture(0).apply { setDefaultBufferSize(1920, 1080) } else null
        val previewSurface = previewTexture?.let { android.view.Surface(it) }
        val previewFrames = AtomicInteger()
        previewTexture?.setOnFrameAvailableListener({ previewFrames.incrementAndGet() }, handler)
        val analysis = if (withPreview) ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3) else null
        val analysisFrames = AtomicInteger()
        analysis?.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close(); analysisFrames.incrementAndGet() }, handler)

        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireNextImage() }.getOrNull()
            if (image == null) { readerDrops.incrementAndGet(); return@setOnImageAvailableListener }
            if (queue.size >= READER_IMAGES - 2) { image.close(); readerDrops.incrementAndGet(); return@setOnImageAvailableListener }
            delivered.incrementAndGet()
            val index = timestampCount.getAndIncrement()
            if (index < timestamps.size) timestamps[index] = image.timestamp
            queue.put(image)
            maxBacklog = maxOf(maxBacklog, queue.size)
        }, handler)

        val writer = Thread {
            RandomAccessFile(ring, "rw").use { file ->
                val channel: FileChannel = file.channel
                var position = 0L
                var sinceSync = 0L
                var windowBytes = 0L
                var windowStart = SystemClock.elapsedRealtime()
                while (!stop.get() || queue.isNotEmpty()) {
                    val image = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    image.use {
                        val plane = it.planes[0]
                        val buffer = if (crop == null || cropBuffer == null) plane.buffer else {
                            cropBuffer.clear()
                            val src = plane.buffer
                            val rowBytes = crop.width * 5 / 4
                            val x0 = (size.width - crop.width) / 2 / 4 * 5
                            val y0 = (size.height - crop.height) / 2 and 1.inv()
                            for (row in 0 until crop.height) {
                                val offset = (y0 + row) * plane.rowStride + x0
                                src.limit(offset + rowBytes).position(offset)
                                cropBuffer.put(src)
                                src.limit(src.capacity())
                            }
                            cropBuffer.flip(); cropBuffer
                        }
                        val bytes = buffer.remaining()
                        if (position + bytes > ringBytes) position = 0L
                        while (buffer.hasRemaining()) position += channel.write(buffer, position)
                        written.addAndGet(bytes.toLong())
                        windowBytes += bytes; sinceSync += bytes
                        if (sinceSync >= 256L shl 20) { channel.force(false); sinceSync = 0 }
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - windowStart >= 1000L) {
                        perSecondWrite += windowBytes * 1000L / (now - windowStart); windowBytes = 0; windowStart = now
                    }
                }
                channel.force(false)
            }
        }

        try {
            val opened = CountDownLatch(1)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { camera = device; opened.countDown() }
                override fun onDisconnected(device: CameraDevice) { error = "disconnected"; device.close(); opened.countDown() }
                override fun onError(device: CameraDevice, code: Int) { error = "open-error-$code"; device.close(); opened.countDown() }
            }, handler)
            opened.await(10, TimeUnit.SECONDS)
            error?.let { return JSONObject().put("error", it) }
            val device = camera ?: return JSONObject().put("error", "open-timeout")
            val configured = CountDownLatch(1)
            device.createCaptureSession(listOfNotNull(reader.surface, previewSurface, analysis?.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { error = "configure-failed"; configured.countDown() }
            }, handler)
            configured.await(10, TimeUnit.SECONDS)
            error?.let { return JSONObject().put("error", it) }
            val aeRange = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.upper == fps }?.maxByOrNull { it.lower }
            val manual = aeRange == null
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                previewSurface?.let(::addTarget)
                analysis?.surface?.let(::addTarget)
                if (manual) {
                    // No AE range for this rate: drive the sensor directly with a short exposure.
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, minOf(4_000_000L, 1_000_000_000L / fps / 2))
                    set(CaptureRequest.SENSOR_SENSITIVITY, 800)
                } else {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeRange)
                }
            }.build()
            writer.start()
            val start = SystemClock.elapsedRealtime()
            session!!.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult) { completed.incrementAndGet() }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) { captureFailures.incrementAndGet() }
            }, handler)
            SystemClock.sleep(PROBE_SECONDS * 1000L)
            session!!.stopRepeating()
            val elapsed = (SystemClock.elapsedRealtime() - start) / 1000.0
            stop.set(true)
            writer.join(15_000)
            val count = minOf(timestampCount.get(), timestamps.size)
            val intervals = (1 until count).map { (timestamps[it] - timestamps[it - 1]) / 1_000_000.0 }.sorted()
            val frameBytes = if (delivered.get() > 0) written.get() / maxOf(1, delivered.get() - queue.size) else 0
            return JSONObject()
                .put("mode", if (manual) "manual" else "ae ${aeRange}")
                .put("completedFps", completed.get() / elapsed)
                .put("deliveredFps", delivered.get() / elapsed)
                .put("sensorIntervalMs", JSONObject()
                    .put("median", intervals.getOrNull(intervals.size / 2))
                    .put("p99", intervals.getOrNull((intervals.size * 99) / 100)))
                .put("readerDrops", readerDrops.get())
                .put("captureFailures", captureFailures.get())
                .put("maxBacklog", maxBacklog)
                .put("frameBytes", frameBytes)
                .put("writeMBps", stats(perSecondWrite))
                .put("previewFps", previewSurface?.let { previewFrames.get() / elapsed })
                .put("analysisFps", analysis?.let { analysisFrames.get() / elapsed })
        } finally {
            stop.set(true)
            runCatching { session?.close() }
            runCatching { camera?.close() }
            if (writer.isAlive) writer.join(5_000)
            while (true) queue.poll()?.close() ?: break
            runCatching { reader.close() }
            runCatching { analysis?.close() }
            runCatching { previewSurface?.release(); previewTexture?.release() }
            thread.quitSafely()
        }
    }

    /** The same sequential writer through a pending MediaStore item, the path a published take uses (FUSE). */
    @Test
    fun storageMediaStore() {
        val resolver = context.contentResolver
        val cases = listOf(
            Triple("movies", android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), "video/mp4") to "Movies/OpenCineCam",
            Triple("documents", android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), "application/octet-stream") to "Documents/OpenCineCam",
        )
        for ((case, path) in cases) {
            val (name, collection, mime) = case
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "occ-raw-probe-$name.bin")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(collection, values) }.getOrElse {
                emit(JSONObject().put("case", "mediastore").put("target", name).put("error", "${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}")); null
            } ?: continue
            try {
                val ringBytes = ringBytes()
                resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                    java.io.FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                        val block = ByteBuffer.allocateDirect(8 shl 20)
                        val perSecond = mutableListOf<Long>()
                        var position = 0L; var sinceSync = 0L; var windowBytes = 0L
                        val start = SystemClock.elapsedRealtime(); var windowStart = start
                        while (SystemClock.elapsedRealtime() - start < STORAGE_SECONDS * 1000L) {
                            block.clear()
                            if (position + block.capacity() > ringBytes) position = 0L
                            while (block.hasRemaining()) position += channel.write(block, position)
                            windowBytes += block.capacity(); sinceSync += block.capacity()
                            if (sinceSync >= 256L shl 20) { channel.force(false); sinceSync = 0 }
                            val now = SystemClock.elapsedRealtime()
                            if (now - windowStart >= 1000L) { perSecond += windowBytes * 1000L / (now - windowStart); windowBytes = 0; windowStart = now }
                        }
                        emit(JSONObject().put("case", "mediastore").put("target", name).put("mbps", stats(perSecond)))
                    }
                }
            } finally {
                resolver.delete(uri, null, null)
            }
            SystemClock.sleep(5_000)
        }
    }

    /**
     * Lossless compressibility of real RAW10 frames: Deflate level 1 on the packed bytes, and a
     * Bayer-aware block bit-packer (same-colour horizontal deltas, 16-sample blocks sized by their
     * largest residual) with the Shannon bound of those residuals. Times are single-threaded Kotlin.
     */
    @Test
    fun rawCompressibility() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first()
        val characteristics = manager.getCameraCharacteristics(id)
        val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.RAW10).maxByOrNull { it.width * it.height }!!
        val thread = HandlerThread("occ-raw-compress").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW10, 4)
        val frames = LinkedBlockingQueue<Pair<ByteArray, Int>>()
        var skip = 0
        reader.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.use { image ->
                // Let AE settle before keeping frames.
                if (skip++ >= 30 && frames.size < 3) {
                    val plane = image.planes[0]
                    val bytes = ByteArray(plane.buffer.remaining()); plane.buffer.get(bytes)
                    frames.put(bytes to plane.rowStride)
                }
            }
        }, handler)
        var camera: CameraDevice? = null
        val opened = CountDownLatch(1)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) { camera = device; opened.countDown() }
            override fun onDisconnected(device: CameraDevice) { device.close(); opened.countDown() }
            override fun onError(device: CameraDevice, code: Int) { device.close(); opened.countDown() }
        }, handler)
        opened.await(10, TimeUnit.SECONDS)
        val device = camera!!
        var session: CameraCaptureSession? = null
        val configured = CountDownLatch(1)
        device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
            override fun onConfigureFailed(s: CameraCaptureSession) { configured.countDown() }
        }, handler)
        configured.await(10, TimeUnit.SECONDS)
        session!!.setRepeatingRequest(device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(reader.surface) }.build(), null, handler)
        val kept = (0 until 3).mapNotNull { frames.poll(10, TimeUnit.SECONDS) }
        session!!.close(); device.close(); reader.close(); thread.quitSafely()
        kept.forEachIndexed { index, (bytes, rowStride) ->
            val packed = size.width * 5 / 4 * size.height
            var t = SystemClock.elapsedRealtimeNanos()
            val deflater = java.util.zip.Deflater(1)
            deflater.setInput(bytes); deflater.finish()
            val out = ByteArray(1 shl 20); var deflated = 0L
            while (!deflater.finished()) deflated += deflater.deflate(out)
            deflater.end()
            val deflateMs = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
            t = SystemClock.elapsedRealtimeNanos()
            val histogram = LongArray(2048)
            var blockBits = 0L
            var samples = 0L
            val row = IntArray(size.width)
            val residual = IntArray(16)
            for (y in 0 until size.height) {
                var x = 0; var o = y * rowStride
                while (x + 3 < size.width) {
                    val low = bytes[o + 4].toInt() and 0xff
                    row[x] = ((bytes[o].toInt() and 0xff) shl 2) or (low and 3)
                    row[x + 1] = ((bytes[o + 1].toInt() and 0xff) shl 2) or ((low shr 2) and 3)
                    row[x + 2] = ((bytes[o + 2].toInt() and 0xff) shl 2) or ((low shr 4) and 3)
                    row[x + 3] = ((bytes[o + 3].toInt() and 0xff) shl 2) or ((low shr 6) and 3)
                    x += 4; o += 5
                }
                var b = 0
                for (i in 0 until size.width) {
                    val r = if (i >= 2) row[i] - row[i - 2] else row[i]
                    histogram[(r + 1024).coerceIn(0, 2047)]++
                    residual[b++] = r
                    if (b == 16 || i == size.width - 1) {
                        var maxAbs = 0
                        for (k in 0 until b) maxAbs = maxOf(maxAbs, kotlin.math.abs(residual[k]))
                        val bits = if (maxAbs == 0) 0 else 32 - Integer.numberOfLeadingZeros(maxAbs) + 1
                        blockBits += 4 + bits.toLong() * b
                        b = 0
                    }
                    samples++
                }
            }
            val packMs = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
            val entropy = histogram.filter { it > 0 }.sumOf { val p = it.toDouble() / samples; -p * kotlin.math.log2(p) }
            emit(JSONObject().put("case", "compress").put("frame", index).put("size", "${size.width}x${size.height}")
                .put("packedBytes", packed)
                .put("deflate1Ratio", packed.toDouble() / deflated).put("deflate1Ms", deflateMs)
                .put("blockPackRatio", packed * 8.0 / blockBits).put("blockPackMs", packMs)
                .put("residualEntropyBits", entropy).put("shannonRatio", 10.0 / entropy))
        }
    }

    @Test
    fun rawWithProductionGraph() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val manager = context.getSystemService(CameraManager::class.java)
        RandomAccessFile(ring, "rw").use { it.setLength(ringBytes()) }
        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW10)?.maxByOrNull { it.width * it.height } ?: continue
            for (fps in listOf(30, 60)) {
                val result = runCatching { probe(manager, id, characteristics, size, fps, null, withPreview = true) }
                    .getOrElse { JSONObject().put("error", "${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}") }
                emit(result.put("case", "graph").put("id", id).put("size", "${size.width}x${size.height}").put("targetFps", fps))
                SystemClock.sleep(1_500)
            }
        }
    }

    private fun ringBytes(): Long {
        val free = StatFs(dir.path).availableBytes
        return minOf(4L shl 30, free / 4) and (8L shl 20).dec().inv()
    }

    private fun fpsOf(minDurationNs: Long) = if (minDurationNs > 0) Math.round(1e9 / minDurationNs) else 0

    private fun stats(values: List<Long>): JSONObject {
        val sorted = values.sorted()
        fun mb(v: Long?) = v?.let { it / 1_000_000.0 }
        return JSONObject().put("n", sorted.size)
            .put("p01", mb(sorted.getOrNull(((sorted.size - 1) * 0.01).toInt())))
            .put("median", mb(sorted.getOrNull(sorted.size / 2)))
            .put("min", mb(sorted.firstOrNull())).put("max", mb(sorted.lastOrNull()))
    }

    private fun emit(json: JSONObject) = Log.i(TAG, json.toString())

    private companion object {
        const val TAG = "OCC_RAW_PROBE"
        const val STORAGE_SECONDS = 30
        const val PROBE_SECONDS = 12
        const val READER_IMAGES = 10
        val FPS_TARGETS = listOf(30, 24, 15, 60, 120)
    }
}
