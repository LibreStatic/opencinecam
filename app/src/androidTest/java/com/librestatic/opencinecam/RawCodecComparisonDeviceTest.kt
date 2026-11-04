/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.media.ocraw.Crc32c
import com.librestatic.opencinecam.media.raw.NativeRawCodec
import com.librestatic.opencinecam.media.raw.NativeRawCodec.Codec
import com.librestatic.opencinecam.media.raw.NativeRawCodec.Kernel
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OCC-PLAN-071 phase 2: which lossless codec to ship for RAW10 video, on device
 * evidence. Captures real full-size RAW10 frames from the first back camera in two
 * exposures (auto, and a noisy manual one at high ISO), then measures every codec
 * configuration in `libocc_rawcodec` (ratio, median/p95 ms per frame, CPU per frame,
 * bit-exact round trip) and runs the two best at a fixed 30 fps cadence (and the
 * best at 60 fps) while sampling thermal headroom, thermal status and battery
 * temperature. [lj92DecodeTiming] times the playback-side LJ92 decode. Each
 * measurement is one `result=` JSON line under [TAG].
 *
 * Instrumentation args: `codecSustainSeconds` (default 120; 0 skips the sustained
 * runs), `codecRepeats` (default 20), `codecSustainExposure` (`noisy` or `auto`,
 * default `noisy`). Defaults keep the whole class under about ten minutes.
 *
 * ```
 * adb shell am instrument -w -e class com.librestatic.opencinecam.RawCodecComparisonDeviceTest \
 *   -e codecSustainSeconds 120 com.librestatic.opencinecam.debug.test/com.librestatic.opencinecam.EnglishLocaleTestRunner
 * adb logcat -s OCC_RAW_CODEC
 * ```
 */
@RunWith(AndroidJUnit4::class)
class RawCodecComparisonDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val sustainSeconds = args.getString("codecSustainSeconds")?.toIntOrNull() ?: 120
    private val repeats = (args.getString("codecRepeats")?.toIntOrNull() ?: 20).coerceAtLeast(5)
    private val sustainExposure = args.getString("codecSustainExposure") ?: "noisy"

    @After fun cleanup() {
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }

    /** memcpy and CRC32C throughput on this device, for scale. */
    @Test
    fun referenceThroughput() {
        assertTrue("libocc_rawcodec must load on the device", NativeRawCodec.available)
        val bytes = 16 shl 20
        val src = ByteBuffer.allocateDirect(bytes)
        val random = java.util.Random(1)
        while (src.hasRemaining()) src.put(random.nextInt().toByte())
        src.clear()
        val dst = ByteBuffer.allocateDirect(bytes)
        emit(JSONObject().put("case", "memcpy").put("mbps", throughput(bytes) {
            src.clear(); dst.clear(); dst.put(src)
        }))
        val heap = ByteArray(bytes).also { src.clear(); src.get(it); src.clear() }
        val paths = listOfNotNull(
            "table" to Crc32c.newAccumulator(preferPlatform = false),
            Crc32c.newPlatformAccumulator()?.let { "platform" to it },
            Crc32c.newNativeAccumulator()?.let { "native" to it },
        )
        val reference = Crc32c.newAccumulator(preferPlatform = false).apply { update(src) }.value()
        for ((name, accumulator) in paths) {
            accumulator.reset(); accumulator.update(src)
            val direct = throughput(bytes) { accumulator.reset(); accumulator.update(src) }
            accumulator.reset(); accumulator.update(heap)
            val heapMatch = accumulator.value() == reference
            val heapMbps = throughput(bytes) { accumulator.reset(); accumulator.update(heap) }
            accumulator.reset(); accumulator.update(src)
            emit(JSONObject().put("case", "crc32c").put("path", name)
                .put("directMbps", direct).put("heapMbps", heapMbps)
                .put("match", accumulator.value() == reference && heapMatch)
                .put("nativeHardware", if (name == "native") Crc32c.nativeHardware else JSONObject.NULL))
        }
        emit(JSONObject().put("case", "library").put("neon", NativeRawCodec.neonAvailable)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull()).put("cores", Runtime.getRuntime().availableProcessors())
            .put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else JSONObject.NULL))
    }

    @Test
    fun codecComparison() {
        assertTrue("libocc_rawcodec must load on the device", NativeRawCodec.available)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val captured = captureFrames()
        val results = mutableListOf<Measurement>()
        for ((exposure, frames) in captured.frames) {
            for (variant in VARIANTS) {
                results += measureVariant(captured.size, exposure, frames, variant)
            }
        }
        if (sustainSeconds <= 0) return
        val sustainFrames = captured.frames[sustainExposure] ?: captured.frames.values.first()
        val pool = results.filter { it.exposure == sustainExposure && it.exact }
        val budget30 = 1000.0 / 30 * BUDGET_MARGIN
        val budget60 = 1000.0 / 60 * BUDGET_MARGIN
        // Best per codec at 30 fps: the fewest threads whose p95 fits, else the fastest.
        val best30 = Codec.entries.mapNotNull { codec ->
            val candidates = pool.filter { it.variant.codec == codec && it.variant.kernel != Kernel.SCALAR }
            candidates.filter { it.p95Ms <= budget30 }.minWithOrNull(compareBy<Measurement> { it.threads }.thenByDescending { it.ratio })
                ?: candidates.minByOrNull { it.p95Ms }
        }
        emit(JSONObject().put("case", "sustainChoice").put("fps", 30).put("budgetMs", budget30)
            .put("chosen", JSONArray(best30.map { it.label })))
        for (choice in best30) sustain(captured.size, sustainFrames, choice, 30)
        val fits60 = pool.filter { it.p95Ms <= budget60 && it.variant.kernel != Kernel.SCALAR }
        val best60 = fits60.groupBy { it.variant }.mapNotNull { (_, list) -> list.minByOrNull { it.threads } }
            .maxWithOrNull(compareBy<Measurement> { it.ratio }.thenByDescending { it.threads })
            ?: pool.filter { it.variant.kernel != Kernel.SCALAR }.minByOrNull { it.p95Ms }
        if (best60 != null) {
            emit(JSONObject().put("case", "sustainChoice").put("fps", 60).put("budgetMs", budget60).put("chosen", best60.label))
            sustain(captured.size, sustainFrames, best60, 60)
        }
    }

    /**
     * OCC-PLAN-071 §7: LJ92 decode time of a full-size frame through the playback
     * decoder ([NativeRawCodec.Decoder]), per thread count, one `lj92Decode` line each.
     */
    @Test
    fun lj92DecodeTiming() {
        assertTrue("libocc_rawcodec must load on the device", NativeRawCodec.available)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val captured = captureFrames()
        val size = captured.size
        for ((exposure, frames) in captured.frames) {
            val rawBytes = frames.first().capacity()
            val encoded = NativeRawCodec.newEncoder(Codec.LJ92, threads = 4, strips = STRIPS).use { encoder ->
                frames.map { frame ->
                    val dst = ByteBuffer.allocateDirect(encoder.maxEncodedSize(size.width, size.height).toInt())
                    val n = encoder.encode(frame, size.width, size.height, dst)
                    check(n > 0) { "LJ92 encode did not fit" }
                    dst.limit(n)
                    dst
                }
            }
            val decoded = ByteBuffer.allocateDirect(rawBytes)
            for (threads in THREADS) {
                NativeRawCodec.newDecoder(threads).use { decoder ->
                    var exact = true
                    encoded.forEachIndexed { f, src ->
                        decoded.clear()
                        exact = exact && decoder.decode(Codec.LJ92, src, size.width, size.height, decoded)
                        frames[f].clear()
                        exact = exact && decoded == frames[f]
                    }
                    repeat(2) { decoded.clear(); decoder.decode(Codec.LJ92, encoded[it % encoded.size], size.width, size.height, decoded) }
                    val times = DoubleArray(repeats)
                    val cpuStart = Process.getElapsedCpuTime()
                    for (r in 0 until repeats) {
                        decoded.clear()
                        val start = System.nanoTime()
                        decoder.decode(Codec.LJ92, encoded[r % encoded.size], size.width, size.height, decoded)
                        times[r] = (System.nanoTime() - start) / 1e6
                    }
                    val cpuMs = (Process.getElapsedCpuTime() - cpuStart).toDouble()
                    times.sort()
                    val median = times[times.size / 2]
                    val p95 = times[minOf(times.size - 1, (times.size * 95 + 99) / 100 - 1)]
                    emit(JSONObject().put("case", "lj92Decode").put("exposure", exposure).put("threads", threads)
                        .put("strips", STRIPS).put("size", "${size.width}x${size.height}").put("exact", exact)
                        .put("ratio", encoded.size.toLong() * rawBytes / encoded.sumOf { it.limit().toLong() }.toDouble())
                        .put("medianMs", median).put("p95Ms", p95).put("minMs", times.first()).put("maxMs", times.last())
                        .put("maxFps", 1000.0 / median).put("cpuMsPerFrame", cpuMs / repeats)
                        .put("repeats", repeats).put("thermal", thermal()))
                }
            }
        }
    }

    private data class Variant(
        val name: String,
        val codec: Codec,
        val predictor: Int = 1,
        val optimal: Boolean = true,
        val kernel: Kernel = Kernel.AUTO,
    )

    private data class Measurement(
        val exposure: String,
        val variant: Variant,
        val threads: Int,
        val ratio: Double,
        val medianMs: Double,
        val p95Ms: Double,
        val exact: Boolean,
    ) {
        val label get() = "${variant.name}/t$threads"
    }

    private class Captured(val size: Size, val frames: LinkedHashMap<String, List<ByteBuffer>>)

    private fun measureVariant(size: Size, exposure: String, frames: List<ByteBuffer>, variant: Variant): List<Measurement> {
        val rawBytes = frames.first().capacity()
        val dst = ByteBuffer.allocateDirect(rawBytes * 3 / 2)
        val decoded = ByteBuffer.allocateDirect(rawBytes)
        val referenceCrc = IntArray(frames.size)
        val referenceSize = IntArray(frames.size)
        val out = mutableListOf<Measurement>()
        for ((index, threads) in THREADS.withIndex()) {
            NativeRawCodec.newEncoder(variant.codec, threads, STRIPS, variant.predictor, variant.optimal, variant.kernel).use { encoder ->
                // Bytes never depend on the thread count: decode once, then compare CRCs.
                var exact = true
                var encodedTotal = 0L
                val perFrameRatio = JSONArray()
                frames.forEachIndexed { f, frame ->
                    dst.clear()
                    val n = encoder.encode(frame, size.width, size.height, dst)
                    encodedTotal += if (n > 0) n else rawBytes
                    perFrameRatio.put(if (n > 0) rawBytes.toDouble() / n else JSONObject.NULL)
                    if (n <= 0) { exact = false; return@forEachIndexed }
                    dst.limit(n)
                    val crc = requireNotNull(Crc32c.newNativeAccumulator()).apply { update(dst) }.value()
                    if (index == 0) {
                        decoded.clear()
                        exact = exact && encoder.decode(dst, size.width, size.height, decoded)
                        decoded.clear(); frame.clear()
                        exact = exact && decoded == frame
                        referenceCrc[f] = crc; referenceSize[f] = n
                        if (variant.codec == Codec.BLOCK_PACK && variant.kernel == Kernel.SCALAR && NativeRawCodec.neonAvailable) {
                            exact = exact && kernelsAgree(size, frame, n, crc)
                        }
                    } else {
                        exact = exact && crc == referenceCrc[f] && n == referenceSize[f]
                    }
                }
                repeat(2) { dst.clear(); encoder.encode(frames[it % frames.size], size.width, size.height, dst) }
                val times = DoubleArray(repeats)
                val cpuStart = Process.getElapsedCpuTime()
                val wallStart = SystemClock.elapsedRealtimeNanos()
                for (r in 0 until repeats) {
                    dst.clear()
                    val start = System.nanoTime()
                    encoder.encode(frames[r % frames.size], size.width, size.height, dst)
                    times[r] = (System.nanoTime() - start) / 1e6
                }
                val wallMs = (SystemClock.elapsedRealtimeNanos() - wallStart) / 1e6
                val cpuMs = (Process.getElapsedCpuTime() - cpuStart).toDouble()
                times.sort()
                val median = times[times.size / 2]
                val p95 = times[minOf(times.size - 1, (times.size * 95 + 99) / 100 - 1)]
                val ratio = frames.size.toLong() * rawBytes / encodedTotal.toDouble()
                val m = Measurement(exposure, variant, threads, ratio, median, p95, exact)
                out += m
                emit(JSONObject().put("case", "codec").put("exposure", exposure).put("variant", variant.name)
                    .put("codec", variant.codec.name).put("predictor", variant.predictor).put("optimalHuffman", variant.optimal)
                    .put("kernel", variant.kernel.name).put("threads", threads).put("strips", STRIPS)
                    .put("size", "${size.width}x${size.height}").put("exact", exact)
                    .put("ratio", ratio).put("frameRatios", perFrameRatio)
                    .put("medianMs", median).put("p95Ms", p95).put("minMs", times.first()).put("maxMs", times.last())
                    .put("maxFps", 1000.0 / median).put("p95Fps", 1000.0 / p95)
                    .put("cpuMsPerFrame", cpuMs / repeats).put("coresBusy", cpuMs / wallMs)
                    .put("coresAt30", cpuMs / repeats * 30 / 1000).put("coresAt60", cpuMs / repeats * 60 / 1000)
                    .put("repeats", repeats).put("thermal", thermal()))
            }
        }
        return out
    }

    /** NEON and scalar block-pack emit the same bytes for this frame. */
    private fun kernelsAgree(size: Size, frame: ByteBuffer, scalarSize: Int, scalarCrc: Int): Boolean {
        NativeRawCodec.newEncoder(Codec.BLOCK_PACK, 1, STRIPS, kernel = Kernel.NEON).use { neon ->
            val out = ByteBuffer.allocateDirect(frame.capacity() * 3 / 2)
            frame.clear()
            val n = neon.encode(frame, size.width, size.height, out)
            out.limit(maxOf(n, 0))
            val crc = requireNotNull(Crc32c.newNativeAccumulator()).apply { update(out) }.value()
            val same = n == scalarSize && crc == scalarCrc
            emit(JSONObject().put("case", "kernelMatch").put("codec", "BLOCK_PACK").put("same", same))
            return same
        }
    }

    /** Encodes at a fixed cadence for [sustainSeconds], reporting every 10 s. */
    private fun sustain(size: Size, frames: List<ByteBuffer>, choice: Measurement, fps: Int) {
        val v = choice.variant
        val dst = ByteBuffer.allocateDirect(frames.first().capacity() * 3 / 2)
        val periodNs = 1_000_000_000L / fps
        NativeRawCodec.newEncoder(v.codec, choice.threads, STRIPS, v.predictor, v.optimal, v.kernel).use { encoder ->
            val start = SystemClock.elapsedRealtimeNanos()
            var scheduled = start
            var frame = 0
            var window = 0
            var windowStart = start
            var windowCpu = Process.getElapsedCpuTime()
            val windowTimes = ArrayList<Double>()
            var windowMisses = 0
            var totalMisses = 0
            var totalFrames = 0
            var encodedBytes = 0L
            while (SystemClock.elapsedRealtimeNanos() - start < sustainSeconds * 1_000_000_000L) {
                dst.clear()
                val t0 = SystemClock.elapsedRealtimeNanos()
                val n = encoder.encode(frames[frame++ % frames.size], size.width, size.height, dst)
                val t1 = SystemClock.elapsedRealtimeNanos()
                encodedBytes += if (n > 0) n else frames.first().capacity()
                windowTimes += (t1 - t0) / 1e6
                totalFrames++
                // A miss: the frame was not done before the next one was due.
                if (t1 > scheduled + periodNs) { windowMisses++; totalMisses++ }
                scheduled = maxOf(scheduled + periodNs, t1)
                val wait = scheduled - SystemClock.elapsedRealtimeNanos()
                if (wait > 0) LockSupport.parkNanos(wait)
                val now = SystemClock.elapsedRealtimeNanos()
                if (now - windowStart >= 10_000_000_000L) {
                    val cpu = Process.getElapsedCpuTime()
                    val sorted = windowTimes.sorted()
                    emit(JSONObject().put("case", "sustain").put("fps", fps).put("config", choice.label)
                        .put("exposure", choice.exposure).put("window", window++)
                        .put("t", (now - start) / 1e9).put("frames", sorted.size)
                        .put("meanMs", sorted.average()).put("p95Ms", sorted[minOf(sorted.size - 1, sorted.size * 95 / 100)])
                        .put("maxMs", sorted.last()).put("misses", windowMisses)
                        .put("coresBusy", (cpu - windowCpu) / ((now - windowStart) / 1e6))
                        .put("thermal", thermal()))
                    windowTimes.clear(); windowMisses = 0; windowStart = now; windowCpu = cpu
                }
            }
            emit(JSONObject().put("case", "sustainSummary").put("fps", fps).put("config", choice.label)
                .put("seconds", sustainSeconds).put("frames", totalFrames).put("misses", totalMisses)
                .put("ratio", totalFrames.toLong() * frames.first().capacity() / encodedBytes.toDouble())
                .put("thermal", thermal()))
        }
        SystemClock.sleep(5_000)
    }

    private fun thermal(): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return JSONObject()
            .put("headroom10", if (Build.VERSION.SDK_INT >= 30) power.getThermalHeadroom(10).toDouble().takeIf { !it.isNaN() } ?: JSONObject.NULL else JSONObject.NULL)
            .put("status", power.currentThermalStatus)
            .put("batteryC", temperature?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 } ?: JSONObject.NULL)
    }

    /** Four tight RAW10 frames per exposure from the first back camera at full size. */
    private fun captureFrames(): Captured {
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val characteristics = manager.getCameraCharacteristics(id)
        val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.RAW10).maxByOrNull { it.width * it.height }!!
        val thread = HandlerThread("occ-raw-codec").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW10, 4)
        val frames = LinkedBlockingQueue<ByteBuffer>()
        val skip = java.util.concurrent.atomic.AtomicInteger(Int.MAX_VALUE)
        val wanted = java.util.concurrent.atomic.AtomicInteger(0)
        reader.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.use { image ->
                if (skip.getAndDecrement() > 0) return@use
                if (wanted.getAndDecrement() > 0) frames.put(copyTight(image, size))
            }
        }, handler)
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            val opened = CountDownLatch(1)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { camera = device; opened.countDown() }
                override fun onDisconnected(device: CameraDevice) { device.close(); opened.countDown() }
                override fun onError(device: CameraDevice, code: Int) { device.close(); opened.countDown() }
            }, handler)
            opened.await(10, TimeUnit.SECONDS)
            val device = checkNotNull(camera) { "camera $id did not open" }
            val configured = CountDownLatch(1)
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { configured.countDown() }
            }, handler)
            configured.await(10, TimeUnit.SECONDS)
            val s = checkNotNull(session) { "RAW10 session did not configure" }
            val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val maxAnalog = characteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
            val iso = (maxAnalog ?: NOISY_ISO).coerceAtMost(NOISY_ISO).let { iso ->
                sensitivityRange?.let { iso.coerceIn(it.lower, it.upper) } ?: iso
            }
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val exposureNs = exposureRange?.let { NOISY_EXPOSURE_NS.coerceIn(it.lower, it.upper) } ?: NOISY_EXPOSURE_NS
            val out = LinkedHashMap<String, List<ByteBuffer>>()
            for (exposure in listOf("auto", "noisy")) {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(reader.surface)
                    if (exposure == "noisy") {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                        set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / 30)
                    }
                }.build()
                frames.clear()
                wanted.set(FRAMES)
                skip.set(SETTLE_FRAMES)
                s.setRepeatingRequest(request, null, handler)
                val kept = (0 until FRAMES).mapNotNull { frames.poll(15, TimeUnit.SECONDS) }
                s.stopRepeating()
                skip.set(Int.MAX_VALUE)
                check(kept.size == FRAMES) { "only ${kept.size} $exposure frames arrived" }
                out[exposure] = kept
                emit(JSONObject().put("case", "frames").put("exposure", exposure).put("camera", id)
                    .put("size", "${size.width}x${size.height}").put("count", kept.size)
                    .put("iso", if (exposure == "noisy") iso else JSONObject.NULL)
                    .put("exposureNs", if (exposure == "noisy") exposureNs else JSONObject.NULL)
                    .put("meanCode", kept.map { meanCode(it) }.average()))
            }
            return Captured(size, out)
        } finally {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { reader.close() }
            thread.quitSafely()
        }
    }

    private fun copyTight(image: Image, size: Size): ByteBuffer {
        val plane = image.planes[0]
        val src = plane.buffer
        val rowBytes = size.width * 5 / 4
        val out = ByteBuffer.allocateDirect(rowBytes * size.height)
        for (row in 0 until size.height) {
            val offset = row * plane.rowStride
            src.limit(offset + rowBytes).position(offset)
            out.put(src)
            src.limit(src.capacity())
        }
        out.clear()
        return out
    }

    /** Mean of the 8 high bits of every sample, a quick brightness check. */
    private fun meanCode(frame: ByteBuffer): Double {
        var sum = 0L
        var count = 0L
        var i = 0
        val limit = frame.capacity()
        while (i + 4 < limit) {
            sum += (frame.get(i).toInt() and 0xff) + (frame.get(i + 2).toInt() and 0xff)
            count += 2
            i += 5 * 64
        }
        return sum.toDouble() / count * 4
    }

    private inline fun throughput(bytes: Int, block: () -> Unit): Double {
        repeat(2) { block() }
        val rounds = 10
        val start = System.nanoTime()
        repeat(rounds) { block() }
        return rounds.toLong() * bytes / 1e6 / ((System.nanoTime() - start) / 1e9)
    }

    private fun emit(json: JSONObject) = Log.i(TAG, "result=$json")

    private companion object {
        const val TAG = "OCC_RAW_CODEC"
        const val STRIPS = 8
        const val FRAMES = 4
        const val SETTLE_FRAMES = 30
        const val NOISY_ISO = 3200
        const val NOISY_EXPOSURE_NS = 1_000_000_000L / 60
        /** A configuration counts as fitting when its p95 stays under 80 % of the frame period. */
        const val BUDGET_MARGIN = 0.8
        val THREADS = listOf(1, 2, 4, 6)
        val VARIANTS = listOf(
            Variant("lj92-p1-optimal", Codec.LJ92, predictor = 1, optimal = true),
            Variant("lj92-p1-default", Codec.LJ92, predictor = 1, optimal = false),
            Variant("lj92-p6-optimal", Codec.LJ92, predictor = 6, optimal = true),
            Variant("blockpack-neon", Codec.BLOCK_PACK, kernel = Kernel.NEON),
            Variant("blockpack-scalar", Codec.BLOCK_PACK, kernel = Kernel.SCALAR),
        )
    }
}
