/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

/** A rendered take: the Gyroflow log in a temporary file and the series for a CAMM track. */
internal class MotionTakeOutputs(
    val gcsv: File,
    val result: MotionRenderResult,
    /** Null when the render was asked for the log only, or no bin fits [MotionCammBudget.MAX_PACKETS]. */
    val camm: CammSeries?,
    /** The bin [camm] was resampled to; null without it. */
    val cammBinUs: Long?,
    val synced: Boolean,
    val estimated: Boolean,
)

/**
 * Spools a take's motion samples, frame times and exposure changes to `filesDir/motion/<take id>.ocmo`, with the
 * take's description in `<take id>.json` beside it, so a take whose process died can still be rendered. It only
 * observes: every failure ends the motion data quietly and never touches the take, and no method throws.
 */
internal class MotionTakeRecorder internal constructor(
    initialInfo: MotionTakeInfo,
    val spoolFile: File,
    val infoFile: File,
    private val gcsvFile: File,
    private val writer: MotionSpoolWriter,
) {
    /** The take's description, with the sensors found once they are open (as in the `.json`). */
    @Volatile var info: MotionTakeInfo = initialInfo
        private set
    @Volatile private var failed = false
    @Volatile private var lastFlushNs = Long.MIN_VALUE
    private var source: MotionAcquisition? = null
    private var stopped = false
    private var acquisitionStopped = false
    private var lastExposureNs = -1
    private var lastSkewNs = -1

    /**
     * On the sensor thread. A failed write or flush ends the take's motion data: it is rethrown so that
     * MotionSampleSource stops the sensors and hands nothing more over; the video never depends on it.
     */
    internal val consumer = MotionSampleConsumer { kind, timestampNs, x, y, z ->
        if (failed) return@MotionSampleConsumer
        try {
            writer.sample(kind, timestampNs, x, y, z)
            // About once a second, without fsync: a crash then loses at most the last second and its page cache.
            if (lastFlushNs == Long.MIN_VALUE) lastFlushNs = timestampNs
            else if (timestampNs - lastFlushNs >= FLUSH_PERIOD_NS) {
                lastFlushNs = timestampNs
                writer.flush()
            }
        } catch (problem: Exception) {
            failed = true
            throw problem
        }
    }

    val sensors: MotionSensorSet? get() = source?.sensors

    /** A frame reached the encoder: its camera timestamp and the presentation time it was given. */
    fun onFrame(sensorTimestampNs: Long, presentationTimeNs: Long) = guard("frame") {
        writer.frame(sensorTimestampNs, presentationTimeNs)
    }

    /** The exposure in force from [fromSensorNs] (camera clock); only a change is recorded. A null skew is unknown. */
    @Synchronized
    fun onExposure(fromSensorNs: Long, exposureTimeNs: Long?, rollingShutterSkewNs: Long?) = guard("exposure") {
        if (exposureTimeNs == null || exposureTimeNs <= 0 || fromSensorNs <= 0) return@guard
        val exposure = exposureTimeNs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val skew = (rollingShutterSkewNs ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        if (exposure == lastExposureNs && skew == lastSkewNs) return@guard
        lastExposureNs = exposure
        lastSkewNs = skew
        writer.exposure(fromSensorNs, exposure, skew)
    }

    /** Hands the running sensors to the recorder, which stops them with the take. */
    @Synchronized
    internal fun attachAcquisition(acquisition: MotionAcquisition) {
        source = acquisition
    }

    /**
     * Stops the sensors without waiting, and closes the spool on their thread, keeping it for [render] or a later
     * recovery: the take is detached and no frame reaches this recorder any more, whether or not its native writer ever
     * retires. Never blocks on storage nor on the sensor thread, so the main thread may call it. Idempotent.
     */
    fun stopAcquisition() {
        val acquisition = synchronized(this) {
            if (acquisitionStopped) return
            acquisitionStopped = true
            source
        }
        if (acquisition == null) closeWriter() else acquisition.stopThen(::closeWriter)
    }

    /** Stops the sensors and closes the spool. Idempotent. */
    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        acquisitionStopped = true
        // Also after stopAcquisition: waits for the sensor thread to hand over the samples it still held, so the
        // spool's tail is whole before it is read.
        runCatching { source?.stop() }
        closeWriter()
    }

    private fun closeWriter() {
        try {
            writer.close()
        } catch (problem: Exception) {
            failed = true
            Log.w(TAG, "Motion spool close failed", problem)
        }
    }

    /**
     * Stops, then renders the spool against [timing] into the temporary Gyroflow log, adding the CAMM series when
     * [withCamm]. Null when nothing could be rendered (no timeline, no gyroscope sample on it, or a failure).
     */
    fun render(timing: MotionTakeTiming, withCamm: Boolean): MotionTakeOutputs? {
        stop()
        if (failed || writer.failed) {
            Log.w(TAG, "Motion spool incomplete; no motion data for this take")
            return null
        }
        return renderSpool(spoolFile, info, timing, gcsvFile, withCamm)
    }

    /** Stops and deletes the spool, its description and the temporary log. */
    fun discard() {
        stop()
        deleteFiles(spoolFile, infoFile, gcsvFile)
    }

    private inline fun guard(what: String, block: () -> Unit) {
        if (failed) return
        try {
            block()
        } catch (problem: Exception) {
            failed = true
            Log.w(TAG, "Motion $what failed; motion data stops for this take", problem)
        }
    }

    companion object {
        private const val TAG = "MotionData"
        const val SPOOL_DIRECTORY = "motion"
        const val GCSV_DIRECTORY = "gyroflow"
        const val SPOOL_EXTENSION = "ocmo"
        const val INFO_EXTENSION = "json"
        private const val FLUSH_PERIOD_NS = 1_000_000_000L

        /** A take id as a file name: anything but letters, digits, dot, dash and underscore becomes an underscore. */
        fun fileStem(takeId: String): String = takeId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "take" }

        /**
         * Opens the spool and the sensors and writes the take's description with the sensors found. Null when the
         * device has no gyroscope or anything fails. Never throws.
         */
        fun start(context: Context, info: MotionTakeInfo): MotionTakeRecorder? {
            val stem = fileStem(info.takeId)
            val spoolDir = File(context.filesDir, SPOOL_DIRECTORY)
            val gcsvDir = File(context.cacheDir, GCSV_DIRECTORY)
            val spool = File(spoolDir, "$stem.$SPOOL_EXTENSION")
            val json = File(spoolDir, "$stem.$INFO_EXTENSION")
            val gcsv = File(gcsvDir, "$stem.gcsv")
            var recorder: MotionTakeRecorder? = null
            return try {
                check(spoolDir.isDirectory || spoolDir.mkdirs()) { "No motion directory" }
                // Leftover logs are not swept here: the previous take may still be finalising and hold its own.
                check(gcsvDir.isDirectory || gcsvDir.mkdirs()) { "No Gyroflow directory" }
                val created = MotionTakeRecorder(info, spool, json, gcsv, openWriter(spool))
                recorder = created
                val source = MotionSampleSource.start(context, created.consumer) ?: run {
                    created.discard()
                    return null
                }
                created.attachAcquisition(source)
                val sensors = source.sensors
                val described = info.copy(
                    gyroMaxHz = sensors.gyroMaxHz,
                    accelerometer = sensors.accelerometer,
                    accelMaxHz = sensors.accelMaxHz,
                    magnetometer = sensors.magnetometer,
                    magMaxHz = sensors.magMaxHz,
                )
                writeInfo(json, described)
                created.info = described
                created
            } catch (problem: Exception) {
                Log.w(TAG, "Motion data unavailable", problem)
                recorder?.discard() ?: deleteFiles(spool, json)
                null
            }
        }

        /** A writer on a new [spool]; the stream is closed when the writer cannot be made (its header not written). */
        internal fun openWriter(spool: File, open: (File) -> OutputStream = ::FileOutputStream): MotionSpoolWriter {
            val stream = open(spool)
            return try {
                MotionSpoolWriter(stream)
            } catch (problem: Throwable) {
                runCatching { stream.close() }
                throw problem
            }
        }

        /** Writes [info] next to its spool through a temporary file, so a crash never leaves half a description. */
        fun writeInfo(target: File, info: MotionTakeInfo) {
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeText(info.toJson(), Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                temporary.delete()
                error("Could not write ${target.name}")
            }
        }

        /** Renders [spoolFile] (closed, or left by a crash) into [gcsvFile]. Null when nothing could be rendered. */
        fun renderSpool(
            spoolFile: File,
            info: MotionTakeInfo,
            timing: MotionTakeTiming,
            gcsvFile: File,
            withCamm: Boolean,
        ): MotionTakeOutputs? = try {
            // Only the frames and exposures are kept; the samples are streamed from the file by each pass.
            val spool = MotionSpoolReader.scan(spoolFile)
            val timeline = MotionTakeRender.timeline(timing, spool.frames, info)
            if (timeline == null) {
                null
            } else {
                val input = MotionTakeRender.input(spool, info, timeline)
                gcsvFile.parentFile?.mkdirs()
                val result = BufferedWriter(OutputStreamWriter(FileOutputStream(gcsvFile), Charsets.UTF_8), 64 * 1024).use {
                    MotionRender.renderGcsv(input, it)
                }
                if (result.gyroSamples == 0) {
                    gcsvFile.delete()
                    null
                } else {
                    val camm = if (withCamm) budgetedCamm(input) else null
                    MotionTakeOutputs(
                        gcsv = gcsvFile,
                        result = result,
                        camm = camm?.second,
                        cammBinUs = camm?.first,
                        synced = input.synced,
                        estimated = timeline.estimated,
                    )
                }
            }
        } catch (problem: Exception) {
            Log.w(TAG, "Motion render failed", problem)
            gcsvFile.delete()
            null
        } catch (problem: OutOfMemoryError) {
            // The CAMM series and the frames are still allocated; running out costs the motion data, never the take.
            Log.w(TAG, "Motion render ran out of memory", problem)
            gcsvFile.delete()
            null
        }

        /**
         * The CAMM series at the finest bin [MotionCammBudget] allows, with that bin; null when none fits. The packets
         * of every candidate bin are counted first, streaming the spool once per sensor, so only the chosen series is
         * ever built and its size stays within [MotionCammBudget.MAX_PACKETS].
         */
        fun budgetedCamm(input: MotionRenderInput): Pair<Long, CammSeries>? {
            val plan = MotionRender.cammPlan(input, MotionCammBudget.CANDIDATE_BINS_US)
            val bin = MotionCammBudget.chooseBinUs { plan.packets(it) }
            if (bin == null) {
                Log.w(TAG, "Motion data: no CAMM track, even ${MotionCammBudget.FALLBACK_BINS_US.last()} us bins exceed " +
                    "${MotionCammBudget.MAX_PACKETS} packets")
                return null
            }
            if (bin != MotionCammBudget.DEFAULT_BIN_US) Log.i(TAG, "Motion data: CAMM resampled to $bin us bins to stay within budget")
            return bin to MotionRender.cammSeries(input, bin, plan)
        }

        private fun deleteFiles(vararg files: File) {
            files.forEach { runCatching { it.delete() } }
        }
    }
}
