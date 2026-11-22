/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** How a take's frames reach the file, which decides how its timeline is built. */
internal enum class MotionTakeRoute {
    /** The GPU pipeline: every encoded frame is spooled with the presentation time given to the encoder. */
    GPU,
    /** MediaRecorder straight from the camera (constrained high speed): only the first and last frame are known. */
    MEDIA_RECORDER,
}

/**
 * Everything a render needs besides the spool, written beside it when the take starts (`<take id>.json`), so a take
 * whose process died can still be rendered without the service.
 */
internal data class MotionTakeInfo(
    val takeId: String,
    val videoFileName: String,
    /** The [com.librestatic.opencinecam.storage.GyroflowLog.orientation] string of the take. */
    val orientation: String,
    val readoutDirection: Int,
    val frontFacing: Boolean,
    val sensorOrientationDegrees: Int,
    val storedRotationDegrees: Int,
    /** True when camera timestamps are on the SensorEvent clock (SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME). */
    val cameraClockRealtime: Boolean,
    /** Added to a camera timestamp to put it on the SensorEvent clock; 0 when [cameraClockRealtime]. */
    val clockOffsetNs: Long,
    val startedAtUnixMs: Long,
    val firmware: String,
    val route: MotionTakeRoute,
    /** The camera's capture rate; the MediaRecorder route infers frame times from it when the stop was not drained. */
    val captureFps: Double,
    val gyroMaxHz: Double? = null,
    val accelerometer: Boolean = false,
    val accelMaxHz: Double? = null,
    val magnetometer: Boolean = false,
    val magMaxHz: Double? = null,
) {
    fun toJson(): String = buildJsonObject {
        put("schema", SCHEMA)
        put("takeId", takeId)
        put("videoFileName", videoFileName)
        put("orientation", orientation)
        put("readoutDirection", readoutDirection)
        put("frontFacing", frontFacing)
        put("sensorOrientationDegrees", sensorOrientationDegrees)
        put("storedRotationDegrees", storedRotationDegrees)
        put("cameraClockRealtime", cameraClockRealtime)
        put("clockOffsetNs", clockOffsetNs)
        put("startedAtUnixMs", startedAtUnixMs)
        put("firmware", firmware)
        put("route", route.name)
        put("captureFps", captureFps)
        gyroMaxHz?.let { put("gyroMaxHz", it) }
        put("accelerometer", accelerometer)
        accelMaxHz?.let { put("accelMaxHz", it) }
        put("magnetometer", magnetometer)
        magMaxHz?.let { put("magMaxHz", it) }
    }.toString()

    companion object {
        const val SCHEMA = "opencinecam.motion-take.v1"

        /** @throws IllegalArgumentException when [json] is not a take description of this schema. */
        fun fromJson(json: String): MotionTakeInfo {
            val o = Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("not a JSON object")
            fun p(key: String): JsonPrimitive = o[key] as? JsonPrimitive ?: throw IllegalArgumentException("missing $key")
            fun string(key: String): String = p(key).takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key is not a string")
            fun int(key: String): Int = p(key).intOrNull ?: throw IllegalArgumentException("$key is not an int")
            fun long(key: String): Long = p(key).longOrNull ?: throw IllegalArgumentException("$key is not a long")
            fun bool(key: String): Boolean = p(key).booleanOrNull ?: throw IllegalArgumentException("$key is not a boolean")
            fun double(key: String): Double = p(key).doubleOrNull ?: throw IllegalArgumentException("$key is not a number")
            fun optionalDouble(key: String): Double? = (o[key] as? JsonPrimitive)?.doubleOrNull
            require(string("schema") == SCHEMA) { "unsupported motion take schema" }
            return MotionTakeInfo(
                takeId = string("takeId"),
                videoFileName = string("videoFileName"),
                orientation = string("orientation"),
                readoutDirection = int("readoutDirection"),
                frontFacing = bool("frontFacing"),
                sensorOrientationDegrees = int("sensorOrientationDegrees"),
                storedRotationDegrees = int("storedRotationDegrees"),
                cameraClockRealtime = bool("cameraClockRealtime"),
                clockOffsetNs = long("clockOffsetNs"),
                startedAtUnixMs = long("startedAtUnixMs"),
                firmware = string("firmware"),
                route = MotionTakeRoute.valueOf(string("route")),
                captureFps = double("captureFps"),
                gyroMaxHz = optionalDouble("gyroMaxHz"),
                accelerometer = (o["accelerometer"] as? JsonPrimitive)?.booleanOrNull ?: false,
                accelMaxHz = optionalDouble("accelMaxHz"),
                magnetometer = (o["magnetometer"] as? JsonPrimitive)?.booleanOrNull ?: false,
                magMaxHz = optionalDouble("magMaxHz"),
            )
        }
    }
}

/** What the stop knows about how the take's frames landed in the file. */
internal sealed class MotionTakeTiming {
    /**
     * The GPU route: a spooled frame's file time is its encoder presentation time minus the first frame's, plus
     * [fileOffsetUs] (the muxer's constant, `CaptureEpochReport.videoMuxOffsetUs`). Fewer than [minFrames] frames
     * render nothing.
     */
    class EncodedFrames(val fileOffsetUs: Long, val minFrames: Int = 1) : MotionTakeTiming()

    /**
     * The MediaRecorder route, from the recorder's frame anchors (`DirectRecorderTiming`) and the finished file's video
     * track: [fileSamples] samples, the last presented at [lastSampleTimeUs].
     */
    class RecorderAnchors(
        val firstFrameSensorNs: Long?,
        val lastFrameSensorNs: Long?,
        val frames: Long,
        val drained: Boolean,
        /** Boot time right after the recorder started. */
        val recorderStartElapsedNs: Long,
        val fileSamples: Long,
        val lastSampleTimeUs: Long,
    ) : MotionTakeTiming()
}

/**
 * What a take's motion data came to, for the take's records: the render, the CAMM track, and the sensors and their
 * nominal fastest rates in [info].
 */
internal data class MotionTakeSummary(
    val info: MotionTakeInfo,
    val result: MotionRenderResult,
    val synced: Boolean,
    val estimated: Boolean,
    val cammAppended: Boolean,
    val cammPackets: Int,
    /** The bin the CAMM series was resampled to ([MotionCammBudget]); null when no CAMM series was built. */
    val cammBinUs: Long?,
    /** True once the Gyroflow log is attached to the take. */
    val gcsvAttached: Boolean = false,
) {
    fun logLine(): String = "Motion data: ${result.gyroSamples} gyro (${hz(result.measuredGyroHz)} of ${hz(info.gyroMaxHz)}), " +
        "${result.accelSamples} accel (${hz(result.measuredAccelHz)} of ${hz(info.accelMaxHz)}), " +
        "${result.magSamples} mag (${hz(result.measuredMagHz)} of ${hz(info.magMaxHz)}), " +
        "largest gyro gap ${result.largestGyroGapNs / 1_000L} us, rate scale ${"%.4f".format(java.util.Locale.ROOT, result.globalRateScale)}, " +
        "file ${result.fileStartUs}..${result.fileEndUs} us, ${if (synced) "synced" else if (estimated) "estimated" else "clock estimated"}, " +
        "camm ${if (cammAppended) "$cammPackets packets at ${cammBinUs} us bins" else "none"}, " +
        "gcsv ${if (gcsvAttached) "attached" else "not attached"}"

    private fun hz(value: Double?): String = value?.let { "%.1f Hz".format(java.util.Locale.ROOT, it) } ?: "? Hz"
}

/** A take's timeline and whether it is measured or partly inferred. */
internal class MotionTakeTimeline(val timeline: MotionTimeline, val estimated: Boolean)

internal object MotionTakeRender {
    const val NOTE_CLOCK_ESTIMATED = "sync estimated: camera clock is not the sensor clock"
    const val NOTE_FRAMES_ESTIMATED = "sync estimated: recorder timing not measured"

    /** The take's sensor-to-file timeline, or null when [timing] cannot place any frame. */
    fun timeline(timing: MotionTakeTiming, frames: FrameTrack, info: MotionTakeInfo): MotionTakeTimeline? = when (timing) {
        is MotionTakeTiming.EncodedFrames -> {
            if (frames.size < maxOf(timing.minFrames, 1)) null
            else MotionTakeTimeline(MotionTimeline.fromFrames(frames, timing.fileOffsetUs, info.clockOffsetNs), estimated = false)
        }
        is MotionTakeTiming.RecorderAnchors -> recorderTimeline(timing, info)
    }

    /**
     * The file's frame duration is measured from its video track, so a stretched (slow-motion) file and a real-time
     * one need no separate case. A drained stop pins the last frame to the file's last sample and the first frame to
     * where the frame count puts it; otherwise the first frame is taken to be at file time 0 and the slope is the file
     * frame duration over the capture frame interval. MediaRecorder writes the high-speed file at `setVideoFrameRate`
     * (the capture rate, or the project rate off-speed, which stretches it), so the measured duration covers both.
     *
     * Both end with a third anchor one frame past the last, on the same slope, so that only one frame interval past the
     * last frame maps: with two anchors the extrapolation would reach a whole take length past the stop.
     */
    private fun recorderTimeline(timing: MotionTakeTiming.RecorderAnchors, info: MotionTakeInfo): MotionTakeTimeline? {
        if (timing.fileSamples < 2 || timing.lastSampleTimeUs <= 0) return null
        val fileFrameUs = timing.lastSampleTimeUs.toDouble() / (timing.fileSamples - 1)
        val first = timing.firstFrameSensorNs
        val last = timing.lastFrameSensorNs
        if (timing.drained && timing.frames >= 2 && first != null && last != null && last > first) {
            val firstFileUs = (timing.lastSampleTimeUs - Math.round((timing.frames - 1) * fileFrameUs)).coerceAtLeast(0L)
            if (firstFileUs < timing.lastSampleTimeUs) {
                return MotionTakeTimeline(
                    bounded(first, firstFileUs, last, timing.lastSampleTimeUs, (last - first) / (timing.frames - 1),
                        Math.round(fileFrameUs), info.clockOffsetNs),
                    estimated = false,
                )
            }
        }
        if (info.captureFps <= 0.0) return null
        val start = first ?: (timing.recorderStartElapsedNs - info.clockOffsetNs)
        val spanFrames = timing.fileSamples - 1
        val captureFrameNs = Math.round(1_000_000_000.0 / info.captureFps)
        val endSensor = start + Math.round(spanFrames * 1_000_000_000.0 / info.captureFps)
        return MotionTakeTimeline(
            bounded(start, 0L, endSensor, timing.lastSampleTimeUs, captureFrameNs, Math.round(fileFrameUs), info.clockOffsetNs),
            estimated = true,
        )
    }

    private fun bounded(
        firstNs: Long,
        firstFileUs: Long,
        lastNs: Long,
        lastFileUs: Long,
        frameNs: Long,
        fileFrameUs: Long,
        clockOffsetNs: Long,
    ): MotionTimeline = MotionTimeline.fromAnchors(
        longArrayOf(firstNs, lastNs, lastNs + frameNs.coerceAtLeast(1L)),
        longArrayOf(firstFileUs, lastFileUs, lastFileUs + fileFrameUs.coerceAtLeast(1L)),
        clockOffsetNs,
    )

    fun input(spool: MotionSpool, info: MotionTakeInfo, timeline: MotionTakeTimeline): MotionRenderInput = MotionRenderInput(
        spool = spool,
        timeline = timeline.timeline,
        orientation = info.orientation,
        videoFileName = info.videoFileName,
        unixSeconds = info.startedAtUnixMs / 1000L,
        firmware = info.firmware,
        synced = synced(info, timeline),
        readoutDirection = info.readoutDirection,
        note = when {
            !info.cameraClockRealtime -> NOTE_CLOCK_ESTIMATED
            timeline.estimated -> NOTE_FRAMES_ESTIMATED
            else -> null
        },
    )

    fun synced(info: MotionTakeInfo, timeline: MotionTakeTimeline): Boolean = info.cameraClockRealtime && !timeline.estimated
}
