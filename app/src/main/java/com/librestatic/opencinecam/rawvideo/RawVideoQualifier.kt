/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.BatteryManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.librestatic.opencinecam.DeviceQuirksProvider
import com.librestatic.opencinecam.RawVideoConfigKeys
import com.librestatic.opencinecam.camera.DeviceQuirks
import com.librestatic.opencinecam.camera.QtiRawFrameRate
import com.librestatic.opencinecam.camera.RAW_VIDEO_MAX_IMAGES
import com.librestatic.opencinecam.camera.RawBenchmarkStatus
import com.librestatic.opencinecam.camera.RawLiveRun
import com.librestatic.opencinecam.camera.RawStorageCliff
import com.librestatic.opencinecam.camera.RawStorageRest
import com.librestatic.opencinecam.camera.RawStorageRestResult
import com.librestatic.opencinecam.camera.RawStorageSweep
import com.librestatic.opencinecam.camera.RawSustainedWrite
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.camera.RawThroughputQualification
import com.librestatic.opencinecam.camera.RawThroughputSample
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoQualificationCodec
import com.librestatic.opencinecam.camera.RawVideoQualificationRecord
import com.librestatic.opencinecam.camera.RawWriteEvidence
import com.librestatic.opencinecam.camera.rawVideoFrameRatePlan
import com.librestatic.opencinecam.core.model.QuirkEffect
import com.librestatic.opencinecam.media.ocraw.Crc32c
import com.librestatic.opencinecam.media.ocraw.OcrawChunkId
import com.librestatic.opencinecam.media.ocraw.OcrawCodec
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawHeader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamScan
import com.librestatic.opencinecam.media.ocraw.OcrawStreamStats
import com.librestatic.opencinecam.media.ocraw.OcrawStreamWriter
import com.librestatic.opencinecam.media.ocraw.OcrawTimeBase
import com.librestatic.opencinecam.media.raw.NativeRawCodec
import com.librestatic.opencinecam.media.raw.RawVideoRing
import com.librestatic.opencinecam.media.raw.SharedMemorySlots
import com.librestatic.opencinecam.media.raw.copyRaw10Rows
import com.librestatic.opencinecam.storage.CloseOnce
import com.librestatic.opencinecam.storage.DriveDescriptorHandle
import com.librestatic.opencinecam.storage.DriveDescriptors
import com.librestatic.opencinecam.storage.RawQualificationDriveScratch
import com.librestatic.opencinecam.storage.RawVideoQualificationRows
import com.librestatic.opencinecam.storage.volumeIdOfTree
import com.librestatic.opencinecam.storage.RawVideoQualificationStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import org.json.JSONArray
import org.json.JSONObject

/** What [RawVideoQualifier.run] returns. */
class RawVideoQualificationOutcome(
    val status: RawQualificationStatus,
    val reasonCode: String,
    /** The full report (schema [RawVideoQualifier.SCHEMA]): sweep, ring, encoder state and benchmark, every session's and verdict's JSON. */
    val report: JSONObject,
    /**
     * One record per judged configuration, PASS and FAIL, in the [RawVideoQualificationStore] format.
     * Empty when cancelled, and when the plan left a stage out ([RawQualificationPlan.complete]).
     */
    val records: List<RawVideoQualificationRecord>,
) {
    /** The configurations that passed. */
    val passedConfigs: List<RawVideoConfig>
        get() = records.filter { it.qualification.status == RawBenchmarkStatus.PASS }.map { it.config }
}

/**
 * OCC-PLAN-071 RAW video qualification, protocol 3 (decisions 9 and 10: rule v2, staged), run by
 * the app itself. It never promotes RAW video by itself: [run] returns the records, and stores them
 * only when given a store. The decisions are [RawQualificationStaging]'s; this runs the stages:
 *
 * 1. One [RawVideoRing] sized per decision 4 (off-heap [SharedMemorySlots]), allocated once and reused.
 * 2. A camera check of every uncompressed configuration, heaviest stream first, while the phone is still
 *    cool: the real camera for [RawQualificationTiming.cameraCheckSeconds], its RAW10 rows copied into
 *    the ring on the reader thread and released, no row. The checks judge the camera: its verdicts, its
 *    achieved rates and the evidence of every configuration come from them, measured before any heavy
 *    session warmed the phone.
 * 3. An encode benchmark per codec of the compressed configurations (block-pack, and LJ92 when a
 *    spec names it), once per [RawQualificationPlan.threadsFor] entry: the same session on the
 *    heaviest configuration whose camera check says the camera delivers, each frame encoded on the
 *    consumer thread with a fresh encoder ([RawFrameEncoders.native]) and discarded, no row. Every
 *    compressed configuration is judged from its codec's benchmark by pixel rate, its twin's camera and
 *    the storage at the compressed rate.
 * 4. A storage rest of [RawQualificationTiming.STORAGE_REST_MS] when a camera session ran, so the
 *    storage is measured rested.
 * 5. A storage sweep: at least 60 one-second windows of unthrottled `RAW_FRAME`-shaped gather writes
 *    to a pending row in `Documents/OpenCineCam/RAW` ([RawVideoQualificationRows]), forced every 256 MiB,
 *    then a sustained phase that writes on until the SLC write cache's cliff and the windows after it
 *    are measured ([RawStorageCliff]); rule v2 reads only the first 60. A sweep judged unrested
 *    (`storage-not-rested`) ends the run there, after the camera work: those rows stay in the report and
 *    nothing is stored. A USB drive's run goes on regardless (PLAN-072).
 * 6. Long disk runs of the uncompressed configurations, heaviest stream first: the real camera, its
 *    RAW10 rows copied into the ring and drained by one [OcrawStreamWriter] thread to its own row, then
 *    scanned back with [OcrawStreamReader]. Each is judged by [RawThroughputBenchmark.qualifyWithBuffer]
 *    plus the run's own checks (frame rate, heat, writer, scan), and, for comparison, by the old
 *    [RawThroughputBenchmark.qualifyWithoutBuffer]. The first pass stops the descent: it covers every
 *    lighter configuration's storage. A configuration whose camera check proved the camera cannot
 *    deliver it ([RawQualificationStaging.skipLongRun]) gets no long run, and neither does one the
 *    sweep alone rules out ([RawQualificationStaging.sweepRuledOut]: its median under the margin at
 *    the target rate, or too few windows). Its run would merge those reasons into its own and never
 *    pass, so the sweep decides it and the descent starts at the heaviest configuration the sweep
 *    allows; with none left, no long run happens. A run whose ring overflowed
 *    and whose only camera reason is `fps-not-reached` is weighed against its earlier camera check:
 *    when the camera held the rate from the RAM ring, the drop was the storage's
 *    ([RawQualificationStaging.storageLoadCodes]) and the run is risky rather than failed.
 * 7. A long run of the compressed twins whose twin's camera delivered, whose encoder keeps up and
 *    which the sweep allows at their benchmark's conservative ratio
 *    ([RawQualificationStaging.compressedLongRunOrder]). They run last, on the hottest phone, so one
 *    that fails for heat alone ([RawQualificationStaging.compressedHeatOnly]) is judged by the
 *    extrapolation instead ([RawQualificationStaging.compressedFinalVerdict]): its row stays in the
 *    results marked `supersededByVerdict`, its record is dropped, and the extrapolated verdict's
 *    record and row (with the info code `compressed-heat-fallback` and a `heatFallback` summary of
 *    the run) replace them.
 * 8. The verdicts: a configuration with a long run takes that run's; any other its camera check's,
 *    then the sweep's when the sweep ruled it out (risky when the margin alone failed), else ready
 *    when a passing long run covers its rate ([RawQualificationStaging.uncompressedVerdict]).
 *
 * With several cameras, steps 2 and 3 run for every camera, then one rest and one sweep, then steps 6
 * to 8 for each camera.
 *
 * The graph of every run is the app's RAW_VIDEO session (`Camera2PreviewEngine.configureGpuPreviewSession`):
 * the RAW10 reader at the largest RAW10 size beside one viewfinder stream, a [SurfaceTexture] at the
 * size [rawQualificationPreviewSize] picks, standing in for the GPU viewfinder's input. RAW_VIDEO adds
 * no YUV analysis stream (the scopes come from the GPU), so neither does this. The request keeps the
 * device test's form: TEMPLATE_RECORD, the fixed `[fps, fps]` AE range when advertised, otherwise
 * manual exposure at the frame duration. On a camera with the `QTI_RAW_FRAME_RATE_OVERRIDE` quirk, a
 * rate above the declared RAW limit follows [rawVideoFrameRatePlan] as the engine does: an AE range
 * within the limit plus the Qualcomm vendor keys at 1/fps.
 *
 * Between sessions it waits [RawQualificationTiming.COOL_DOWN_MS], but not between the sweep and the
 * first long run: nothing sits between them, so that run writes past the write cache the sweep filled,
 * as a long take would, and its verdict does not depend on how long the phone rested before. Nor does
 * the sweep wait for the heat after the storage rest. A session waits up to [THERMAL_WAIT_MS]
 * for the thermal status to drop below SEVERE and is skipped (`thermal-skip`) if it does not; reaching
 * SEVERE during one fails it (`thermal-limit`). Encode benchmarks and every long run (uncompressed or
 * a compressed twin's), the heaviest sessions, first cool down: they wait up to [COOL_WAIT_MS] for the
 * status to drop below MODERATE, then run anyway under the same rule (a heavier session before them
 * may have left the phone hot, and a run the heat spoils proves less). The first long run, right
 * after the sweep, does not: it keeps the old rule so nothing lets the write cache recover. Each session's JSON records the status it
 * started at (`thermalBefore`) and how long it waited (`thermalWaitMs`). A long run is skipped (`insufficient-space`) when the
 * volume lacks [RawQualificationTiming.neededBytes]. Every row is deleted after its step and on
 * cancellation or failure.
 *
 * [run] blocks for minutes and must not be called on the main thread; it runs once per instance. It
 * runs as the app, which must hold CAMERA, and opens the camera itself: the caller first releases any
 * camera the app holds. [cancel] may be called from any thread; [run] then returns
 * [RawQualificationStatus.CANCELLED] within about two seconds. [Listener] calls arrive on [run]'s thread.
 *
 * @param cameraIds the cameras to qualify; null takes the first back camera with RAW10. The app passes
 *   one; the device test may pass several (one sweep and one ring, sized for the largest, serve them all).
 */
class RawVideoQualifier(
    context: Context,
    private val cameraIds: List<String>? = null,
    private val plan: RawQualificationPlan = RawQualificationPlan.DEFAULT,
    private val timing: RawQualificationTiming = RawQualificationTiming(),
    private val listener: Listener = object : Listener {},
    /** The device-profile quirks; the lazy handle reads the bundled profiles on [run]'s thread, at the first run. */
    private val deviceQuirks: DeviceQuirks = DeviceQuirksProvider.lazy(context),
    /** Overrides [RawRingBudget.targetBytes] (device-test diagnostics only); still capped by the free memory. */
    private val ringTargetBytes: Long? = null,
    /**
     * The rest reference ([RawStorageRest]) when the caller has one; null reads it from the store [run]
     * gets, if any. The device test passes its own.
     */
    private val restReferenceBytesPerSecond: Long? = null,
    /** Runs on after a sweep judged unrested (testing only; a [driveTree] always does); the sweep's `rest` object still says so. */
    private val ignoreStorageRest: Boolean = false,
    /**
     * Writes the sweep and long-run rows as plain files in this directory instead of MediaStore rows
     * (device-test diagnostics only: app-private storage bypasses FUSE). Its records and rest
     * reference go under [DESTINATION_APP_PRIVATE].
     */
    private val privateDirectory: File? = null,
    /**
     * PLAN-072 Delivery 3: qualifies the USB drive whose SAF tree this is instead of the phone. The sweep and long-run
     * scratch files go to `<tree>/OpenCineCam/.occ-qualify/` ([RawQualificationDriveScratch]), the free space is the
     * drive's, and the records and rest reference go under `saf:<volume>`
     * ([RawVideoQualificationStore.destinationIdForDrive]). The heat heuristics are the phone's. The rest rule never ends a
     * drive's run: it is tuned on UFS, and a USB drive (a hard disk above all) writes unevenly when rested too, which no
     * wait cures. The long runs still judge each configuration against what the drive sustains.
     */
    private val driveTree: Uri? = null,
    /**
     * What the caller knows of the storage before the run ([RawQualificationStorageModel]): the long runs are planned
     * with it until the sweep measures the storage, so the first estimate matches the one the screen showed.
     */
    private val storageModel: RawQualificationStorageModel? = null,
) {
    /** Progress and per-step results, called on [run]'s thread. */
    interface Listener {
        fun onProgress(progress: RawQualificationProgress) = Unit

        /** One `kind: sweep` or `kind: live` JSON object, as soon as the step ends (skips included). */
        fun onResult(result: JSONObject) = Unit
    }

    private val context = context.applicationContext
    private val started = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val rows = mutableListOf<Uri>()
    private val restRuleIgnored = ignoreStorageRest || (driveTree != null && privateDirectory == null)
    private val driveScratch = driveTree?.takeIf { privateDirectory == null }?.let { RawQualificationDriveScratch(context.applicationContext.contentResolver, it) }
    private val destinationId = when {
        privateDirectory != null -> DESTINATION_APP_PRIVATE
        driveTree != null -> RawVideoQualificationStore.destinationIdForDrive(requireNotNull(volumeIdOfTree(driveTree)) { "Not a drive tree" })
        else -> DESTINATION_ID
    }

    init {
        // StatFs needs the directory to exist before the first free-space check.
        privateDirectory?.mkdirs()
    }
    private val slotMemory = SharedMemorySlots()

    /** The encoders of the benchmark sessions not yet closed. */
    private val benchEncoders = mutableListOf<RawFrameEncoder>()

    /** The hottest thermal status seen during the current session's sleeps. */
    @Volatile private var thermalPeak = 0

    /** The live run whose frame rate progress reports carry ([liveFps]); null outside a camera session. */
    @Volatile private var liveSession: LiveState? = null

    // Qualification thread only: [liveFps]'s last value, recomputed every [RawQualificationLiveRate.INTERVAL_MS].
    private var liveFpsOf: LiveState? = null
    private var liveFpsValue: Double? = null
    private var liveFpsAtMs = 0L

    /** The last writer or consumer thread; slot memory and the encoders are released only once it has ended. */
    private var lastWriter: Thread? = null
    private var meter = RawQualificationProgressMeter(SystemClock::elapsedRealtime)

    /** The storage model the long runs are planned with: [storageModel], then the sweep's. Qualification thread only. */
    private var plannedStorage: RawQualificationStorageModel? = storageModel

    val isCancelled: Boolean get() = cancelled.get()

    /** The drive under test left while a scratch file was open on it: the run was cancelled, and nothing is stored. */
    @Volatile var driveLost = false
        private set

    private fun cancelledOutcome(report: JSONObject, results: JSONArray): RawVideoQualificationOutcome {
        report.put("results", results)
            .put("status", RawQualificationStatus.CANCELLED.name)
            .put("reasonCode", REASON_CANCELLED)
            .put("safeMessage", "RAW video qualification was cancelled; nothing was stored.")
        return RawVideoQualificationOutcome(RawQualificationStatus.CANCELLED, REASON_CANCELLED, report, emptyList())
    }

    /**
     * ADR-0037: a scratch file on a drive is closed the moment the drive leaves (vold kills a process that still holds a
     * descriptor on it). The release cancels the run, so the write that then fails is not judged and nothing is stored.
     * A row on the phone registers nothing. The caller unregisters once it has closed the file itself.
     */
    internal fun registerDriveRow(uri: Uri, channel: FileChannel, descriptor: ParcelFileDescriptor): DriveDescriptorHandle {
        val closer = CloseOnce(channel, descriptor)
        return DriveDescriptors.registerDocument(uri, AutoCloseable {
            driveLost = true
            cancel()
            closer.close()
        })
    }

    /** Asks a running (or not yet started) qualification to stop; idempotent, any thread. */
    fun cancel() {
        cancelled.set(true)
    }

    /**
     * The estimate of this qualifier's plan on its first camera, or null when no camera has RAW10.
     * The duration is an upper bound (every long run of the descent, thermal waits excluded).
     */
    fun estimate(): RawQualificationEstimate? {
        val camera = runCatching { selectCameras(context.getSystemService(CameraManager::class.java)) }.getOrNull()?.firstOrNull() ?: return null
        return RawQualificationEstimates.of(plan, camera.id, camera.size.width, camera.size.height, NativeRawCodec.available, timing, storageModel)
    }

    /**
     * Runs the qualification and returns its outcome; never throws (an exception is an UNKNOWN outcome
     * with reason [REASON_EXCEPTION]). With a [store], the records are merged into it
     * ([RawVideoQualificationStore.upsertAll]) unless the run was cancelled, and a sweep judged rested
     * raises its rest reference ([RawStorageRest]). A sweep judged unrested ends the run as UNKNOWN with
     * [REASON_STORAGE_NOT_RESTED] and stores nothing, so the records in force stay.
     */
    fun run(store: RawVideoQualificationStore? = null): RawVideoQualificationOutcome {
        check(started.compareAndSet(false, true)) { "a RawVideoQualifier runs once" }
        val report = baseReport()
        val results = JSONArray()
        val records = mutableListOf<RawVideoQualificationRecord>()
        return try {
            qualify(report, results, records, store)
        } catch (_: QualificationCancelled) {
            cancelledOutcome(report, results)
        } catch (error: Throwable) {
            // A write that failed because the drive's descriptors were released is the drive leaving, not a result.
            if (driveLost) return cancelledOutcome(report, results)
            report.put("results", results)
                .put("status", RawQualificationStatus.UNKNOWN.name)
                .put("reasonCode", REASON_EXCEPTION)
                .put("safeMessage", "RAW qualification could not run: ${describe(error)}")
            RawVideoQualificationOutcome(RawQualificationStatus.UNKNOWN, REASON_EXCEPTION, report, emptyList())
        } finally {
            release()
            meter.complete()
            putTiming(report)
            progress(RawQualificationPhase.DONE)
        }
    }

    private fun qualify(
        report: JSONObject,
        results: JSONArray,
        records: MutableList<RawVideoQualificationRecord>,
        store: RawVideoQualificationStore?,
    ): RawVideoQualificationOutcome {
        checkCancelled()
        val manager = context.getSystemService(CameraManager::class.java)
        val cameras = selectCameras(manager)
        if (cameras.isEmpty()) {
            report.put("status", RawQualificationStatus.UNKNOWN.name).put("reasonCode", REASON_UNSUPPORTED)
                .put("safeMessage", "No public Camera2 RAW10 stream is advertised; RAW-video mode remains disabled.")
            return RawVideoQualificationOutcome(RawQualificationStatus.UNKNOWN, REASON_UNSUPPORTED, report, emptyList())
        }
        val codecAvailable = NativeRawCodec.available
        val stages = plan.effectiveStages
        report.put("liveSeconds", timing.liveSeconds)
            .put("sustainedLiveSeconds", timing.sustainedLiveSeconds)
            .put("sustainedBytesPerSecond", timing.sustainedBytesPerSecond)
            .put("warmupSeconds", RawQualificationTiming.WARMUP_SECONDS)
            .put("cameraCheckSeconds", timing.cameraCheckSeconds)
            .put("cameraCheckWarmupSeconds", timing.cameraCheckWarmupSeconds)
            .put("encodeBenchSeconds", timing.encodeBenchSeconds)
            .put("configs", JSONArray(plan.specs))
            .put("compressedTwins", plan.compressedTwins)
            .put("stages", JSONArray(RawQualificationStage.entries.filter { it in stages }.map { it.name.lowercase() }))
            .put("graph", GRAPH)
            .put("cameras", JSONArray(cameras.map { it.id }))
        RawVideoQualificationRows.purgeOrphans(context)
        driveScratch?.purgeOrphans()

        // Resolve every spec; one the camera cannot carry is reported and skipped.
        val runsByCamera = linkedMapOf<CameraInfo, List<RawQualificationRun>>()
        for (camera in cameras) {
            val cameraRuns = mutableListOf<RawQualificationRun>()
            for (spec in plan.specs) {
                val run = RawQualificationSpecs.parse(spec, camera.id, camera.size.width, camera.size.height)
                if (run == null) {
                    val skipped = JSONObject().put("kind", "live").put("cameraId", camera.id).put("spec", spec)
                        .put("status", "UNKNOWN").put("reasonCode", "config-invalid")
                    results.put(skipped); emit(skipped)
                } else cameraRuns += run
            }
            runsByCamera[camera] = cameraRuns
        }
        val allUncompressed = runsByCamera.values.flatMap { plan.uncompressedRuns(it) }
        val allBench = runsByCamera.values.sumOf { plan.benchSessions(it, codecAvailable) }
        val estimate = RawQualificationEstimates.of(allUncompressed, allBench, timing, stages)
        // The meter's plan is the estimate the screen showed: the long runs the free space has room for now, planned
        // with what is known of the storage. A long run left out runs unplanned if the space is there after all.
        val freeAtStart = runCatching { freeBytes() }.getOrDefault(Long.MAX_VALUE)
        val plannedLong = allUncompressed.filter { timing.neededBytes(it.config) <= freeAtStart }.map { it.config }.toMutableSet()
        val planned = RawQualificationEstimates.of(allUncompressed, allBench, timing, stages,
            allUncompressed.filter { it.config in plannedLong }, plannedStorage)
        report.put("estimate", JSONObject().put("durationMs", estimate.durationMs).put("freeBytesNeeded", estimate.freeBytesNeeded)
            .put("runCount", estimate.runCount).put("plannedMs", planned.durationMs))
        meter = RawQualificationProgressMeter(SystemClock::elapsedRealtime, planned.plannedMsByKind)

        // 1. The ring, allocated once, for the largest frame any session will carry.
        val largest = cameras.maxBy { it.size.width.toLong() * it.size.height }.size
        meter.startStep(RawQualificationStepKind.RING, RawQualificationTiming.RING_OVERHEAD_MS)
        progress(RawQualificationPhase.RING)
        val slotBytes = OcrawFrameHeader.raw10RowBytes(largest.width) * largest.height
        val ringPlan = allocateRing(slotBytes)
        finishStep()
        report.put("ring", ringPlan.json)
        Log.i(TAG, "ring=${ringPlan.json}")
        checkCancelled()

        val steps = StepCounter(estimate.runCount)
        var anyException = false
        var ringPoisoned = false
        val benchJson = JSONArray()
        /** The storage sweep, once it ran: the camera checks and the encode benchmarks come before it. */
        var sweep: RawStorageSweep? = null
        /** The next long run is the first after the sweep: no cool-down and no cool wait, so it writes past the cache the sweep filled. */
        var firstAfterSweep = false

        /** The record of the latest session, when it stored one. */
        var lastRecord: RawVideoQualificationRecord? = null
        /** Every judged long run's writer rate and whether it fell behind the storage, for [RawStorageCliff.withLongRunFallback]. */
        val writeEvidence = mutableListOf<RawWriteEvidence>()
        /** Each recorded configuration's storage rate and burst ratio, and the row carrying its record, for the final pass. */
        val storageRates = mutableMapOf<RawVideoConfig, Pair<Long, Double>>()
        val rowsByConfig = mutableMapOf<RawVideoConfig, JSONObject>()

        /** One camera session of [kind]; never throws but on cancellation. */
        fun session(
            camera: CameraInfo,
            run: RawQualificationRun,
            kind: SessionKind,
            bench: EncodeSession? = null,
            expectedFps: Double? = null,
        ): JSONObject {
            val afterSweep = kind == SessionKind.LONG && firstAfterSweep
            if (kind == SessionKind.LONG) firstAfterSweep = false
            // No cool-down before the first session, nor between the sweep and the first long run.
            if (steps.index > 0 && !afterSweep) coolDown(steps.index, steps.count)
            steps.index++
            val plannedMs = if (kind == SessionKind.LONG && !run.config.isCompressed && run.config !in plannedLong) 0L else kind.plannedMs(run.config)
            meter.startStep(kind.stepKind, plannedMs, run.spec)
            progress(kind.phase, index = steps.index, count = steps.count, spec = run.spec)
            val ring = ringPlan.ring
            val outcome = when {
                ring == null -> LiveOutcome(skipped(run, "ring-unavailable", kind), null)
                ringPoisoned -> LiveOutcome(skipped(run, "ring-poisoned", kind), null)
                (kind == SessionKind.ENCODE || run.config.isCompressed) && bench == null -> LiveOutcome(skipped(run, REASON_CODEC_UNAVAILABLE, kind), null)
                else -> try {
                    liveRun(manager, camera, run, ring, sweep, kind, bench, steps.index, steps.count, expectedFps,
                        coolFirst = steps.index > 1 && !afterSweep)
                } catch (cancel: QualificationCancelled) {
                    throw cancel
                } catch (error: Throwable) {
                    anyException = true
                    LiveOutcome(skipped(run, "live-run-exception", kind).put("error", describe(error)), null)
                }
            }
            // A step skipped before its work (space, heat, no ring) says nothing about how long its kind takes.
            finishStep(measured = outcome.json.has("drainMs"))
            val result = outcome.json
            if (result.optBoolean("ringPoisoned")) ringPoisoned = true
            lastRecord = outcome.record
            if (kind == SessionKind.LONG) {
                writeEvidenceOf(result)?.let(writeEvidence::add)
                outcome.record?.let { record ->
                    storageRates[record.config] = result.optLong("storageBytesPerSecond") to result.optDouble("burstRatio", 1.0)
                    rowsByConfig[record.config] = result
                }
            }
            outcome.record?.let { if (plan.complete) records += it }
            results.put(result)
            emit(result)
            checkCancelled()
            return result
        }

        val passes = runsByCamera.map { (camera, cameraRuns) ->
            CameraPass(camera, plan.uncompressedRuns(cameraRuns), plan.compressedRuns(cameraRuns, codecAvailable),
                plan.benchEncodings(cameraRuns, codecAvailable))
        }
        val cameraJudged = RawQualificationStage.CAMERA in stages

        // 2 and 3. Per camera, while the phone is cool: a camera check of every uncompressed configuration, heaviest
        // first, from the ring alone; then each codec's benchmark, judged from those checks.
        for (pass in passes) {
            val camera = pass.camera
            if (cameraJudged) {
                for (run in RawQualificationStaging.longRunOrder(pass.uncompressed)) {
                    val json = session(camera, run, SessionKind.CAMERA)
                    pass.noteAchieved(run.config, json)
                    pass.cameraChecks[run.config] = stageResult(json)
                    pass.checkJsons[run.config] = json
                    json.optJSONObject("evidence")?.let { pass.evidence[run.config] = it }
                }
            }

            // Each codec's benchmark, once per thread count, from the heaviest configuration the camera delivers
            // down, until one holds the camera under the encode load and keeps up with its own configuration.
            for (encoding in pass.benchEncodings) {
                val sessionsPer = plan.threadsFor(encoding).size
                val benches = linkedMapOf<RawVideoConfig, RawEncodeBenchFigures?>()
                val twinConfigs = pass.compressedRuns.filter { it.config.encoding == encoding }
                    .map { it.config.copy(encoding = RawVideoConfig.ENCODING_RAW10) }.toSet()
                val candidates = if (!cameraJudged) emptyList() else RawQualificationStaging.longRunOrder(pass.uncompressed)
                    .filter { it.config in twinConfigs && RawQualificationStaging.cameraEvidence(it.config, pass.longRuns, pass.cameraChecks)?.cameraDelivered == true }
                /** One benchmark configuration: a session per thread count; the verdict entry's figures go into [benches]. */
                fun benchConfig(benchRun: RawQualificationRun) {
                    val entries = mutableListOf<RawEncodeBenchFigures?>()
                    for (threads in plan.threadsFor(encoding)) {
                        val bench = encodeSession(encoding, threads, plan.encodeStrips, slotBytes)
                        val json = session(camera, benchRun, SessionKind.ENCODE, bench, pass.expectedFps(benchRun.config))
                        bench?.let { closeAfterConsumer(it.encoder) }
                        benchJson.put(json)
                        entries += benchFigures(json, benchRun.config, encoding, threads)
                        json.optJSONObject("evidence")?.let { pass.benchEvidence[Triple(encoding, benchRun.config, threads)] = it }
                    }
                    benches[benchRun.config] = RawQualificationStaging.verdictBench(entries, RawFrameEncoders.defaultThreads(encoding))
                }
                if (!cameraJudged) {
                    // No camera check ran (the stage is off): one benchmark on the heaviest.
                    RawQualificationStaging.longRunOrder(pass.uncompressed).firstOrNull()?.let(::benchConfig)
                    pass.judgesByEncoding[encoding] = benches.values.firstOrNull()?.let { only -> twinConfigs.associateWith { only } }.orEmpty()
                } else {
                    val candidateConfigs = candidates.map { it.config }
                    var next = RawQualificationStaging.nextEncodeBenchConfig(candidateConfigs, benches)
                    if (next == null) {
                        steps.count -= sessionsPer
                        meter.shrink(RawQualificationStepKind.ENCODE_BENCH, sessionsPer * timing.encodeBenchMs())
                        meter.shrink(RawQualificationStepKind.COOL_DOWN, sessionsPer * RawQualificationTiming.COOL_DOWN_MS)
                    }
                    while (next != null) {
                        if (benches.isNotEmpty()) {
                            // The plan counted one benchmark configuration per codec; the descent adds another.
                            steps.count += sessionsPer
                            meter.extend(RawQualificationStepKind.ENCODE_BENCH, sessionsPer * timing.encodeBenchMs())
                            meter.extend(RawQualificationStepKind.COOL_DOWN, sessionsPer * RawQualificationTiming.COOL_DOWN_MS)
                        }
                        benchConfig(candidates.first { it.config == next })
                        next = RawQualificationStaging.nextEncodeBenchConfig(candidateConfigs, benches)
                    }
                    pass.judgesByEncoding[encoding] = RawQualificationStaging.encodeJudges(candidateConfigs, benches)
                }
            }
        }

        // 4. The storage rest: after the camera work, the storage is measured rested.
        val sweepStage = RawQualificationStage.SWEEP in stages
        if (sweepStage && steps.index > 0) {
            meter.startStep(RawQualificationStepKind.STORAGE_REST, RawQualificationTiming.STORAGE_REST_MS)
            progress(RawQualificationPhase.STORAGE_REST, index = steps.index, count = steps.count)
            sleepCancellable(RawQualificationTiming.STORAGE_REST_MS, RawQualificationPhase.STORAGE_REST, steps.index, steps.count, null)
            finishStep()
        }

        // 5. Storage sweep, with the largest frame any run will write; right after the rest, with no thermal wait.
        if (sweepStage) {
            meter.startStep(RawQualificationStepKind.SWEEP, timing.sweepMs)
            progress(RawQualificationPhase.SWEEP, index = steps.index, count = steps.count)
            val reference = restReferenceBytesPerSecond ?: store?.let { runCatching { it.restReference(destinationId) }.getOrNull() }
            val sweepResult = storageSweep(largest, timing.sweepWindows, timing.sweepMaxExtraSeconds, reference, steps.index, steps.count)
            finishStep()
            // The long runs are planned again with the speed the sweep measured and the ring actually allocated.
            RawQualificationStorageModel.of(sweepResult.sweep.medianBytesPerSecond, ringPlan.ring?.capacityBytes)
                ?.takeIf { RawQualificationStage.LONG in stages }?.let { measured ->
                plannedStorage = measured
                val longMs = allUncompressed.filter { it.config in plannedLong }.sumOf { timing.runMs(it.config, measured) }
                meter.replan(RawQualificationStepKind.LONG_RUN, longMs)
                report.put("sweepStorageModel", storageModelJson(measured))
                Log.i(TAG, "long runs replanned: $longMs ms at ${measured.writeBytesPerSecond} B/s, ${meter.remainingMs()} ms left")
            }
            report.put("sweep", sweepResult.json)
            emit(JSONObject(sweepResult.json.toString()).put("kind", "sweep"))
            System.gc()
            val rest = sweepResult.rest
            if (rest != null && rest.rested && store != null) {
                rest.medianBytesPerSecond?.takeIf { it > 0 }?.let { median ->
                    runCatching { store.raiseRestReference(destinationId, median) }
                        .onFailure { Log.w(TAG, "rest reference not stored", it) }
                }
            }
            if (rest != null && !rest.rested && !restRuleIgnored) {
                // A cache still flushing makes every storage verdict wrong; the records in force stay untouched.
                // The camera checks and benchmarks already ran: their rows stay in the report, nothing is stored.
                Log.i(TAG, "storage not rested (${rest.rule}): median ${rest.medianBytesPerSecond} B/s, p01 ${rest.p01BytesPerSecond} B/s")
                report.put("results", results)
                    .put("status", RawQualificationStatus.UNKNOWN.name)
                    .put("reasonCode", REASON_STORAGE_NOT_RESTED)
                    .put("safeMessage", "The storage is still flushing recent writes; let the phone rest and qualify again. Nothing was stored.")
                return RawVideoQualificationOutcome(RawQualificationStatus.UNKNOWN, REASON_STORAGE_NOT_RESTED, report, emptyList())
            }
            sweep = sweepResult.sweep
            firstAfterSweep = true
            checkCancelled()
        }

        // 6 to 8. Per camera: the long-run descent, the compressed long runs, the verdicts.
        for (pass in passes) {
            val camera = pass.camera
            val longRuns = pass.longRuns
            val cameraChecks = pass.cameraChecks
            val evidence = pass.evidence
            val achieved = pass.achieved

            // Long disk runs, heaviest first, until one passes; one whose camera check proved the camera cannot deliver
            // it is left out (its check judges it), and so is one the sweep alone rules out: its run could never pass,
            // so the sweep decides its verdict and the descent starts at the heaviest configuration the sweep allows.
            if (RawQualificationStage.LONG in stages) {
                val fullOrder = RawQualificationStaging.longRunOrder(pass.uncompressed)
                // A run the camera check skips stays in the order, where the skip below counts it out; its check judges it.
                val ruledOut = sweep?.let { measured ->
                    fullOrder.filterNot { RawQualificationStaging.skipLongRun(cameraChecks[it.config]) }
                        .associate { it.config to RawQualificationStaging.sweepRuledOut(it.config, measured) }
                        .filterValues { it.isNotEmpty() }
                }.orEmpty()
                ruledOut.forEach { (config, reasons) -> pass.sweepDecided[config] = RawStageResult.fail(reasons) }
                val order = fullOrder.filter { it.config !in ruledOut }
                val sweepDropped = fullOrder.filter { it.config in ruledOut }
                if (sweepDropped.isNotEmpty()) {
                    steps.count -= sweepDropped.size
                    unplanLong(plannedLong, sweepDropped.map { it.config })
                    Log.i(TAG, "no long run, the sweep rules them out: " +
                        sweepDropped.joinToString { "${it.spec} ${pass.sweepDecided[it.config]?.reasonCodes}" })
                }
                for ((position, run) in order.withIndex()) {
                    val check = cameraChecks[run.config]
                    if (RawQualificationStaging.skipLongRun(check)) {
                        steps.count--
                        unplanLong(plannedLong, listOf(run.config))
                        continue
                    }
                    val json = session(camera, run, SessionKind.LONG)
                    // The cool camera check's rate wins over the long run's, which the heat and the storage may lower.
                    if (run.config !in achieved) pass.noteAchieved(run.config, json)
                    val longRecord = lastRecord
                    var result = stageResult(json)
                    val checkJson = pass.checkJsons[run.config]
                    if (check != null && checkJson != null && result.status == RawBenchmarkStatus.FAIL &&
                        RawQualificationStaging.needsStorageLoadCheck(result.reasonCodes)) {
                        // The ring overflowed and the rate fell: the camera's fault, or the storage's load? The camera check
                        // that ran before, from the RAM ring alone, says whether the camera holds the rate.
                        result = storageLoadReclassify(json, result, check, checkJson, longRecord, records, sweep)
                        // Emitted again so the row the UI and the logs read carries the final verdict.
                        emit(json)
                    }
                    // The run's own row and record keep the sustained rule; the descent and the cover read what it held.
                    result = RawQualificationStaging.withoutSustainedRate(result)
                    if (result.status == RawBenchmarkStatus.UNKNOWN) pass.longSkips[run.config] = result else {
                        longRuns[run.config] = result
                        if (run.config !in evidence) json.optJSONObject("evidence")?.let { evidence[run.config] = it }
                    }
                    if (!RawQualificationStaging.continueDescent(result)) {
                        val left = order.drop(position + 1).filterNot { RawQualificationStaging.skipLongRun(cameraChecks[it.config]) }
                        steps.count -= left.size
                        unplanLong(plannedLong, left.map { it.config })
                        break
                    }
                }
            }

            // Compressed twins whose twin's camera delivered and whose encoder keeps up: a long run of their own, on the
            // storage with the encoder working, judged like any long run. The rest are extrapolated from the benchmark.
            val compressedLong = mutableMapOf<RawVideoConfig, RawStageResult>()
            /** The compressed long runs the heat alone failed, summarized: their verdict is the extrapolation's. */
            val heatFallbacks = mutableMapOf<RawVideoConfig, JSONObject>()
            if (RawQualificationStage.LONG in stages) {
                val order = RawQualificationStaging.compressedLongRunOrder(
                    pass.compressedRuns.map { it.config },
                    twinCamera = { twin -> RawQualificationStaging.cameraEvidence(twin, longRuns, cameraChecks) },
                    // Like an uncompressed one, a twin the sweep rules out at its benchmark's ratio gets no long run.
                    sweepAllows = { config -> pass.sweepRuledOut(config, sweep).isEmpty() },
                    judge = { config -> pass.judgesByEncoding[config.encoding]?.get(config.copy(encoding = RawVideoConfig.ENCODING_RAW10)) },
                )
                if (order.isNotEmpty()) {
                    steps.count += order.size
                    meter.extend(RawQualificationStepKind.LONG_RUN, order.sumOf { timing.runMs(it, plannedStorage) })
                    meter.extend(RawQualificationStepKind.COOL_DOWN, order.size * RawQualificationTiming.COOL_DOWN_MS)
                }
                for (config in order) {
                    val run = pass.compressedRuns.first { it.config == config }
                    val twin = config.copy(encoding = RawVideoConfig.ENCODING_RAW10)
                    val bench = encodeSession(config.encoding, RawFrameEncoders.defaultThreads(config.encoding), plan.encodeStrips, slotBytes)
                    val json = session(camera, run, SessionKind.LONG, bench)
                    bench?.let { closeAfterConsumer(it.encoder) }
                    val longRecord = lastRecord
                    var result = stageResult(json)
                    val twinReduced = RawQualificationFrameRate.isReducedRate(achieved[twin], twin.fps)
                    val twinCheck = RawQualificationStaging.twinCameraCheck(RawQualificationStaging.cameraEvidence(twin, longRuns, cameraChecks), twinReduced)
                    if (twinCheck != null && result.status == RawBenchmarkStatus.FAIL && RawQualificationStaging.needsStorageLoadCheck(result.reasonCodes)) {
                        // The ring overflowed and the rate fell: the twin's camera evidence says whether the camera holds the rate.
                        val checkJson = JSONObject()
                            .put("evidence", evidence[twin] ?: JSONObject.NULL)
                            .put("achievedFps", achieved[twin] ?: JSONObject.NULL)
                        result = storageLoadReclassify(json, result, twinCheck, checkJson, longRecord, records, sweep,
                            json.optDouble("burstRatio", 1.0).takeIf { !it.isNaN() && it >= 1.0 } ?: 1.0)
                        emit(json)
                    }
                    if (RawQualificationStaging.compressedHeatOnly(result)) {
                        // It ran last, on the hottest phone: the heat alone proves nothing about the configuration, so its
                        // twin's camera and its codec's benchmark judge it (the verdict loop). Its row stays in the results,
                        // its record goes: the extrapolated verdict's replaces it.
                        records.removeAll { it.config == config }
                        rowsByConfig.remove(config)
                        storageRates.remove(config)
                        json.put("supersededByVerdict", true)
                        heatFallbacks[config] = JSONObject()
                            .put("reasonCode", RawQualificationStaging.REASON_COMPRESSED_HEAT_FALLBACK)
                            .put("longRunStatus", json.optString("status"))
                            .put("longRunReasonCodes", JSONArray(result.reasonCodes))
                            .put("achievedFps", json.opt("achievedFps") ?: JSONObject.NULL)
                            .put("thermalBefore", json.opt("thermalBefore") ?: JSONObject.NULL)
                            .put("thermalPeak", json.opt("thermalPeak") ?: JSONObject.NULL)
                    }
                    compressedLong[config] = result
                }
            }
            if (!plan.complete) continue

            // Verdicts, records and the rows the UI reads.
            val ring = ringPlan.ring ?: continue
            val measuredAt = System.currentTimeMillis()
            for (run in pass.uncompressed) {
                // A long run's record and row are already in, its camera check weighed where it mattered.
                if (run.config in longRuns) continue
                val verdict = RawQualificationStaging.uncompressedVerdict(run.config, longRuns, cameraChecks[run.config], pass.longSkips[run.config],
                    pass.sweepDecided[run.config]?.reasonCodes.orEmpty())
                val record = verdictRecord(run.config, ring, requireNotNull(sweep), verdict, 1.0, pass.specOf, evidence[run.config], measuredAt, achieved[run.config])
                record?.let(records::add)
                storageRates[run.config] = RawQualificationFrameRate.effectiveStreamBytesPerSecond(run.config, achieved[run.config]) to 1.0
                verdictRow(run, verdict, pass.specOf, timing.neededBytes(run.config), record).also { rowsByConfig[run.config] = it; results.put(it); emit(it) }
            }
            for (run in pass.compressedRuns) {
                // Its own long run's record and row are already in; a skipped one, or one the heat alone failed, falls
                // back to the extrapolation.
                if (RawQualificationStaging.compressedLongRunVerdict(compressedLong[run.config]) != null) continue
                val twin = run.config.copy(encoding = RawVideoConfig.ENCODING_RAW10)
                val camEvidence = RawQualificationStaging.cameraEvidence(twin, longRuns, cameraChecks)
                val bench = pass.judgesByEncoding[run.config.encoding]?.get(twin)
                // The configurations the sweep decided count as storage failures (never as covers) for an uncovered twin.
                val verdict = RawQualificationStaging.compressedFinalVerdict(compressedLong[run.config], twin, camEvidence, bench,
                    longRuns + pass.sweepDecided, pass.sweepRuledOut(run.config, sweep))
                val heatFallback = heatFallbacks[run.config]
                val proof = JSONObject()
                    .put("camera", evidence[twin] ?: JSONObject.NULL)
                    .put("bench", bench?.let { pass.benchEvidence[Triple(it.encoding, it.config, it.threads)] } ?: JSONObject.NULL)
                heatFallback?.let { proof.put("heatFallback", it) }
                val ratio = bench?.conservativeRatio ?: 1.0
                val record = verdictRecord(run.config, ring, requireNotNull(sweep), verdict, ratio, pass.specOf, proof, measuredAt, achieved[twin])
                record?.let(records::add)
                storageRates[run.config] = RawStorageCliff.compressedBytesPerSecond(
                    RawQualificationFrameRate.effectiveStreamBytesPerSecond(run.config, achieved[twin]), ratio) to ratio
                verdictRow(run, verdict, pass.specOf, 0L, record).also { row ->
                    heatFallback?.let {
                        row.put("infoCodes", JSONArray(listOf(RawQualificationStaging.REASON_COMPRESSED_HEAT_FALLBACK))).put("heatFallback", it)
                    }
                    rowsByConfig[run.config] = row; results.put(row); emit(row)
                }
            }
        }
        applyLongRunSustained(sweep?.sustained, writeEvidence, records, storageRates, rowsByConfig, report)
        report.put("results", results)
        val encodings = runsByCamera.values.flatMap { plan.benchEncodings(it, codecAvailable) }.distinct()
        report.put("encode", JSONObject()
            .put("codecAvailable", codecAvailable)
            .put("neon", NativeRawCodec.neonAvailable)
            .put("encoderReady", benchJson.length() > 0)
            .put("encodings", JSONArray(encodings))
            .put("threads", JSONObject().apply { encodings.forEach { put(it, JSONArray(plan.threadsFor(it))) } })
            .put("strips", plan.encodeStrips)
            .put("verdictThreads", JSONObject().apply { encodings.forEach { put(it, RawFrameEncoders.defaultThreads(it)) } })
            .put("lj92Predictor", RawFrameEncoders.LJ92_PREDICTOR)
            .put("cpuCores", Runtime.getRuntime().availableProcessors())
            .put("bench", benchJson))

        // A compressed long run the heat alone failed is superseded by its extrapolated verdict row.
        val statuses = (0 until results.length()).map { results.getJSONObject(it) }
            .filter { it.optString("kind") == "live" && !it.optBoolean("supersededByVerdict") }.map { it.optString("status") }
        val passedCount = statuses.count { it == "PASS" }
        val status = RawQualificationRecords.overallStatus(statuses)
        var reasonCode = if (anyException) REASON_EXCEPTION else if (passedCount > 0) "raw-video-qualified-configs" else "raw-video-no-qualified-config"
        if (store != null && records.isNotEmpty()) {
            checkCancelled()
            progress(RawQualificationPhase.SAVING)
            runCatching { store.upsertAll(records) }
                .onSuccess { report.put("storedRecords", records.size) }
                .onFailure { error -> reasonCode = REASON_STORE_FAILED; report.put("storeError", describe(error)) }
        }
        report.put("status", status.name)
            .put("reasonCode", reasonCode)
            .put("passedConfigs", passedCount)
            .put("records", JSONArray(records.map { JSONObject(RawVideoQualificationCodec.encodeRecord(it).toString()) }))
            .put("safeMessage", "RAW video qualification v3 (staged) completed; promotion stays separately gated.")
        return RawVideoQualificationOutcome(status, reasonCode, report, records.toList())
    }

    /** The step the ring shows: [index] of [count]; the count shrinks once the descent stops. */
    private class StepCounter(var count: Int) {
        var index = 0
    }

    /** Ends the meter's current step and logs where its time went. */
    private fun finishStep(measured: Boolean = true) {
        meter.finishStep(measured)?.let { step ->
            Log.i(TAG, "step ${step.kind.name.lowercase()}${step.spec?.let { " $it" }.orEmpty()} planned=${step.plannedMs} ms " +
                "actual=${step.actualMs} ms held=${step.heldMs} ms left=${meter.remainingMs()} ms")
        }
    }

    /** Long runs of [configs] the meter planned will not run: their planned time and cool-downs leave its plan. */
    private fun unplanLong(plannedLong: MutableSet<RawVideoConfig>, configs: List<RawVideoConfig>) {
        for (config in configs) {
            if (!plannedLong.remove(config)) continue
            meter.shrink(RawQualificationStepKind.LONG_RUN, timing.runMs(config, plannedStorage))
            meter.shrink(RawQualificationStepKind.COOL_DOWN, RawQualificationTiming.COOL_DOWN_MS)
        }
    }

    /** Where the run's time went: every step planned against actual, the total and the storage model it started with. */
    private fun putTiming(report: JSONObject) {
        report.put("elapsedMs", meter.elapsedMs())
            .put("storageModel", storageModel?.let(::storageModelJson) ?: JSONObject.NULL)
            .put("steps", JSONArray(meter.steps.map { step ->
                JSONObject().put("kind", step.kind.name.lowercase()).put("spec", step.spec ?: JSONObject.NULL)
                    .put("plannedMs", step.plannedMs).put("actualMs", step.actualMs).put("heldMs", step.heldMs)
            }))
    }

    private fun storageModelJson(model: RawQualificationStorageModel) = JSONObject()
        .put("writeBytesPerSecond", model.writeBytesPerSecond)
        .put("ringBytes", model.ringBytes ?: JSONObject.NULL)

    /** The kinds of camera session: a long disk run, a RAM-only camera check, an encode benchmark session. */
    private enum class SessionKind(val phase: RawQualificationPhase, val stage: String, val stepKind: RawQualificationStepKind) {
        LONG(RawQualificationPhase.RUN, "long", RawQualificationStepKind.LONG_RUN),
        CAMERA(RawQualificationPhase.CAMERA_CHECK, "camera", RawQualificationStepKind.CAMERA_CHECK),
        ENCODE(RawQualificationPhase.ENCODE_BENCH, "encode-bench", RawQualificationStepKind.ENCODE_BENCH),
    }

    private fun SessionKind.plannedMs(config: RawVideoConfig): Long = when (this) {
        SessionKind.LONG -> timing.runMs(config, plannedStorage)
        SessionKind.CAMERA -> timing.cameraCheckMs()
        SessionKind.ENCODE -> timing.encodeBenchMs()
    }

    private fun SessionKind.measureSeconds(config: RawVideoConfig): Int = when (this) {
        SessionKind.LONG -> timing.liveSeconds(config)
        SessionKind.CAMERA -> timing.cameraCheckSeconds
        SessionKind.ENCODE -> timing.encodeBenchSeconds
    }

    private fun SessionKind.warmupMs(): Long = when (this) {
        SessionKind.LONG -> RawQualificationTiming.WARMUP_SECONDS * 1_000L
        else -> timing.cameraCheckWarmupSeconds * 1_000L
    }

    /** One camera's runs and what its sessions found, kept from its camera checks to its verdicts. */
    private class CameraPass(
        val camera: CameraInfo,
        val uncompressed: List<RawQualificationRun>,
        val compressedRuns: List<RawQualificationRun>,
        val benchEncodings: List<String>,
    ) {
        val specOf: Map<RawVideoConfig, String> = uncompressed.associate { it.config to it.spec }
        val longRuns = linkedMapOf<RawVideoConfig, RawStageResult>()
        val longSkips = mutableMapOf<RawVideoConfig, RawStageResult>()
        /**
         * The uncompressed configurations the sweep ruled out ([RawQualificationStaging.sweepRuledOut]), with no long
         * run: a FAIL with the sweep's reasons. Never camera evidence nor a long run of their own; only the compressed
         * verdicts read them, as storage failures.
         */
        val sweepDecided = mutableMapOf<RawVideoConfig, RawStageResult>()
        val cameraChecks = mutableMapOf<RawVideoConfig, RawStageResult>()
        /** Each camera check's row, for a long run's storage-load reclassification. */
        val checkJsons = mutableMapOf<RawVideoConfig, JSONObject>()
        /** Each configuration's camera evidence: its camera check's, else its long run's. */
        val evidence = mutableMapOf<RawVideoConfig, JSONObject>()
        /** The rate each configuration's camera delivered, to a tenth: its RAM camera check when one ran, else its long run. */
        val achieved = mutableMapOf<RawVideoConfig, Double>()
        val benchEvidence = mutableMapOf<Triple<String, RawVideoConfig, Int>, JSONObject>()
        val judgesByEncoding = mutableMapOf<String, Map<RawVideoConfig, RawEncodeBenchFigures?>>()

        fun noteAchieved(config: RawVideoConfig, json: JSONObject) {
            if (json.optString("status") == RawBenchmarkStatus.UNKNOWN.name) return
            json.optDouble("achievedFps").takeIf { !it.isNaN() }?.let { achieved[config] = it }
        }

        /**
         * Why [sweep] rules the compressed [config] out at the conservative ratio of the benchmark judging it
         * ([RawQualificationStaging.sweepRuledOut]); empty with no sweep or no benchmark.
         */
        fun sweepRuledOut(config: RawVideoConfig, sweep: RawStorageSweep?): List<String> {
            val bench = judgesByEncoding[config.encoding]?.get(config.copy(encoding = RawVideoConfig.ENCODING_RAW10)) ?: return emptyList()
            return sweep?.let { RawQualificationStaging.sweepRuledOut(config, it, bench.conservativeRatio) }.orEmpty()
        }

        /** What an encode benchmark of [config] asks the camera to hold: its reduced rate when it has one, else the target. */
        fun expectedFps(config: RawVideoConfig): Double =
            achieved[config]?.takeIf { RawQualificationFrameRate.isReducedRate(it, config.fps) } ?: config.fps.toDouble()
    }

    /** A session row's verdict. */
    private fun stageResult(json: JSONObject): RawStageResult {
        val codes = json.optJSONArray("reasonCodes")?.let { array -> (0 until array.length()).map(array::getString) }
            ?: listOfNotNull(json.optString("reasonCode").takeIf(String::isNotEmpty))
        return RawStageResult.of(json.optString("status"), codes)
    }

    /**
     * A judged long run's [RawWriteEvidence] from its row, or null for a skip or a run that wrote
     * nothing: its writer rate, whether it fell behind, its peak thermal status and the rate it had to
     * store (`storageBytesPerSecond`, else its `streamBytesPerSecond`).
     */
    private fun writeEvidenceOf(json: JSONObject): RawWriteEvidence? {
        if (json.optString("status") == RawBenchmarkStatus.UNKNOWN.name || !json.has("writerBytesPerSecond")) return null
        val rate = json.optLong("writerBytesPerSecond").takeIf { it > 0 } ?: return null
        val thermalPeak = if (json.has("thermalPeak")) json.optInt("thermalPeak") else null
        val storage = json.optLong("storageBytesPerSecond").takeIf { it > 0 } ?: json.optLong("streamBytesPerSecond").takeIf { it > 0 }
        return RawWriteEvidence(json.optString("spec"), rate, json.optBoolean("fellBehind"), thermalPeak, storage)
    }

    /**
     * The final pass, once every long run (the compressed twins' included) is done: when the sweep
     * found no cliff, [RawStorageCliff.withLongRunFallback] takes the sustained rate from the runs
     * that fell behind without reaching SEVERE, never below what a later run kept up with, and every record is judged again against it
     * ([RawQualificationStaging.rejudgeWithLongRunSustained]); a changed record replaces its entry in
     * [records] and its row is updated and emitted again. The sweep's `sustained` object says where
     * the rate came from (`source`). Nothing changes with a real cliff or when no run fell behind.
     */
    private fun applyLongRunSustained(
        sweepSustained: RawSustainedWrite?,
        evidence: List<RawWriteEvidence>,
        records: MutableList<RawVideoQualificationRecord>,
        storageRates: Map<RawVideoConfig, Pair<Long, Double>>,
        rowsByConfig: Map<RawVideoConfig, JSONObject>,
        report: JSONObject,
    ) {
        val sustained = RawStorageCliff.withLongRunFallback(sweepSustained, evidence)
        report.optJSONObject("sweep")?.optJSONObject("sustained")?.let { json ->
            json.put("source", sustained?.source ?: RawStorageCliff.SOURCE_SWEEP)
                .put("longRuns", JSONArray(evidence.map {
                    JSONObject().put("spec", it.spec).put("writerBytesPerSecond", it.bytesPerSecond).put("fellBehind", it.fellBehind)
                        .put("thermalPeak", it.thermalPeak ?: JSONObject.NULL)
                        .put("storageBytesPerSecond", it.storageBytesPerSecond ?: JSONObject.NULL)
                }))
            if (sustained?.source == RawStorageCliff.SOURCE_LONG_RUNS) {
                json.put("derivedFrom", JSONArray(sustained.derivedFrom))
                    .put("derivedSustainedBytesPerSecond", sustained.sustainedBytesPerSecond)
                    .put("derivedCacheBytes", sustained.cacheBytes ?: JSONObject.NULL)
                    .put("derivedFloorBytesPerSecond", sustained.floorBytesPerSecond ?: JSONObject.NULL)
                    .put("floorFrom", sustained.floorFrom ?: JSONObject.NULL)
            }
        }
        if (sustained?.source != RawStorageCliff.SOURCE_LONG_RUNS) return
        Log.i(TAG, "sustained from long runs ${sustained.derivedFrom} (floor ${sustained.floorFrom}): ${sustained.sustainedBytesPerSecond} B/s")
        for (index in records.indices) {
            val record = records[index]
            val (rate, ratio) = storageRates[record.config] ?: continue
            val updated = RawQualificationStaging.rejudgeWithLongRunSustained(record, rate, ratio, sustained)
            if (updated == record) continue
            records[index] = updated
            val row = rowsByConfig[record.config] ?: continue
            val q = updated.qualification
            row.put("status", q.status.name)
                .put("reasonCode", q.reasonCodes.first())
                .put("reasonCodes", JSONArray(q.reasonCodes))
                .put("burstSeconds", updated.burstSeconds ?: JSONObject.NULL)
                .put("sustainedSource", RawStorageCliff.SOURCE_LONG_RUNS)
            if (row.has("record")) row.put("record", JSONObject(RawVideoQualificationCodec.encodeRecord(updated).toString()))
            emit(row)
        }
    }

    /**
     * A long run [json] whose [result] [RawQualificationStaging.needsStorageLoadCheck] said needed the
     * camera check [check] ([checkJson]): the check goes into the row as `storageLoadCheck`, and when it
     * clears the camera the row, its stored [longRecord] (replaced in [records]) and the returned
     * result take [RawQualificationStaging.storageLoadCodes], so the run is risky with its burst seconds.
     */
    private fun storageLoadReclassify(
        json: JSONObject,
        result: RawStageResult,
        check: RawStageResult,
        checkJson: JSONObject,
        longRecord: RawVideoQualificationRecord?,
        records: MutableList<RawVideoQualificationRecord>,
        sweep: RawStorageSweep?,
        ratio: Double = 1.0,
    ): RawStageResult {
        val reclassified = RawQualificationStaging.storageLoadResult(result, check)
        val changed = reclassified != result
        json.put("storageLoadCheck", JSONObject()
            .put("status", check.status.name)
            .put("reasonCodes", JSONArray(check.reasonCodes.ifEmpty { listOf(RawThroughputBenchmark.REASON_PASS) }))
            .put("evidence", checkJson.optJSONObject("evidence") ?: JSONObject.NULL)
            .put("reclassified", changed))
        if (!changed) return result
        json.put("reasonCode", reclassified.reasonCodes.first()).put("reasonCodes", JSONArray(reclassified.reasonCodes))
        val record = longRecord ?: return reclassified
        val q = RawQualificationStaging.storageLoadQualification(record.qualification, check) ?: return reclassified
        // The RAM check, free of the storage's load, says what rate the camera achieves.
        val achievedFps = checkJson.optDouble("achievedFps").takeIf { !it.isNaN() } ?: record.achievedFps
        val updated = RawQualificationRecords.withBurstSeconds(record.copy(qualification = q, achievedFps = achievedFps), sweep?.sustained, ratio)
        val index = records.indexOfFirst { it === record }
        if (index >= 0) records[index] = updated
        json.put("burstSeconds", updated.burstSeconds ?: JSONObject.NULL)
            .put("achievedFps", achievedFps ?: JSONObject.NULL)
        return reclassified
    }

    /** One benchmark session's figures, or null when it encoded nothing. */
    private fun benchFigures(json: JSONObject, config: RawVideoConfig, encoding: String, threads: Int): RawEncodeBenchFigures? {
        if (json.optString("kind") != SessionKind.ENCODE.stage) return null
        val quarters = json.optJSONArray("encodeQuarterMeanNs")?.let { array -> (0 until array.length()).map(array::getLong) }.orEmpty()
        val minRatio = json.optDouble("minFrameRatio").takeIf { !it.isNaN() }
        val reasons = json.optJSONArray("reasonCodes")?.let { array -> (0 until array.length()).map(array::getString) }.orEmpty()
            .filter { it == RawQualificationStaging.REASON_THERMAL_LIMIT }
        val cameraHeld = json.optBoolean("cameraHeld", true)
        return RawQualificationStaging.benchFigures(config, quarters, minRatio, threads, plan.encodeStrips, cameraHeld, reasons, encoding)
    }

    /**
     * The record of a configuration no long run of its own judged, or null for an UNKNOWN verdict:
     * rule v2 on the sweep at its own rate (a compressed twin at [ratio]) and the ring, with no live
     * run (see [RawQualificationStaging.extrapolatedQualification]), and a storage failure when its
     * stream exceeds the sustained rate past the write cache ([RawQualificationStaging.withSustainedRate]).
     */
    private fun verdictRecord(
        config: RawVideoConfig,
        ring: RawVideoRing,
        sweep: RawStorageSweep,
        verdict: RawStagedVerdict,
        ratio: Double,
        specOf: Map<RawVideoConfig, String>,
        evidence: JSONObject?,
        measuredAt: Long,
        achievedFps: Double?,
    ): RawVideoQualificationRecord? {
        if (verdict.status == RawBenchmarkStatus.UNKNOWN) return null
        val ringBytes = ring.slotCount.toLong() * config.bytesPerFrame
        val base = RawThroughputBenchmark.qualifyWithBuffer(config, ringBytes, sweep, null, ratio)
        val extrapolated = RawQualificationStaging.extrapolatedQualification(base, verdict)
        val record = RawQualificationRecords.forRun(
            Build.FINGERPRINT, destinationId, config, ringBytes,
            RawQualificationStaging.withSustainedRate(extrapolated, config, ratio, sweep.sustained, achievedFps), emptyList(),
            measuredAt, verdict.method, verdict.coveredBy?.let { specOf[it] ?: RawVideoConfigKeys.key(it) }, evidence?.toString(), achievedFps,
        )
        // A risky verdict (compressed twins included, judged at [ratio]) says how long a take lasts past the write cache.
        return RawQualificationRecords.withBurstSeconds(record, sweep.sustained, ratio)
    }

    /** The `kind: live` row of a configuration's staged verdict; its status is the stored record's when there is one. */
    private fun verdictRow(
        run: RawQualificationRun,
        verdict: RawStagedVerdict,
        specOf: Map<RawVideoConfig, String>,
        neededBytes: Long,
        record: RawVideoQualificationRecord?,
    ): JSONObject {
        val q = record?.qualification
        val status = q?.status?.name ?: verdict.status.name
        val codes = q?.reasonCodes ?: verdict.reasonCodes.ifEmpty { listOf(RawThroughputBenchmark.REASON_PASS) }
        return JSONObject()
            .put("kind", "live")
            .put("stage", "verdict")
            .put("graph", GRAPH)
            .put("cameraId", run.config.cameraId)
            .put("spec", run.spec)
            .put("crop", run.cropLabel)
            .put("targetFps", run.config.fps)
            .put("encoding", run.config.encoding)
            .put("streamBytesPerSecond", run.config.streamBytesPerSecond)
            .put("status", status)
            .put("reasonCode", codes.first())
            .put("reasonCodes", JSONArray(codes))
            .put("method", verdict.method)
            .put("coveredBy", verdict.coveredBy?.let { specOf[it] ?: RawVideoConfigKeys.key(it) } ?: JSONObject.NULL)
            .put("neededBytes", neededBytes)
            .put("burstSeconds", record?.burstSeconds ?: JSONObject.NULL)
            .put("achievedFps", record?.achievedFps ?: JSONObject.NULL)
            .put("record", record?.let { JSONObject(RawVideoQualificationCodec.encodeRecord(it).toString()) } ?: JSONObject.NULL)
    }

    /** The encoder and output of one benchmark session, or null when the native codec cannot be created. */
    private class EncodeSession(
        val encoding: String,
        val encoder: RawFrameEncoder,
        val compressor: RawFrameCompressor,
        val threads: Int,
        val strips: Int,
    )

    /** One output buffer, reused by every benchmark session (they run one after another). */
    private var benchOutput: ByteBuffer? = null

    private fun encodeSession(encoding: String, threads: Int, strips: Int, slotBytes: Int): EncodeSession? = runCatching {
        val encoder = RawFrameEncoders.native(encoding, threads, strips) ?: return@runCatching null
        synchronized(benchEncoders) { benchEncoders += encoder }
        val output = benchOutput ?: slotMemory.allocate(slotBytes).also { benchOutput = it }
        EncodeSession(encoding, encoder, RawFrameCompressor(encoder, output), threads, strips)
    }.onFailure { Log.w(TAG, "$encoding encoder unavailable", it) }.getOrNull()

    /** Closes [encoder] once the last consumer thread is done with it. */
    private fun closeAfterConsumer(encoder: RawFrameEncoder) {
        afterWriter(lastWriter) {
            // Whoever takes it off the list closes it: this, or [release] if it ran first.
            if (synchronized(benchEncoders) { benchEncoders.remove(encoder) }) runCatching { encoder.close() }
        }
    }

    // region cameras

    private class CameraInfo(val id: String, val characteristics: CameraCharacteristics, val size: Size)

    private fun selectCameras(manager: CameraManager): List<CameraInfo> {
        fun info(id: String): CameraInfo? {
            val characteristics = manager.getCameraCharacteristics(id)
            val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW10)
                ?.filter { it.width > 0 && it.height > 0 && it.width % 4 == 0 }
                ?.maxByOrNull { it.width.toLong() * it.height }
                ?: return null
            return CameraInfo(id, characteristics, size)
        }
        cameraIds?.let { requested -> return requested.mapNotNull { id -> runCatching { info(id) }.getOrNull() } }
        return manager.cameraIdList.asSequence()
            .filter { id -> manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            .mapNotNull(::info)
            .take(1)
            .toList()
    }

    // endregion

    // region storage sweep

    private class SweepResult(val sweep: RawStorageSweep, val json: JSONObject, val rest: RawStorageRestResult?)

    /**
     * Unthrottled `RAW_FRAME` chunks: the 44-byte chunk header, the 64-byte frame header and a full
     * frame from one pre-filled direct buffer, one gather write each, with a CRC32C over the payload
     * per frame, so the cost per frame matches [OcrawStreamWriter]. It bypasses the writer only so it
     * can rewind: when the volume lacks room for an unbounded minute the position wraps to just after
     * the 32-byte file header every [SWEEP_WRAP_BYTES]. A force runs every 256 MiB, so a rewind still
     * measures the flash rather than the page cache.
     *
     * After the [windows] burst windows (the only ones rule v2 reads) it keeps writing until the
     * write cache's cliff and [RawStorageCliff.SUSTAINED_TAIL_WINDOWS] windows after it are measured,
     * or [maxExtraSeconds] have passed, or the sweep wrote [RawStorageCliff.SUSTAINED_MAX_TOTAL_BYTES]
     * in all ([RawStorageCliff.sweepStop]), so the long runs, which follow at once, always write past the cache. An unbounded sweep ends that phase early (`free-space`)
     * before its row would leave less than [RawQualificationTiming.SPACE_RESERVE_BYTES] free.
     *
     * Right after the burst windows [RawStorageRest.judge] checks them against [restReference]: a
     * write cache still flushing ends the sweep there ([RawStorageCliff.STOP_NOT_RESTED], no sustained
     * phase) unless [ignoreStorageRest] or the run qualifies a drive. The sweep's `rest` object carries the figures either way.
     */
    private fun storageSweep(size: Size, windows: Int, maxExtraSeconds: Int, restReference: Long?, index: Int = 0, runCount: Int = 0): SweepResult {
        val header = OcrawFrameHeader.raw10(size.width, size.height, 0, 0, 0, 0)
        val frame = ByteBuffer.allocateDirect(header.dataLength).order(ByteOrder.LITTLE_ENDIAN)
        // Varied, incompressible bytes (a 64-bit LCG), so no layer can shortcut the write.
        var seed = 0x9E3779B97F4A7C15uL.toLong()
        while (frame.remaining() >= 8) { seed = seed * 6364136223846793005L + 1442695040888963407L; frame.putLong(seed) }
        while (frame.hasRemaining()) frame.put(seed.toByte())
        frame.clear()
        val chunkHeader = ByteBuffer.allocateDirect(OcrawCodec.CHUNK_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val frameHeader = ByteBuffer.allocateDirect(OcrawFrameHeader.BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val gather = arrayOf(chunkHeader, frameHeader, frame)
        val crc = Crc32c.newAccumulator()
        val payloadLength = OcrawFrameHeader.BYTES + header.dataLength
        val chunkBytes = OcrawCodec.CHUNK_HEADER_BYTES.toLong() + payloadLength
        val expectedBytes = SWEEP_ASSUMED_CEILING_BYTES_PER_SECOND * windows
        val freeBytes = freeBytes()
        val wrapAt = if (freeBytes >= expectedBytes * 2) Long.MAX_VALUE else SWEEP_WRAP_BYTES
        val samples = mutableListOf<RawThroughputSample>()
        var forceCount = 0
        var forceMaxNs = 0L
        var forceTotalNs = 0L
        var writeMaxNs = 0L
        var frames = 0L
        var wraps = 0
        var totalBytes = 0L
        var stopReason: String? = null
        var rest: RawStorageRestResult? = null
        val sweepStart = SystemClock.elapsedRealtime()
        var lastProgress = sweepStart
        val uri = insertRow("sweep")
        try {
            requireNotNull(context.contentResolver.openFileDescriptor(uri, "rw")) { "sweep row could not be opened" }.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                    val driveHandle = registerDriveRow(uri, channel, pfd)
                    try {
                        val fileHeader = ByteBuffer.wrap(OcrawCodec.encodeHeader(OcrawHeader(randomUuid())))
                        while (fileHeader.hasRemaining()) channel.write(fileHeader)
                        var position = OcrawCodec.HEADER_BYTES.toLong()
                        var sinceForce = position
                        var windowBytes = 0L
                        var windowStart = SystemClock.elapsedRealtimeNanos()
                        while (stopReason == null) {
                            checkCancelled()
                            if (samples.size >= windows && !sustainedRoomLeft(wrapAt, position + chunkBytes, freeBytes)) {
                                stopReason = RawStorageCliff.STOP_FREE_SPACE
                                break
                            }
                            // The burst windows always complete; past them the sweep never writes more than the cap.
                            if (samples.size >= windows && totalBytes + chunkBytes > RawStorageCliff.SUSTAINED_MAX_TOTAL_BYTES) {
                                stopReason = RawStorageCliff.STOP_MAX_BYTES
                                break
                            }
                            if (position + chunkBytes > wrapAt) {
                                channel.position(OcrawCodec.HEADER_BYTES.toLong())
                                position = OcrawCodec.HEADER_BYTES.toLong()
                                wraps++
                            }
                            val start = SystemClock.elapsedRealtimeNanos()
                            frameHeader.clear()
                            header.copy(sensorTimestampNs = start, frameNumber = frames).writeTo(frameHeader)
                            frameHeader.flip()
                            frame.clear()
                            crc.reset(); crc.update(frameHeader); crc.update(frame)
                            chunkHeader.clear()
                            chunkHeader.putShort(OcrawChunkId.RAW_FRAME.wireId.toShort()).putShort(0)
                                .putLong(frames).putLong(start).putLong(1).putLong(NANOS)
                                .putInt(payloadLength).putInt(crc.value())
                            chunkHeader.flip()
                            var remaining = chunkBytes
                            while (remaining > 0) remaining -= channel.write(gather)
                            val written = SystemClock.elapsedRealtimeNanos()
                            writeMaxNs = maxOf(writeMaxNs, written - start)
                            position += chunkBytes; sinceForce += chunkBytes; windowBytes += chunkBytes; totalBytes += chunkBytes
                            frames++
                            if (sinceForce >= FORCE_EVERY_BYTES) {
                                channel.force(false)
                                val forced = SystemClock.elapsedRealtimeNanos() - written
                                forceMaxNs = maxOf(forceMaxNs, forced); forceTotalNs += forced; forceCount++
                                sinceForce = 0
                            }
                            val now = SystemClock.elapsedRealtimeNanos()
                            if (now - windowStart >= 1_000_000_000L) {
                                samples += RawThroughputSample(((now - windowStart) / 1_000_000L).coerceAtLeast(1), windowBytes, 0)
                                windowBytes = 0; windowStart = now
                                if (samples.size == windows) {
                                    rest = RawStorageRest.judge(samples.toList(), restReference)
                                    if (rest?.rested == false && !restRuleIgnored) stopReason = RawStorageCliff.STOP_NOT_RESTED
                                }
                                if (stopReason == null) stopReason = RawStorageCliff.sweepStop(samples, windows, maxExtraSeconds)
                            }
                            val nowMs = now / 1_000_000L
                            if (nowMs - lastProgress >= PROGRESS_INTERVAL_MS) {
                                lastProgress = nowMs
                                progress(RawQualificationPhase.SWEEP, index, runCount)
                            }
                        }
                        channel.force(false)
                    } finally {
                        driveHandle.unregister()
                    }
                }
            }
        } finally {
            deleteRow(uri)
        }
        val burst = samples.take(windows)
        val extra = samples.drop(windows)
        val sustained = RawStorageCliff.detect(samples, windows)
        val sweep = RawStorageSweep(destinationId, burst, sustained.takeIf { maxExtraSeconds > 0 })
        val rates = burst.map(RawThroughputSample::bytesPerSecond)
        val json = JSONObject()
            .put("destinationId", destinationId)
            .put("frameSize", "${size.width}x${size.height}")
            .put("chunkBytes", chunkBytes)
            .put("windows", sweep.windowCount)
            .put("totalDurationMs", sweep.totalDurationMs)
            .put("totalBytes", totalBytes)
            .put("medianBytesPerSecond", sweep.medianBytesPerSecond ?: JSONObject.NULL)
            .put("p01BytesPerSecond", sweep.p01BytesPerSecond ?: JSONObject.NULL)
            .put("minBytesPerSecond", sweep.minBytesPerSecond ?: JSONObject.NULL)
            .put("maxBytesPerSecond", rates.maxOrNull() ?: JSONObject.NULL)
            .put("forceEveryBytes", FORCE_EVERY_BYTES)
            .put("forceCount", forceCount)
            .put("forceMaxMs", forceMaxNs / 1e6)
            .put("forceMeanMs", if (forceCount > 0) forceTotalNs / 1e6 / forceCount else JSONObject.NULL)
            .put("writeMaxMs", writeMaxNs / 1e6)
            .put("freeBytesAtStart", freeBytes)
            .put("wrapBytes", if (wrapAt == Long.MAX_VALUE) JSONObject.NULL else wrapAt)
            .put("wraps", wraps)
            .put("samples", samplesJson(burst))
            .put("sustained", sustainedJson(sustained, extra, stopReason, maxExtraSeconds))
            .put("rest", rest?.let(::restJson) ?: JSONObject.NULL)
        return SweepResult(sweep, json, rest)
    }

    /** The sweep's `rest` object: [RawStorageRest]'s verdict on the burst windows. */
    private fun restJson(rest: RawStorageRestResult) = JSONObject()
        .put("rested", rest.rested)
        .put("rule", rest.rule ?: JSONObject.NULL)
        .put("medianBytesPerSecond", rest.medianBytesPerSecond ?: JSONObject.NULL)
        .put("p01BytesPerSecond", rest.p01BytesPerSecond ?: JSONObject.NULL)
        .put("referenceBytesPerSecond", rest.referenceBytesPerSecond ?: JSONObject.NULL)
        .put("cliffWindow", rest.cliffWindow ?: JSONObject.NULL)
        .put("worstSpread", rest.worstSpread?.let { Math.round(it * 1000) / 1000.0 } ?: JSONObject.NULL)
        .put("ignored", restRuleIgnored && !rest.rested)

    /**
     * The sweep's `sustained` object: the cliff ([RawStorageCliff.detect] over every window), why the
     * sustained phase ended, and its windows (`samples`, after the burst ones in the sweep's own `samples`).
     */
    private fun sustainedJson(
        found: RawSustainedWrite,
        extra: List<RawThroughputSample>,
        stopReason: String?,
        maxExtraSeconds: Int,
    ) = JSONObject()
        .put("cliffDetected", found.cliffDetected)
        .put("cliffWindow", found.cliffWindow ?: JSONObject.NULL)
        .apply { if (found.cliffDetected) put("cacheBytes", found.cacheBytes) else put("cacheBytesAtLeast", found.cacheBytesAtLeast) }
        .put("burstBytesPerSecond", found.burstBytesPerSecond ?: JSONObject.NULL)
        .put("sustainedBytesPerSecond", found.sustainedBytesPerSecond ?: JSONObject.NULL)
        .put("windows", found.windows)
        .put("windowsAfterCliff", found.windowsAfterCliff)
        .put("extraWindows", extra.size)
        .put("extraDurationMs", extra.sumOf { it.durationMs })
        .put("extraBytes", extra.sumOf { it.bytesWritten })
        .put("maxExtraSeconds", maxExtraSeconds)
        .put("maxTotalBytes", RawStorageCliff.SUSTAINED_MAX_TOTAL_BYTES)
        .put("stopReason", stopReason ?: JSONObject.NULL)
        .put("stoppedEarlyForSpace", stopReason == RawStorageCliff.STOP_FREE_SPACE)
        .put("samples", samplesJson(extra))

    // endregion

    // region ring

    private class RingPlan(val ring: RawVideoRing?, val json: JSONObject)

    /**
     * PLAN-071 decision 4 ([RawRingBudget.targetBytes]): 4 GiB or 2 GiB on 16 or 12 GB phones, otherwise
     * min(1 GiB, totalMem / 8), kept below `availMem - threshold`.
     * The slots are off-heap shared memory ([SharedMemorySlots]): a direct buffer on ART would come
     * out of the Java heap, far smaller than the ring. The binding limit is recorded.
     */
    private fun allocateRing(slotBytes: Int): RingPlan {
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        val target = ringTargetBytes ?: RawRingBudget.targetBytes(memory.totalMem)
        val memoryCap = RawRingBudget.memoryCapBytes(memory.availMem, memory.threshold)
        val planned = minOf(target, memoryCap)
        val limitedBy = if (planned == target) "target" else "availMem"
        var slots = (planned / slotBytes).toInt()
        var ring: RawVideoRing? = null
        var oomRetries = 0
        val allocStartMs = SystemClock.elapsedRealtime()
        while (ring == null && slots >= RawRingBudget.MIN_SLOTS) {
            try {
                ring = RawVideoRing(slotBytes, slots, slotMemory::allocate)
            } catch (_: Throwable) {
                slotMemory.close()
                oomRetries++
                slots--
                System.gc()
            }
        }
        val json = JSONObject()
            .put("allocated", ring != null)
            .put("ringBytes", ring?.capacityBytes ?: 0)
            .put("slotBytes", slotBytes)
            .put("slotCount", ring?.slotCount ?: 0)
            .put("targetBytes", target)
            .put("availMemCapBytes", memoryCap)
            .put("slotMemory", "sharedMemory")
            .put("limitedBy", if (oomRetries > 0) "outOfMemory" else limitedBy)
            .put("oomRetries", oomRetries)
            .put("allocMs", SystemClock.elapsedRealtime() - allocStartMs)
            .put("totalMem", memory.totalMem)
            .put("availMem", memory.availMem)
            .put("threshold", memory.threshold)
            .put("javaHeapMax", runtime.maxMemory())
            .put("minSlots", RawRingBudget.MIN_SLOTS)
        return RingPlan(ring, json)
    }

    // endregion

    // region live run

    private class LiveOutcome(val json: JSONObject, val record: RawVideoQualificationRecord?)

    /**
     * One camera session of [kind] at [run]: a [SessionKind.LONG] run drains the ring to a row with an
     * [OcrawStreamWriter] and scans it back; a [SessionKind.CAMERA] check and a [SessionKind.ENCODE]
     * benchmark session write nothing and create no row: their consumer thread releases each slot,
     * after encoding it with [bench]'s compressor for a benchmark.
     */
    private fun liveRun(
        manager: CameraManager,
        camera: CameraInfo,
        run: RawQualificationRun,
        ring: RawVideoRing,
        sweep: RawStorageSweep?,
        kind: SessionKind,
        bench: EncodeSession?,
        index: Int,
        runCount: Int,
        expectedFps: Double? = null,
        coolFirst: Boolean = index > 1,
    ): LiveOutcome {
        val config = run.config
        val liveSeconds = kind.measureSeconds(config)
        val warmupMs = kind.warmupMs()
        // Encode benchmarks and long runs start cool (a long run that reaches SEVERE fails with thermal-limit); camera
        // checks only need to be below SEVERE. [coolFirst] is false for the first session and for the long run right
        // after the sweep, which must write while the sweep's cache exhaustion lasts.
        val heavy = (kind == SessionKind.ENCODE || kind == SessionKind.LONG) && coolFirst
        val thermalWait = if (heavy) {
            waitForThermalHeadroom(index, runCount, run.spec, PowerManager.THERMAL_STATUS_MODERATE, COOL_WAIT_MS)
        } else {
            waitForThermalHeadroom(index, runCount, run.spec, PowerManager.THERMAL_STATUS_SEVERE, THERMAL_WAIT_MS)
        }
        val thermalBefore = thermalWait.status
        val thermalWaitMs = thermalWait.waitedMs
        if (thermalBefore >= PowerManager.THERMAL_STATUS_SEVERE) {
            return LiveOutcome(
                skipped(run, "thermal-skip", kind).put("thermalBefore", thermalBefore).put("thermalWaitMs", thermalWaitMs),
                null,
            )
        }
        thermalPeak = thermalBefore
        val batteryBefore = batteryTemperatureC()
        progress(kind.phase, index = index, count = runCount, spec = run.spec)
        if (kind == SessionKind.LONG) {
            val needed = timing.neededBytes(config)
            val free = freeBytes()
            if (free < needed) {
                return LiveOutcome(skipped(run, "insufficient-space", kind).put("freeBytes", free).put("neededBytes", needed), null)
            }
            requireNotNull(sweep) { "a long run needs the sweep" }
        }
        check(ring.usedSlots == 0) { "ring not drained before the run" }
        require(config.bytesPerFrame <= ring.slotBytes) { "frame exceeds the ring slot" }
        Log.i(TAG, "${kind.stage} start camera=${camera.id} spec=${run.spec} graph=$GRAPH seconds=$liveSeconds")

        val characteristics = camera.characteristics
        val size = camera.size
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSize = streamMap?.getOutputSizes(SurfaceTexture::class.java)?.map { it.width to it.height }
            ?.let(::rawQualificationPreviewSize) ?: (1920 to 1080)
        val declaredMinFrameDurationNs = maxOf(
            runCatching { streamMap?.getOutputMinFrameDuration(ImageFormat.RAW10, size) ?: 0L }.getOrDefault(0L),
            runCatching { streamMap?.getOutputMinFrameDuration(SurfaceTexture::class.java, Size(previewSize.first, previewSize.second)) ?: 0L }
                .getOrDefault(0L),
        )
        val cameraThread = HandlerThread("occ-raw-camera").apply { start() }
        val readerThread = HandlerThread("occ-raw-reader").apply { start() }
        val cameraHandler = Handler(cameraThread.looper)
        val readerHandler = Handler(readerThread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW10, RAW_VIDEO_MAX_IMAGES)
        val previewTexture = SurfaceTexture(0).apply { setDefaultBufferSize(previewSize.first, previewSize.second) }
        val previewSurface = Surface(previewTexture)

        val timestampCapacity = (liveSeconds + (warmupMs / 1_000L).toInt() + 5) * maxOf(config.fps, 30) * 2 + 256
        val state = LiveState(timestampCapacity)
        liveSession = state
        val compressor = bench?.compressor
        compressor?.reset()
        val effectiveRingBytes = ring.slotCount.toLong() * config.bytesPerFrame
        val highWaterSlots = HIGH_WATER_FRACTION * ring.slotCount

        reader.setOnImageAvailableListener({ source ->
            val image = try { source.acquireNextImage() } catch (_: IllegalStateException) { state.acquireFailures++; null }
                ?: return@setOnImageAvailableListener
            try {
                val frameIndex = state.imageIndex++
                if (state.producerStop) { state.producerDone = true; return@setOnImageAvailableListener }
                if (!state.measuring) { state.warmupFrames++; return@setOnImageAvailableListener }
                val timestamp = image.timestamp
                if (state.startTs < 0) {
                    state.startTs = timestamp
                    state.readerCpuStartNs = android.os.Debug.threadCpuTimeNanos()
                    state.readerWallStartNs = SystemClock.elapsedRealtimeNanos()
                }
                state.endTs = timestamp
                val handled = state.handled++
                if (handled < state.imageTs.length()) state.imageTs.set(handled, timestamp) else state.timestampOverflow = true
                val slot = ring.tryAcquire()
                if (slot == null) {
                    state.overflow = true
                    state.lostFrames++
                    return@setOnImageAvailableListener
                }
                val used = ring.usedSlots
                if (used > state.peakSlots) state.peakSlots = used
                if (used >= highWaterSlots) state.highWaterReached = true
                try {
                    val plane = image.planes[0]
                    val copyStart = System.nanoTime()
                    copyRaw10Rows(plane.buffer, plane.rowStride, image.width, image.height,
                        run.cropLeft, run.cropTop, config.cropWidth, config.cropHeight, slot.buffer)
                    val copyNs = System.nanoTime() - copyStart
                    state.copyMaxNs = maxOf(state.copyMaxNs, copyNs)
                    state.copyTotalNs += copyNs
                    val copyIndex = state.copies++
                    if (copyIndex < state.copyNs.length()) state.copyNs.set(copyIndex.toInt(), copyNs)
                    if (state.rowStride == 0) state.rowStride = plane.rowStride
                    ring.publish(slot, OcrawFrameHeader.raw10(config.cropWidth, config.cropHeight, run.cropLeft, run.cropTop,
                        timestamp, frameIndex.toLong()))
                    state.published++
                } catch (error: RuntimeException) {
                    ring.cancel(slot)
                    state.copyErrors++
                    state.lostFrames++
                    if (state.copyError == null) state.copyError = describe(error)
                }
            } finally {
                image.close()
                if (state.readerCpuStartNs >= 0 && state.measuring && !state.producerStop) {
                    state.readerCpuEndNs = android.os.Debug.threadCpuTimeNanos()
                    state.readerWallEndNs = SystemClock.elapsedRealtimeNanos()
                }
            }
        }, readerHandler)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var uri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        var driveHandle = DriveDescriptorHandle.NONE
        var writerThread: Thread? = null
        val closed = CountDownLatch(1)
        try {
            var openError: String? = null
            val opened = CountDownLatch(1)
            manager.openCamera(camera.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; opened.countDown() }
                override fun onDisconnected(d: CameraDevice) { openError = "camera-disconnected"; d.close(); opened.countDown() }
                override fun onError(d: CameraDevice, error: Int) { openError = "camera-open-error-$error"; d.close(); opened.countDown() }
                override fun onClosed(d: CameraDevice) { closed.countDown() }
            }, cameraHandler)
            if (!awaitCancellable(opened, TIMEOUT_SECONDS)) return LiveOutcome(skipped(run, "camera-open-timeout"), null)
            openError?.let { return LiveOutcome(skipped(run, it), null) }
            val openedDevice = requireNotNull(device)
            val aeRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().toList()
            // A QTI_RAW_FRAME_RATE_OVERRIDE camera (the Razr) delivers nothing with an AE range past the
            // declared RAW limit; the plan keeps the range within it and sends 1/fps through the vendor keys.
            val qtiQuirk = runCatching { deviceQuirks.resolve(camera.id).applies(QuirkEffect.QTI_RAW_FRAME_RATE_OVERRIDE) }
                .onFailure { Log.w(TAG, "device quirks unavailable for camera ${camera.id}", it) }
                .getOrDefault(false)
            val frameRatePlan = rawVideoFrameRatePlan(
                aeRanges.map { it.lower to it.upper }, config.fps, declaredMinFrameDurationNs.takeIf { it > 0 }, qtiQuirk,
            )
            val vendorFrameDurationNs = frameRatePlan.vendorFrameDurationNs
            val aeRange = if (vendorFrameDurationNs != null) {
                frameRatePlan.aeRange?.let { (lower, upper) -> Range(lower, upper) }
            } else {
                aeRanges.firstOrNull { it.lower == config.fps && it.upper == config.fps }
            }

            // The AE range is a session key: a session configured without it is reconfigured by the first
            // request, and on the Razr that reconfigured session skips about one frame in six at 60 fps.
            var configureFailed = false
            val configured = CountDownLatch(1)
            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(reader.surface), OutputConfiguration(previewSurface)),
                { command -> cameraHandler.post(command) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) { configureFailed = true; configured.countDown() }
                },
            )
            if (aeRange != null) {
                sessionConfiguration.sessionParameters = openedDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeRange)
                }.build()
            }
            openedDevice.createCaptureSession(sessionConfiguration)
            if (!awaitCancellable(configured, TIMEOUT_SECONDS)) return LiveOutcome(skipped(run, "session-timeout"), null)
            if (configureFailed) return LiveOutcome(skipped(run, "session-configure-failed"), null)

            var qtiFrameRateOverride = false
            val request = openedDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                if (vendorFrameDurationNs != null) {
                    qtiFrameRateOverride = QtiRawFrameRate.apply(this, vendorFrameDurationNs)
                    if (!qtiFrameRateOverride) Log.w(TAG, "QTI frame-rate override keys could not be set on camera ${camera.id}")
                }
                if (aeRange != null) {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeRange)
                } else {
                    // No fixed AE range at this rate: drive the sensor directly, as the probe did.
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, NANOS / config.fps)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, minOf(4_000_000L, NANOS / config.fps / 2))
                    set(CaptureRequest.SENSOR_SENSITIVITY, 800)
                }
            }.build()

            // Destination and writer for a long run; a check or a benchmark only consumes the ring.
            var descriptor: ParcelFileDescriptor? = null
            val writer = if (kind == SessionKind.LONG) {
                val rowUri = insertRow("${config.cameraId}-${config.cropWidth}x${config.cropHeight}-${config.fps}-${config.encoding}")
                uri = rowUri
                val opened = requireNotNull(context.contentResolver.openFileDescriptor(rowUri, "rw")) { "live row could not be opened" }
                descriptor = opened
                pfd = opened
                val channel = FileOutputStream(opened.fileDescriptor).channel
                driveHandle = registerDriveRow(rowUri, channel, opened)
                // A compressed long run writes as a take does: each frame encoded on the writer thread, then written.
                val metadata = staticMetadata(camera, run) + (RawFrameEncoders.KEY_ENCODING to config.encoding) + compressor?.metadata.orEmpty()
                Thread({ writerLoop(channel, metadata, ring, state, compressor) }, "occ-raw-writer")
            } else {
                Thread({ consumerLoop(ring, state, compressor) }, "occ-raw-consumer")
            }
            writerThread = writer
            lastWriter = writer
            writer.start()

            val activeSession = requireNotNull(session)
            activeSession.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    val i = state.captureCount.getAndIncrement()
                    if (i < state.captureTs.length()) state.captureTs.set(i, ts) else state.timestampOverflow = true
                    // Per-frame evidence of the measured window only (frameDiagnostics); never a verdict input.
                    if (state.measuring && !state.producerStop) {
                        val m = state.measuredCaptures.getAndIncrement()
                        if (m < state.captureExposureNs.length()) {
                            state.captureExposureNs.set(m, result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L)
                            state.captureSensitivity.set(m, (result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0).toLong())
                            state.captureFrameDurationNs.set(m, result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L)
                        }
                    }
                }

                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    state.captureFailed.incrementAndGet()
                }

                override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frameNumber: Long) {
                    if (target == reader.surface) state.bufferLost.incrementAndGet()
                }
            }, cameraHandler)
            sleepCancellable(warmupMs, kind.phase, index, runCount, run.spec)
            if (state.imageIndex == 0) sleepCancellable(NO_FRAMES_GRACE_MS, kind.phase, index, runCount, run.spec)
            // Nothing at all after the warm-up and the grace: the measured window would only wait, so skip it.
            val endedEarly = state.imageIndex == 0
            if (endedEarly) Log.w(TAG, "live no frames camera=${camera.id} spec=${run.spec}: measured window skipped")
            val cpuStartMs = android.os.Process.getElapsedCpuTime()
            val wallStartMs = SystemClock.elapsedRealtime()
            if (!endedEarly) {
                state.measuring = true
                sleepCancellable(liveSeconds * 1_000L + RawQualificationTiming.MEASURE_SLACK_MS, kind.phase, index, runCount, run.spec)
            }
            state.producerStop = true
            // Whole-process CPU over the measured window: camera callbacks, copies, writer and encoder pool.
            val wallMs = SystemClock.elapsedRealtime() - wallStartMs
            state.processCpuCores = if (wallMs > 0) (android.os.Process.getElapsedCpuTime() - cpuStartMs).toDouble() / wallMs else 0.0
            val stoppedAt = SystemClock.elapsedRealtime()
            runCatching { activeSession.stopRepeating() }
            // Let in-flight results land, then make sure the reader thread is past its last publish.
            sleepCancellable(1_000L, kind.phase, index, runCount, run.spec)
            awaitIdle(readerHandler, TIMEOUT_SECONDS * 1_000L)
            state.queuedAtStop = ring.queuedSlots
            state.writerStop = true
            // A slow storage drains for a while: the time left keeps counting down meanwhile.
            joinCancellable(writer, WRITER_JOIN_MS) { progress(kind.phase, index, runCount, run.spec) }
            val writerAlive = writer.isAlive
            val drainMs = SystemClock.elapsedRealtime() - stoppedAt

            val thermalAfter = thermalStatus()
            thermalPeak = maxOf(thermalPeak, thermalAfter)
            val probe = SessionProbe(run, kind, liveSeconds, aeRange, qtiFrameRateOverride, thermalBefore, thermalWaitMs, thermalAfter, thermalPeak,
                declaredMinFrameDurationNs, endedEarly, writerAlive, drainMs, expectedFps ?: config.fps.toDouble())
            return when (kind) {
                SessionKind.LONG -> {
                    // Read the file back.
                    val scan = if (!writerAlive) runCatching {
                        FileInputStream(requireNotNull(descriptor).fileDescriptor).channel.use { OcrawStreamReader(it).scan(collectFrames = false) }
                    } else null
                    liveResult(probe, state, ring, effectiveRingBytes, requireNotNull(sweep), scan, compressor)
                }
                SessionKind.CAMERA -> cameraCheckResult(probe, state, ring)
                SessionKind.ENCODE -> benchResult(probe, state, ring, requireNotNull(bench), batteryBefore, batteryTemperatureC())
            }
        } finally {
            liveSession = null
            val aborting = cancelled.get()
            state.producerStop = true
            if (aborting) runCatching { session?.stopRepeating() }
            // No reader callback may touch the ring once this returns: the ring outlives the run.
            awaitIdle(readerHandler, if (aborting) ABORT_WAIT_MS else TIMEOUT_SECONDS * 1_000L)
            // A cancelled run's queued frames are released unwritten, so the writer ends at once.
            if (aborting) state.discard = true
            state.writerStop = true
            writerThread?.let { if (it.isAlive) it.join(if (aborting) ABORT_WAIT_MS else WRITER_JOIN_MS) }
            runCatching { session?.close() }
            runCatching { device?.let { it.close(); closed.await(if (aborting) ABORT_WAIT_MS else TIMEOUT_SECONDS * 1_000L, TimeUnit.MILLISECONDS) } }
            runCatching { reader.close() }
            runCatching { previewSurface.release(); previewTexture.release() }
            readerThread.quitSafely()
            cameraThread.quitSafely()
            // The descriptor and the row go only once the writer is done with them.
            val descriptor = pfd
            val row = uri
            val handle = driveHandle
            afterWriter(writerThread) {
                runCatching { descriptor?.close() }
                handle.unregister()
                row?.let(::deleteRow)
            }
        }
    }

    /** Mutable state of one live run, shared by the reader thread, the writer thread and the run thread. */
    private class LiveState(timestampCapacity: Int) {
        @Volatile var measuring = false
        @Volatile var producerStop = false
        @Volatile var producerDone = false
        @Volatile var writerStop = false

        /** Set on cancellation: the writer releases what is queued without writing it. */
        @Volatile var discard = false
        @Volatile var startTs = -1L
        @Volatile var endTs = -1L
        @Volatile var timestampOverflow = false

        // Reader thread only (read by the run thread after the reader is idle).
        @Volatile var imageIndex = 0
        @Volatile var warmupFrames = 0
        @Volatile var handled = 0
        @Volatile var published = 0L
        @Volatile var lostFrames = 0L
        @Volatile var overflow = false
        @Volatile var highWaterReached = false
        @Volatile var peakSlots = 0
        @Volatile var copyMaxNs = 0L
        @Volatile var copyTotalNs = 0L
        @Volatile var copies = 0L
        @Volatile var copyErrors = 0L
        @Volatile var copyError: String? = null
        @Volatile var acquireFailures = 0
        @Volatile var rowStride = 0
        val imageTs = AtomicLongArray(timestampCapacity)
        /** Crop copy time of each measured frame, in copy order. */
        val copyNs = AtomicLongArray(timestampCapacity)
        /** Reader thread CPU and wall clock at the first and the latest measured frame; -1 until known. */
        @Volatile var readerCpuStartNs = -1L
        @Volatile var readerCpuEndNs = -1L
        @Volatile var readerWallStartNs = -1L
        @Volatile var readerWallEndNs = -1L

        // Camera thread.
        val captureTs = AtomicLongArray(timestampCapacity)
        /** Exposure, sensitivity and frame duration of each capture result of the measured window (0 when not reported). */
        val captureExposureNs = AtomicLongArray(timestampCapacity)
        val captureSensitivity = AtomicLongArray(timestampCapacity)
        val captureFrameDurationNs = AtomicLongArray(timestampCapacity)
        val measuredCaptures = AtomicInteger()
        val captureCount = AtomicInteger()
        val captureFailed = AtomicInteger()
        val bufferLost = AtomicInteger()

        // Writer thread.
        @Volatile var writerStats: OcrawStreamStats? = null
        @Volatile var writerError: String? = null
        @Volatile var framesWritten = 0L
        /** Per-frame encode times of an encode benchmark, in encode order. */
        val encodeNs = AtomicLongArray(timestampCapacity)
        @Volatile var encodes = 0
        /** The lowest single-frame compression ratio of an encode benchmark (RAW10 bytes / stored bytes). */
        @Volatile var minFrameRatio = Double.MAX_VALUE
        /** Slots still queued when the producer stopped (a benchmark's encoder backlog). */
        @Volatile var queuedAtStop = 0

        // Run thread.
        @Volatile var processCpuCores = 0.0
    }

    /**
     * The writer thread of a long run: drains the ring into one [OcrawStreamWriter] until told to stop
     * and the ring is empty. With a [compressor] (a compressed long run) each frame is encoded first
     * and the encoded frame written, as [RawVideoRecorder]'s writer does.
     */
    private fun writerLoop(
        channel: FileChannel,
        metadata: Map<String, String>,
        ring: RawVideoRing,
        state: LiveState,
        compressor: RawFrameCompressor? = null,
    ) {
        val writer = try {
            OcrawStreamWriter(channel, OcrawHeader(randomUuid()), OcrawTimeBase(1, NANOS)).also { it.writeMetadata(metadata, 0) }
        } catch (error: Throwable) {
            state.writerError = describe(error)
            null
        }
        while (true) {
            val slot = ring.take(WRITER_POLL_MS)
            if (slot == null) {
                if (state.writerStop && ring.queuedSlots == 0) break
                continue
            }
            try {
                val header = requireNotNull(slot.header)
                if (writer != null && state.writerError == null && !state.discard) {
                    val (frameHeader, data) = if (compressor == null) header to slot.buffer else {
                        compressor.compress(header, slot.buffer).also { (stored, _) ->
                            val index = state.encodes++
                            if (index < state.encodeNs.length()) state.encodeNs.set(index, compressor.lastEncodeNs)
                            if (stored.dataLength > 0) state.minFrameRatio = minOf(state.minFrameRatio, header.dataLength.toDouble() / stored.dataLength)
                        }
                    }
                    writer.writeRawFrame(frameHeader, data, header.sensorTimestampNs - state.startTs)
                    state.framesWritten++
                }
            } catch (error: Throwable) {
                // Keep draining so the reader never blocks; the run is evidence of a write failure.
                if (state.writerError == null) state.writerError = describe(error)
            } finally {
                ring.release(slot)
            }
        }
        if (writer != null) {
            state.writerStats = try {
                if (state.writerError == null && !state.discard) writer.finish() else writer.abort()
            } catch (error: Throwable) {
                if (state.writerError == null) state.writerError = describe(error)
                writer.stats()
            }
        }
    }

    /**
     * The consumer thread of a camera check or a benchmark session: takes every published slot and
     * releases it, nothing written. With a [compressor] each slot is encoded first, on this
     * thread as the recorder's writer does, and the output discarded.
     */
    private fun consumerLoop(ring: RawVideoRing, state: LiveState, compressor: RawFrameCompressor?) {
        while (true) {
            val slot = ring.take(WRITER_POLL_MS)
            if (slot == null) {
                if (state.writerStop && ring.queuedSlots == 0) break
                continue
            }
            try {
                if (compressor != null && state.writerError == null && !state.discard) {
                    val header = requireNotNull(slot.header)
                    val (stored, _) = compressor.compress(header, slot.buffer)
                    val index = state.encodes++
                    if (index < state.encodeNs.length()) state.encodeNs.set(index, compressor.lastEncodeNs)
                    if (stored.dataLength > 0) state.minFrameRatio = minOf(state.minFrameRatio, header.dataLength.toDouble() / stored.dataLength)
                }
                state.framesWritten++
            } catch (error: Throwable) {
                if (state.writerError == null) state.writerError = describe(error)
            } finally {
                ring.release(slot)
            }
        }
    }

    /** What every kind of session measured the same way. */
    private class SessionProbe(
        val run: RawQualificationRun,
        val kind: SessionKind,
        val measureSeconds: Int,
        val aeRange: Range<Int>?,
        val qtiFrameRateOverride: Boolean,
        val thermalBefore: Int,
        /** How long the session waited for thermal headroom before it started. */
        val thermalWaitMs: Long,
        val thermalAfter: Int,
        val thermalPeak: Int,
        val declaredMinFrameDurationNs: Long,
        val endedEarly: Boolean,
        val consumerAlive: Boolean,
        val drainMs: Long,
        /** The rate the camera must hold: the target, or the reduced rate its evidence achieved (an encode benchmark's). */
        val expectedFps: Double,
    )

    /** The measured window of a session: delivered rate and the captures completed inside it. */
    private class Window(val handled: Int, val intervals: List<Long>, val deliveredFps: Double, val durationMs: Long, val completedInWindow: Long) {
        val medianIntervalNs: Long get() = intervals.getOrNull(intervals.size / 2) ?: 0L
    }

    private fun window(state: LiveState): Window {
        val startTs = state.startTs
        val endTs = state.endTs
        val handled = minOf(state.handled, state.imageTs.length())
        val intervals = (1 until handled).map { state.imageTs.get(it) - state.imageTs.get(it - 1) }.sorted()
        val medianIntervalNs = intervals.getOrNull(intervals.size / 2) ?: 0L
        val spanNs = if (startTs >= 0 && endTs > startTs) endTs - startTs else 0L
        val deliveredFps = if (spanNs > 0) (state.handled - 1) * 1e9 / spanNs else 0.0
        val captures = minOf(state.captureCount.get(), state.captureTs.length())
        val completedInWindow = if (startTs < 0) 0L else
            (0 until captures).count { state.captureTs.get(it) in startTs..endTs }.toLong()
        return Window(handled, intervals, deliveredFps, (spanNs + medianIntervalNs) / 1_000_000L, completedInWindow)
    }

    /** The camera reasons of a session: frame rate, the HAL's limit or no frames, and the heat. */
    private fun cameraReasons(probe: SessionProbe, state: LiveState, window: Window): List<String> {
        val config = probe.run.config
        val reasons = mutableListOf<String>()
        if (RawQualificationFrameRate.fpsNotReached(window.deliveredFps, config.fps)) reasons += "fps-not-reached"
        reasons += RawQualificationFrameRate.cameraReasons(probe.declaredMinFrameDurationNs, config.fps, window.deliveredFps, state.imageIndex,
            probe.qtiFrameRateOverride)
        reasons += RawQualificationStaging.thermalReasons(probe.thermalPeak)
        return reasons
    }

    /** [codes] with a reduced rate made informational when the session allows it ([RawQualificationStaging.reducedRateCodes]). */
    private fun reducedRateCodes(codes: List<String>, probe: SessionProbe, state: LiveState, window: Window): List<String> =
        RawQualificationStaging.reducedRateCodes(codes, window.deliveredFps, probe.thermalPeak, state.captureFailed.get(), state.bufferLost.get())

    /** The compact camera evidence a record keeps ([RawVideoQualificationRecord.evidence]). */
    private fun evidenceJson(probe: SessionProbe, state: LiveState, window: Window, fd: JSONObject): JSONObject = JSONObject()
        .put("stage", probe.kind.stage)
        .put("targetFps", probe.run.config.fps)
        .put("deliveredFps", round2(window.deliveredFps))
        .put("measureSeconds", probe.measureSeconds)
        .put("framesSeen", state.handled)
        .put("completedCaptures", window.completedInWindow)
        .put("captureFailed", state.captureFailed.get())
        .put("bufferLost", state.bufferLost.get())
        .put("gaps", fd.optJSONObject("gaps")?.optInt("count") ?: 0)
        .put("lateGaps", fd.optInt("lateGaps"))
        .put("qtiFrameRateOverride", probe.qtiFrameRateOverride)
        .put("thermalPeak", probe.thermalPeak)

    /** The fields every session row shares. */
    private fun sessionJson(probe: SessionProbe, state: LiveState, ring: RawVideoRing, window: Window, reasons: List<String>): JSONObject {
        val config = probe.run.config
        val status = if (reasons.all { it in RawQualificationRisk.INFORMATIONAL_REASONS }) "PASS" else "FAIL"
        return JSONObject()
            .put("kind", if (probe.kind == SessionKind.LONG) "live" else probe.kind.stage)
            .put("stage", probe.kind.stage)
            .put("graph", GRAPH)
            .put("cameraId", config.cameraId)
            .put("spec", probe.run.spec)
            .put("sensor", "${config.sensorWidth}x${config.sensorHeight}")
            .put("crop", probe.run.cropLabel)
            .put("targetFps", config.fps)
            .put("fpsMode", probe.aeRange?.let { "ae $it" } ?: "manual")
            .put("aeRange", probe.aeRange?.let { JSONArray(listOf(it.lower, it.upper)) } ?: JSONObject.NULL)
            .put("qtiFrameRateOverride", probe.qtiFrameRateOverride)
            .put("deliveredFps", round2(window.deliveredFps))
            .put("fpsNotReached", RawQualificationFrameRate.fpsNotReached(window.deliveredFps, config.fps))
            .put("achievedFps", RawQualificationFrameRate.roundFps(window.deliveredFps))
            .put("declaredMaxFps", RawQualificationFrameRate.declaredMaxFps(probe.declaredMinFrameDurationNs)?.let { Math.round(it) } ?: JSONObject.NULL)
            .put("endedEarlyNoFrames", probe.endedEarly)
            .put("status", status)
            .put("reasonCode", reasons.firstOrNull() ?: RawThroughputBenchmark.REASON_PASS)
            .put("reasonCodes", JSONArray(reasons.ifEmpty { listOf(RawThroughputBenchmark.REASON_PASS) }))
            .put("streamBytesPerSecond", config.streamBytesPerSecond)
            .put("bytesPerFrame", config.bytesPerFrame)
            .put("liveSeconds", probe.measureSeconds)
            .put("durationMs", window.durationMs)
            .put("warmupFrames", state.warmupFrames)
            .put("framesSeen", state.handled)
            .put("framesPublished", state.published)
            .put("completedCaptures", window.completedInWindow)
            .put("completedCapturesTotal", state.captureCount.get())
            .put("lostFrames", state.lostFrames)
            .put("ringOverflow", state.overflow)
            .put("captureFailed", state.captureFailed.get())
            .put("bufferLost", state.bufferLost.get())
            .put("acquireFailures", state.acquireFailures)
            .put("copyErrors", state.copyErrors)
            .put("copyError", state.copyError ?: JSONObject.NULL)
            .put("sensorIntervalMedianMs", round2(window.medianIntervalNs / 1e6))
            .put("sensorIntervalMaxMs", round2((window.intervals.lastOrNull() ?: 0L) / 1e6))
            .put("ringSlots", ring.slotCount)
            .put("peakRingSlots", state.peakSlots)
            .put("peakRingFraction", round2(state.peakSlots.toDouble() / ring.slotCount))
            .put("ringPoisoned", probe.consumerAlive || ring.usedSlots != 0)
            .put("drainMs", probe.drainMs)
            .put("thermalBefore", probe.thermalBefore)
            .put("thermalWaitMs", probe.thermalWaitMs)
            .put("thermalAfter", probe.thermalAfter)
            .put("thermalPeak", probe.thermalPeak)
            .put("encoding", config.encoding)
            .put("processCpuCores", round2(state.processCpuCores))
            .put("cpuCores", Runtime.getRuntime().availableProcessors())
    }

    /**
     * A RAM-only camera check: the camera's frame rate (with the HAL's declared limit and the vendor
     * override exemption, as a long run), every completed capture of the window reaching the ring,
     * no copy error, and no SEVERE heat. Nothing about the storage.
     */
    private fun cameraCheckResult(probe: SessionProbe, state: LiveState, ring: RawVideoRing): LiveOutcome {
        val window = window(state)
        val reasons = cameraReasons(probe, state, window).toMutableList()
        if (state.handled > 0 && state.published != window.completedInWindow) reasons += "camera-frame-loss"
        if (state.copyErrors > 0) reasons += "copy-error"
        if (state.overflow) reasons += "consumer-overflow"
        if (probe.consumerAlive) reasons += "consumer-hung"
        if (state.timestampOverflow) reasons += "timestamp-capacity-exceeded"
        val codes = reducedRateCodes(RawQualificationFrameRate.primaryFirst(reasons.distinct()), probe, state, window)
        val fd = frameDiagnosticsJson(probe.run.config.fps, window.handled, state)
        val json = sessionJson(probe, state, ring, window, codes)
            .put("frameDiagnostics", fd)
            .put("evidence", evidenceJson(probe, state, window, fd))
        return LiveOutcome(json, null)
    }

    /**
     * One encode benchmark session: every frame of the window encoded from the ring by [bench] on one
     * consumer thread, as the recorder's writer does, and discarded. Reports encode ms mean/p95/max
     * and per quarter, the sustained rate (the slowest quarter's), pixels/s, the aggregate and the
     * worst single-frame ratio, process CPU cores, the thermal status and battery temperature at the
     * start and end, and the frames dropped because the encoder fell behind. SEVERE heat fails it.
     */
    private fun benchResult(
        probe: SessionProbe,
        state: LiveState,
        ring: RawVideoRing,
        bench: EncodeSession,
        batteryBefore: Double?,
        batteryAfter: Double?,
    ): LiveOutcome {
        val config = probe.run.config
        val window = window(state)
        val count = minOf(state.encodes, state.encodeNs.length())
        val encodeNs = LongArray(count) { state.encodeNs.get(it) }
        val quarters = RawQualificationStaging.quarterMeans(encodeNs)
        val sustainedFps = RawQualificationStaging.sustainedEncodeFps(quarters)
        val pixelsPerFrame = config.cropWidth.toLong() * config.cropHeight
        val minRatio = state.minFrameRatio.takeIf { it != Double.MAX_VALUE }
        val cameraHeld = RawQualificationStaging.benchCameraHeld(window.deliveredFps, probe.expectedFps, state.handled.toLong(), window.completedInWindow)
        val reasons = RawQualificationStaging.thermalReasons(probe.thermalPeak).toMutableList()
        if (!cameraHeld) reasons += RawQualificationStaging.REASON_COMPRESSED_CAMERA_STARVED
        if (state.writerError != null) reasons += "encoder-error"
        if (count == 0) reasons += RawQualificationStaging.REASON_COMPRESSED_BENCH_MISSING
        val json = sessionJson(probe, state, ring, window, reasons)
            .put("cameraHeld", cameraHeld)
            .put("expectedFps", round2(probe.expectedFps))
            .put("encoding", bench.encoding)
            .put("threads", bench.threads)
            .put("strips", bench.strips)
            .put("encode", encodeJson(bench.compressor, state, bench.threads, bench.strips))
            .put("encodeQuarterMeanNs", JSONArray(quarters))
            .put("encodeQuarterMeanMs", JSONArray(quarters.map { round2(it / 1e6) }))
            .put("sustainedFps", sustainedFps?.let(::round2) ?: JSONObject.NULL)
            .put("pixelsPerSecond", sustainedFps?.let { Math.round(it * pixelsPerFrame) } ?: JSONObject.NULL)
            .put("minFrameRatio", minRatio?.let { Math.round(it * 1000.0) / 1000.0 } ?: JSONObject.NULL)
            .put("conservativeRatio", RawQualificationStaging.conservativeRatio(minRatio))
            .put("droppedFrames", state.lostFrames)
            .put("queuedSlotsAtStop", state.queuedAtStop)
            .put("encoderError", state.writerError ?: JSONObject.NULL)
            .put("batteryTemperatureBeforeC", batteryBefore ?: JSONObject.NULL)
            .put("batteryTemperatureAfterC", batteryAfter ?: JSONObject.NULL)
        json.put("evidence", JSONObject()
            .put("stage", SessionKind.ENCODE.stage)
            .put("spec", probe.run.spec)
            .put("encoding", bench.encoding)
            .put("cameraHeld", cameraHeld)
            .put("deliveredFps", json.opt("deliveredFps"))
            .put("threads", bench.threads)
            .put("strips", bench.strips)
            .put("sustainedFps", json.opt("sustainedFps"))
            .put("pixelsPerSecond", json.opt("pixelsPerSecond"))
            .put("aggregateRatio", bench.compressor.compressionRatio?.let { Math.round(it * 1000.0) / 1000.0 } ?: JSONObject.NULL)
            .put("conservativeRatio", RawQualificationStaging.conservativeRatio(minRatio))
            .put("thermalPeak", probe.thermalPeak))
        return LiveOutcome(json, null)
    }

    private fun liveResult(
        probe: SessionProbe,
        state: LiveState,
        ring: RawVideoRing,
        effectiveRingBytes: Long,
        sweep: RawStorageSweep,
        scan: Result<OcrawStreamScan>?,
        compressor: RawFrameCompressor? = null,
    ): LiveOutcome {
        val run = probe.run
        val writerAlive = probe.consumerAlive
        val config = run.config
        val w = window(state)
        val handled = w.handled
        val durationMs = w.durationMs
        val completedInWindow = w.completedInWindow
        val stats = state.writerStats
        val framesWritten = stats?.framesWritten ?: state.framesWritten
        val peakRingBytes = state.peakSlots.toLong() * config.bytesPerFrame
        val live = RawLiveRun(
            config = config,
            durationMs = durationMs,
            completedCaptures = completedInWindow,
            framesWritten = framesWritten,
            ringOverflow = state.overflow,
            peakRingBytes = peakRingBytes,
            ringBytes = effectiveRingBytes,
            maxWriteMs = stats?.maxWriteMs,
        )
        // A compressed long run's storage margins use its own worst frame's ratio, floored, as the benchmark's do.
        val minFrameRatio = state.minFrameRatio.takeIf { compressor != null && it != Double.MAX_VALUE }
        val runRatio = if (compressor == null) 1.0 else RawQualificationStaging.conservativeRatio(minFrameRatio)
        val v2 = RawThroughputBenchmark.qualifyWithBuffer(config, effectiveRingBytes, sweep, live, runRatio)
        val v1 = RawThroughputBenchmark.qualifyWithoutBuffer(sweep.totalDurationMs / 1_000L, config.streamBytesPerSecond, sweep.samples)

        val scanned = scan?.getOrNull()
        val extraReasons = cameraReasons(probe, state, w).toMutableList()
        if (state.writerError != null) extraReasons += "writer-error"
        if (writerAlive) extraReasons += "writer-hung"
        if (scanned == null || scanned.frameCount.toLong() != framesWritten || !scanned.cleanlyClosed) extraReasons += "ocraw-scan-mismatch"
        if (state.timestampOverflow) extraReasons += "timestamp-capacity-exceeded"
        // A shortfall while the ring overflowed waits for the camera check (storageLoadReclassify); any other may be a reduced rate.
        val v2Codes = v2.reasonCodes.filter { it != RawThroughputBenchmark.REASON_PASS }
        val extras = if (RawQualificationStaging.needsStorageLoadCheck(v2Codes + extraReasons)) extraReasons.toList()
            else reducedRateCodes(extraReasons, probe, state, w)
        // One sustained rule for every verdict: the rate this configuration reaches the storage at, past the write cache.
        val achievedFps = RawQualificationFrameRate.roundFps(w.deliveredFps)
        // The bytes a stored frame took on the file (headers and indexes included) at the rate the camera delivered.
        val storedBytesPerSecond = if (compressor == null) null else {
            val bytes = stats?.bytesWritten ?: compressor.storedBytes
            val frames = stats?.framesWritten ?: (compressor.framesEncoded + compressor.framesStoredUncompressed)
            if (bytes > 0 && frames > 0) kotlin.math.ceil(bytes.toDouble() / frames * achievedFps).toLong() else null
        }
        val effectiveStream = RawQualificationFrameRate.effectiveStreamBytesPerSecond(config, achievedFps.takeIf { it > 0.0 })
        val storageBytesPerSecond = storedBytesPerSecond ?: effectiveStream
        val sustainedReasons = RawQualificationStaging.sustainedRateReasons(storageBytesPerSecond, sweep.sustained)
        val allExtras = extras + sustainedReasons
        // What the writer achieved while it wrote (forces included), and whether the storage fell behind the stream.
        val writerBytesPerSecond = stats?.let { s ->
            val busyNs = s.totalWriteNanos + s.totalForceNanos
            when {
                s.bytesWritten <= 0 -> null
                busyNs > 0 -> (s.bytesWritten * 1e9 / busyNs).toLong()
                durationMs > 0 -> s.bytesWritten * 1_000L / durationMs
                else -> null
            }
        }
        val fellBehind = (v2Codes + allExtras).any {
            it == RawThroughputBenchmark.REASON_RING_OVERFLOW || it == RawThroughputBenchmark.REASON_RING_PEAK_EXCEEDED
        }
        val reasons = RawQualificationFrameRate.primaryFirst((v2Codes + allExtras).distinct())
        val status = when {
            allExtras.any { it !in RawQualificationRisk.INFORMATIONAL_REASONS } -> "FAIL"
            else -> v2.status.name
        }
        val method = if (compressor != null) RawQualificationMethods.COMPRESSED_LONG_RUN else RawQualificationMethods.LONG_RUN
        val measuredAt = System.currentTimeMillis()
        val v2Record = RawVideoQualificationRecord(
            fingerprint = Build.FINGERPRINT,
            destinationId = destinationId,
            config = config,
            ringBytes = effectiveRingBytes,
            qualification = v2,
            measuredAtEpochMs = measuredAt,
        )
        val fd = frameDiagnosticsJson(config.fps, handled, state)
        val evidence = evidenceJson(probe, state, w, fd)
        if (compressor != null) {
            evidence.put("encoding", config.encoding)
                .put("storedBytesPerSecond", storedBytesPerSecond ?: JSONObject.NULL)
                .put("aggregateRatio", compressor.compressionRatio?.let { Math.round(it * 1000.0) / 1000.0 } ?: JSONObject.NULL)
                .put("conservativeRatio", runRatio)
        }
        val burstRatio = RawQualificationStaging.measuredRatio(effectiveStream, storedBytesPerSecond)
        val stored = RawQualificationRecords.withBurstSeconds(
            RawQualificationRecords.forRun(Build.FINGERPRINT, destinationId, config, effectiveRingBytes, v2, allExtras, measuredAt,
                method, null, evidence.toString(), achievedFps),
            sweep.sustained, burstRatio,
        )
        val json = sessionJson(probe, state, ring, w, reasons)
            .put("status", status)
            .put("method", method)
            .put("achievedFps", achievedFps)
            .put("sustainedReasons", JSONArray(sustainedReasons))
            .put("writerBytesPerSecond", writerBytesPerSecond ?: JSONObject.NULL)
            .put("fellBehind", fellBehind)
            .put("storageBytesPerSecond", storageBytesPerSecond)
            .put("burstRatio", burstRatio)
            .put("burstSeconds", stored.burstSeconds ?: JSONObject.NULL)
            .put("framesWritten", framesWritten)
            .put("highWaterReached", state.highWaterReached)
            .put("copyMaxMs", round2(state.copyMaxNs / 1e6))
            .put("copyMeanMs", if (state.copies > 0) round2(state.copyTotalNs / 1e6 / state.copies) else JSONObject.NULL)
            .put("rowStride", state.rowStride)
            .put("ringAllocatedBytes", ring.capacityBytes)
            .put("ringBytes", effectiveRingBytes)
            .put("peakRingBytes", peakRingBytes)
            .put("writer", stats?.let { s ->
                JSONObject()
                    .put("bytesWritten", s.bytesWritten)
                    .put("framesWritten", s.framesWritten)
                    .put("maxWriteMs", round2(s.maxWriteMs))
                    .put("meanWriteMs", if (s.chunksWritten > 0) round2(s.totalWriteNanos / 1e6 / s.chunksWritten) else JSONObject.NULL)
                    .put("forceCount", s.forceCount)
                    .put("maxForceMs", round2(s.maxForceMs))
                    .put("meanForceMs", if (s.forceCount > 0) round2(s.totalForceNanos / 1e6 / s.forceCount) else JSONObject.NULL)
                    .put("periodicIndexCount", s.periodicIndexCount)
                    .put("finalIndexWritten", s.finalIndexWritten)
                    .put("endWritten", s.endWritten)
            } ?: JSONObject.NULL)
            .put("writerError", state.writerError ?: JSONObject.NULL)
            .put("writerHung", writerAlive)
            .put("scan", when {
                scanned != null -> JSONObject()
                    .put("frameCount", scanned.frameCount)
                    .put("cleanlyClosed", scanned.cleanlyClosed)
                    .put("chunkCount", scanned.chunkCount)
                    .put("fileSize", scanned.fileSize)
                    .put("errors", JSONArray(scanned.errors.take(5)))
                    .put("matchesWritten", scanned.frameCount.toLong() == framesWritten)
                scan != null -> JSONObject().put("error", describe(scan.exceptionOrNull()!!))
                else -> JSONObject.NULL
            })
            .put("analysisFrames", JSONObject.NULL)
            .put("compressed", compressor?.let { c ->
                JSONObject()
                    .put("encoding", config.encoding)
                    .put("framesEncoded", c.framesEncoded)
                    .put("framesStoredUncompressed", c.framesStoredUncompressed)
                    .put("uncompressedBytes", c.uncompressedBytes)
                    .put("storedBytes", c.storedBytes)
                    .put("storedBytesPerSecond", storedBytesPerSecond ?: JSONObject.NULL)
                    .put("aggregateRatio", c.compressionRatio?.let { Math.round(it * 1000.0) / 1000.0 } ?: JSONObject.NULL)
                    .put("minFrameRatio", minFrameRatio?.let { Math.round(it * 1000.0) / 1000.0 } ?: JSONObject.NULL)
                    .put("conservativeRatio", runRatio)
                    .put("encodeMeanMs", c.encodeMeanMs?.let(::round2) ?: JSONObject.NULL)
                    .put("encodeMaxMs", round2(c.encodeMaxMs))
            } ?: JSONObject.NULL)
            .put("frameDiagnostics", fd)
            .put("evidence", evidence)
            .put("qualificationV2", JSONObject(RawVideoQualificationCodec.encodeRecord(v2Record).toString()))
            .put("qualificationV1", v1Json(v1))
        return LiveOutcome(json, stored)
    }

    /** The run's [RawFrameDiagnostics] (evidence only), from the reader and camera threads' arrays; both are idle by now. */
    private fun frameDiagnosticsJson(fps: Int, handled: Int, state: LiveState): JSONObject {
        fun AtomicLongArray.toArray(count: Int) = LongArray(count.coerceIn(0, length())) { get(it) }
        val captures = minOf(state.measuredCaptures.get(), state.captureExposureNs.length())
        val copies = minOf(state.copies, state.copyNs.length().toLong()).toInt()
        val cpuStart = state.readerCpuStartNs
        val cpuEnd = state.readerCpuEndNs
        val d = RawFrameDiagnosticsCalc.of(
            fps = fps,
            imageTs = state.imageTs.toArray(handled),
            imageCount = handled,
            exposureNs = state.captureExposureNs.toArray(captures),
            sensitivity = state.captureSensitivity.toArray(captures),
            frameDurationNs = state.captureFrameDurationNs.toArray(captures),
            captureCount = captures,
            copyNs = state.copyNs.toArray(copies),
            copyCount = copies,
            readerCpuNs = if (cpuStart >= 0 && cpuEnd >= cpuStart) cpuEnd - cpuStart else null,
            readerWallNs = (state.readerWallEndNs - state.readerWallStartNs).takeIf { state.readerWallStartNs >= 0 && it > 0 },
        )
        fun ms(ns: Long) = round2(ns / 1e6)
        fun statJson(stat: RawFrameStat?, scale: (Long) -> Any): Any = stat?.let {
            JSONObject().put("min", scale(it.min)).put("median", scale(it.median)).put("max", scale(it.max))
        } ?: JSONObject.NULL
        val gaps = d.gaps
        return JSONObject()
            .put("nominalFrameMs", ms(d.nominalFrameNs))
            .put("gaps", JSONObject()
                .put("count", gaps.gapCount)
                .put("upTo1_25x", gaps.upTo1_25)
                .put("upTo2_25x", gaps.upTo2_25)
                .put("upTo3_25x", gaps.upTo3_25)
                .put("larger", gaps.larger))
            .put("lateGaps", gaps.late)
            .put("firstLateGaps", JSONArray(gaps.firstLate.map {
                JSONObject().put("index", it.index).put("atMs", ms(it.offsetNs)).put("gapMs", ms(it.gapNs))
            }))
            .put("lateGapsByQuarter", JSONArray(gaps.lateByQuarter.toList()))
            .put("captureResults", d.captureResults)
            .put("exposureMs", statJson(d.exposureNs, ::ms))
            .put("sensitivity", statJson(d.sensitivity) { it })
            .put("frameDurationMs", statJson(d.frameDurationNs, ::ms))
            .put("exposureOverFrame", d.exposureOverFrame)
            .put("copyP50Ms", d.copyP50Ns?.let(::ms) ?: JSONObject.NULL)
            .put("copyP99Ms", d.copyP99Ns?.let(::ms) ?: JSONObject.NULL)
            .put("readerCpuCores", d.readerCpuCores?.let(::round2) ?: JSONObject.NULL)
    }

    /** The encoder numbers of a run: codec, frames encoded or stored raw, the ratio, and encode ms mean/p95/max. */
    private fun encodeJson(compressor: RawFrameCompressor, state: LiveState, threads: Int, strips: Int): JSONObject {
        val count = minOf(state.encodes, state.encodeNs.length())
        val sorted = LongArray(count) { state.encodeNs.get(it) }.also { it.sort() }
        val p95 = if (count > 0) sorted[minOf(count - 1, (count * 95 + 99) / 100 - 1)] / 1e6 else null
        return JSONObject()
            .put("codec", codecName(compressor.encoding))
            .put("threads", threads)
            .put("strips", strips)
            .put("predictor", if (compressor.encoding == OcrawFrameHeader.ENCODING_LJ92) RawFrameEncoders.LJ92_PREDICTOR else JSONObject.NULL)
            .put("framesEncoded", compressor.framesEncoded)
            .put("framesStoredUncompressed", compressor.framesStoredUncompressed)
            .put("uncompressedBytes", compressor.uncompressedBytes)
            .put("storedBytes", compressor.storedBytes)
            .put("ratio", compressor.compressionRatio?.let { Math.round(it * 1000.0) / 1000.0 } ?: JSONObject.NULL)
            .put("encodeMeanMs", compressor.encodeMeanMs?.let(::round2) ?: JSONObject.NULL)
            .put("encodeP95Ms", p95?.let(::round2) ?: JSONObject.NULL)
            .put("encodeMaxMs", round2(compressor.encodeMaxMs))
    }

    /** The report name of an OCRAW frame encoding: the [RawVideoConfig] encoding names (`bpq` for near-lossless). */
    private fun codecName(encoding: Int): String = when (encoding) {
        OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK -> RawVideoConfig.ENCODING_BLOCK_PACK
        OcrawFrameHeader.ENCODING_LJ92 -> RawVideoConfig.ENCODING_LJ92
        OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED -> RawFrameEncoders.ENCODING_NEAR_LOSSLESS
        else -> RawVideoConfig.ENCODING_RAW10
    }

    private fun skipped(run: RawQualificationRun, reason: String, kind: SessionKind = SessionKind.LONG): JSONObject = JSONObject()
        .put("kind", if (kind == SessionKind.LONG) "live" else kind.stage)
        .put("stage", kind.stage)
        .put("graph", GRAPH)
        .put("cameraId", run.config.cameraId)
        .put("spec", run.spec)
        .put("crop", run.cropLabel)
        .put("targetFps", run.config.fps)
        .put("encoding", run.config.encoding)
        .put("streamBytesPerSecond", run.config.streamBytesPerSecond)
        .put("liveSeconds", kind.measureSeconds(run.config))
        .put("neededBytes", if (kind == SessionKind.LONG) timing.neededBytes(run.config) else 0L)
        .put("status", "UNKNOWN")
        .put("reasonCode", reason)
        .put("reasonCodes", JSONArray(listOf(reason)))

    // endregion

    // region metadata

    /** The first `METADATA` chunk, with the keys and value formats of `docs/formats/opencine-raw-v1.md`. */
    private fun staticMetadata(camera: CameraInfo, run: RawQualificationRun): Map<String, String> {
        val config = run.config
        val appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
        val crop = RawVideoCrop(run.cropLeft, run.cropTop, config.cropWidth, config.cropHeight)
        return RawVideoMetadata.baseValues(camera.id, camera.size.width, camera.size.height, crop, config.fps, Build.MODEL, appVersion) +
            ("fingerprint" to Build.FINGERPRINT) + RawVideoMetadata.staticValues(camera.characteristics)
    }

    // endregion

    // region cancellation, progress, helpers

    /** Thrown on [run]'s thread once [cancel] was called; caught by [run]. */
    private class QualificationCancelled : RuntimeException("RAW video qualification cancelled") {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw QualificationCancelled()
    }

    /**
     * Sleeps [ms] in short slices, reporting [phase] progress and sampling the thermal status into
     * [thermalPeak] every [THERMAL_SAMPLE_MS].
     */
    private fun sleepCancellable(ms: Long, phase: RawQualificationPhase, index: Int, runCount: Int, spec: String?) {
        val start = SystemClock.elapsedRealtime()
        var lastProgress = start
        var lastThermal = start
        while (true) {
            checkCancelled()
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - start
            if (elapsed >= ms) return
            if (now - lastThermal >= THERMAL_SAMPLE_MS) {
                lastThermal = now
                runCatching { thermalPeak = maxOf(thermalPeak, thermalStatus()) }
            }
            if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                lastProgress = now
                progress(phase, index, runCount, spec)
            }
            SystemClock.sleep(minOf(SLEEP_SLICE_MS, ms - elapsed))
        }
    }

    /** Awaits [latch] up to [timeoutSeconds]; false on timeout. */
    private fun awaitCancellable(latch: CountDownLatch, timeoutSeconds: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1_000L
        while (true) {
            checkCancelled()
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) return latch.count == 0L
            if (latch.await(minOf(SLEEP_SLICE_MS, left), TimeUnit.MILLISECONDS)) return true
        }
    }

    /** Joins [thread] up to [timeoutMs], calling [onProgress] every [PROGRESS_INTERVAL_MS] meanwhile. */
    private fun joinCancellable(thread: Thread, timeoutMs: Long, onProgress: () -> Unit = {}) {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + timeoutMs
        var lastProgress = start
        while (thread.isAlive) {
            checkCancelled()
            val now = SystemClock.elapsedRealtime()
            val left = deadline - now
            if (left <= 0) return
            if (now - lastProgress >= PROGRESS_INTERVAL_MS) { lastProgress = now; onProgress() }
            thread.join(minOf(SLEEP_SLICE_MS, left))
        }
    }

    /** Waits until [handler]'s thread has run everything queued before this call. */
    private fun awaitIdle(handler: Handler, timeoutMs: Long) {
        val idle = CountDownLatch(1)
        if (handler.post { idle.countDown() }) runCatching { idle.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun coolDown(index: Int, runCount: Int) {
        meter.startStep(RawQualificationStepKind.COOL_DOWN, RawQualificationTiming.COOL_DOWN_MS)
        progress(RawQualificationPhase.COOLING, index = index, count = runCount)
        val start = SystemClock.elapsedRealtime()
        while (true) {
            checkCancelled()
            val elapsed = SystemClock.elapsedRealtime() - start
            if (elapsed >= RawQualificationTiming.COOL_DOWN_MS) break
            SystemClock.sleep(minOf(SLEEP_SLICE_MS, RawQualificationTiming.COOL_DOWN_MS - elapsed))
        }
        finishStep()
    }

    /** One progress report, its fraction and time left from [meter]. */
    private fun progress(phase: RawQualificationPhase, index: Int = 0, count: Int = 0, spec: String? = null) {
        val fps = if (phase == RawQualificationPhase.SWEEP) null else liveFps()
        val fraction = meter.fraction()
        runCatching { listener.onProgress(RawQualificationProgress(phase, fraction, index, count, spec, fps, meter.remainingMs())) }
            .onFailure { Log.w(TAG, "progress listener failed", it) }
    }

    /**
     * Qualification thread: the rate the current camera session delivers over the last two seconds of
     * measured frames ([RawQualificationLiveRate.fps]), recomputed about once a second; null outside a
     * session, in its warm-up and once it stops producing. The reader thread writes the timestamps;
     * entries it has counted but not yet written read 0 and are left out.
     */
    private fun liveFps(): Double? {
        val state = liveSession
        if (state == null || !state.measuring || state.producerStop) {
            liveFpsOf = null
            liveFpsValue = null
            return null
        }
        val now = SystemClock.elapsedRealtime()
        if (liveFpsOf === state && now - liveFpsAtMs < RawQualificationLiveRate.INTERVAL_MS) return liveFpsValue
        val count = minOf(state.handled, state.imageTs.length())
        val lastWallNs = state.readerWallEndNs
        val sinceLastNs = if (lastWallNs >= 0) (SystemClock.elapsedRealtimeNanos() - lastWallNs).coerceAtLeast(0L) else 0L
        val fps = RawQualificationLiveRate.fps(count, state.imageTs::get, sinceLastNs = sinceLastNs)
            ?.let(RawQualificationFrameRate::roundFps)
        liveFpsOf = state
        liveFpsAtMs = now
        liveFpsValue = fps
        return fps
    }

    private fun emit(result: JSONObject) {
        runCatching { listener.onResult(result) }.onFailure { Log.w(TAG, "result listener failed", it) }
    }

    /**
     * Runs [action] once [writer] has ended: now when it has (or there is none), else on a daemon
     * thread that waits for it, so memory or a descriptor a hung writer still uses is never freed under it.
     */
    private fun afterWriter(writer: Thread?, action: () -> Unit) {
        if (writer == null || !writer.isAlive) { action(); return }
        Log.w(TAG, "writer still running; deferring its cleanup")
        Thread({ runCatching { writer.join() }; action() }, "occ-raw-cleanup").apply { isDaemon = true }.start()
    }

    /** Releases the encoders and the ring memory (after the last writer) and deletes every row left. */
    private fun release() {
        val encoders = synchronized(benchEncoders) { benchEncoders.toList().also { benchEncoders.clear() } }
        afterWriter(lastWriter) {
            encoders.forEach { runCatching { it.close() } }
            slotMemory.close()
        }
        val left = synchronized(rows) { rows.toList() }
        left.forEach(::deleteRow)
    }

    private fun insertRow(label: String): Uri {
        val uri = privateDirectory?.let { directory ->
            Uri.fromFile(File(directory, "occ-qualify-$label-${SystemClock.elapsedRealtime()}.ocraw").apply { createNewFile() })
        } ?: driveScratch?.create(label) ?: RawVideoQualificationRows.insert(context, label)
        return uri.also { synchronized(rows) { rows += it } }
    }

    private fun deleteRow(uri: Uri) {
        when {
            uri.scheme == "file" -> uri.path?.let { File(it).delete() }
            driveScratch != null && uri.authority == driveScratch.tree.authority -> driveScratch.delete(uri)
            else -> RawVideoQualificationRows.delete(context, uri)
        }
        synchronized(rows) { rows -= uri }
    }

    /** A drive whose provider gives no figure reads as full: nothing is written blind. */
    private fun freeBytes(): Long = driveScratch?.let { it.freeBytes() ?: 0L }
        ?: StatFs((privateDirectory ?: requireNotNull(context.getExternalFilesDir(null))).path).availableBytes

    private fun thermalStatus(): Int = context.getSystemService(PowerManager::class.java).currentThermalStatus

    /** The battery temperature in °C from the sticky battery broadcast, or null when unknown. */
    private fun batteryTemperatureC(): Double? = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10.0 }
    }.getOrNull()

    /** The thermal status a session starts at and how long it waited for it. */
    private class ThermalWait(val status: Int, val waitedMs: Long)

    /**
     * Waits up to [maxWaitMs] for the thermal status to drop below [below], showing
     * [RawQualificationPhase.THERMAL_WAIT] meanwhile; returns the last status and the time waited.
     */
    private fun waitForThermalHeadroom(index: Int, runCount: Int, spec: String, below: Int, maxWaitMs: Long): ThermalWait {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + maxWaitMs
        var status = thermalStatus()
        // The wait is not the session's work: the meter holds the step's time meanwhile.
        if (status >= below) meter.holdStep()
        try {
            while (status >= below && SystemClock.elapsedRealtime() < deadline) {
                Log.i(TAG, "thermal status $status, waiting")
                progress(RawQualificationPhase.THERMAL_WAIT, index = index, count = runCount, spec = spec)
                val until = SystemClock.elapsedRealtime() + THERMAL_POLL_MS
                while (SystemClock.elapsedRealtime() < until) { checkCancelled(); SystemClock.sleep(SLEEP_SLICE_MS) }
                status = thermalStatus()
            }
        } finally {
            meter.resumeStep()
        }
        return ThermalWait(status, SystemClock.elapsedRealtime() - start)
    }

    private fun randomUuid(): ByteArray {
        val uuid = UUID.randomUUID()
        return ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    }

    private fun samplesJson(samples: List<RawThroughputSample>) = JSONArray().apply {
        samples.forEach { put(JSONObject().put("durationMs", it.durationMs).put("bytesWritten", it.bytesWritten)) }
    }

    private fun v1Json(q: RawThroughputQualification) = JSONObject()
        .put("status", q.status.name)
        .put("reasonCode", q.reasonCode)
        .put("windowSeconds", q.windowSeconds)
        .put("streamBytesPerSecond", q.streamBytesPerSecond)
        .put("p01BytesPerSecond", q.p01BytesPerSecond ?: JSONObject.NULL)
        .put("requiredBytesPerSecond", q.requiredBytesPerSecond ?: JSONObject.NULL)

    private fun baseReport() = JSONObject()
        .put("schema", SCHEMA)
        .put("protocolVersion", RawThroughputBenchmark.PROTOCOL_VERSION)
        .put("collectedAtEpochMs", System.currentTimeMillis())
        .put("destinationId", destinationId)
        .put("format", "RAW10")
        .put("device", JSONObject().put("fingerprint", Build.FINGERPRINT).put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL))
        .put("rawVideoPromotion", "not evaluated")

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    private fun describe(error: Throwable) = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(180)}"

    // endregion

    companion object {
        private const val TAG = "OCC_RAW_QUALIFIER"
        const val SCHEMA = "opencinecam-raw-throughput-v3"
        const val REASON_EXCEPTION = "raw-throughput-exception"
        const val REASON_UNSUPPORTED = "raw-throughput-unsupported"
        const val REASON_CANCELLED = "raw-video-qualification-cancelled"
        const val REASON_STORE_FAILED = "raw-video-store-failed"
        const val REASON_CODEC_UNAVAILABLE = "raw-codec-unavailable"

        /** The sweep found the write cache still flushing ([RawStorageRest]): the run stopped after it and stored nothing. */
        const val REASON_STORAGE_NOT_RESTED = "storage-not-rested"
        const val DESTINATION_ID = RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS

        /** The destination id of a run with a private directory (device-test diagnostics only). */
        const val DESTINATION_APP_PRIVATE = "app-private"

        /** The `graph` of every run: the app's RAW_VIDEO session (viewfinder + RAW10). */
        const val GRAPH = "raw-video-session"

        /** The sweep rewinds within this many bytes when the volume lacks room for an unbounded minute. */
        const val SWEEP_WRAP_BYTES = 4L shl 30

        /**
         * Whether the sweep's sustained phase may write a chunk ending at [nextEndPosition]: always when
         * it rewinds within [wrapAt] (its row never grows past it), otherwise only while the row leaves
         * [RawQualificationTiming.SPACE_RESERVE_BYTES] of the [freeBytesAtStart] free.
         */
        internal fun sustainedRoomLeft(wrapAt: Long, nextEndPosition: Long, freeBytesAtStart: Long): Boolean =
            wrapAt != Long.MAX_VALUE || nextEndPosition <= freeBytesAtStart - RawQualificationTiming.SPACE_RESERVE_BYTES

        const val THERMAL_WAIT_MS = 60_000L

        /** The longest an encode benchmark or a long run waits for the status to drop below MODERATE. */
        const val COOL_WAIT_MS = 180_000L
        private const val THERMAL_POLL_MS = 2_000L
        private const val TIMEOUT_SECONDS = 15L
        private const val NANOS = 1_000_000_000L
        private const val WRITER_POLL_MS = 100L
        private const val WRITER_JOIN_MS = RawQualificationTiming.DRAIN_MAX_MS

        /** How long cancellation waits for each of the reader, the writer and the camera to let go. */
        private const val ABORT_WAIT_MS = 500L
        private const val SLEEP_SLICE_MS = 100L
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val THERMAL_SAMPLE_MS = 1_000L
        private const val FORCE_EVERY_BYTES = 256L * 1024 * 1024

        /** Upper bound assumed when deciding whether an unbounded sweep fits the volume. */
        private const val SWEEP_ASSUMED_CEILING_BYTES_PER_SECOND = 2_500_000_000L
        private const val HIGH_WATER_FRACTION = 0.9

        /** After the warm-up, how much longer a run that has seen no frame at all waits for one. */
        private const val NO_FRAMES_GRACE_MS = 2_000L
    }
}
