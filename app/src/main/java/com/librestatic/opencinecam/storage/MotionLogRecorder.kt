/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.UUID

/** Where a motion log is aimed: the camera and the stored-pixel rotation decide its axis mapping. */
internal class MotionLogTarget(
    val videoFileName: String,
    val sensorOrientationDegrees: Int,
    val frontFacing: Boolean,
    val storedPixelRotationDegrees: Int,
    /** True when SENSOR_TIMESTAMP shares the SensorEvent clock (REALTIME), so the log can be aligned. */
    val timestampsSynced: Boolean,
    val rollingShutterSkewNs: Long?,
)

/**
 * Streams gyroscope and accelerometer samples into a local `.gcsv` while a take records. It only
 * observes: any failure ends the log silently and never touches the take. The sensors are read at the
 * fastest rate on their own thread; no high-sampling-rate permission is requested, so Android may cap it.
 */
internal class MotionLogRecorder private constructor(
    private val file: File,
    private val thread: HandlerThread,
    private val manager: SensorManager,
    private val target: MotionLogTarget,
    firmware: String,
    hasAccelerometer: Boolean,
) {
    @Volatile private var rollingShutterSkewNs: Long? = target.rollingShutterSkewNs
    @Volatile private var failed = false
    private var finished = false
    private val startedAtUnixSeconds = System.currentTimeMillis() / 1000L
    private val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8), 64 * 1024)
    private val stream = GyroflowLog.Stream(writer, { synced ->
        GyroflowLog.header(GyroflowLog.Header(
            orientation = GyroflowLog.orientation(target.sensorOrientationDegrees, target.frontFacing, target.storedPixelRotationDegrees),
            videoFileName = target.videoFileName,
            unixSeconds = startedAtUnixSeconds,
            firmware = firmware,
            readoutTimeMs = rollingShutterSkewNs?.let { it / 1_000_000.0 },
            readoutDirection = GyroflowLog.readoutDirection(target.storedPixelRotationDegrees),
            synced = synced,
            hasAccelerometer = hasAccelerometer,
        ))
    }, hasAccelerometer)
    private val pairer = GyroflowLog.Pairer(this::add)
    private val withAccelerometer = hasAccelerometer
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (failed) return
            try {
                val v = event.values
                if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    if (withAccelerometer) pairer.onGyro(event.timestamp, v[0], v[1], v[2])
                    else add(GyroflowLog.Sample(event.timestamp, v[0], v[1], v[2], 0f, 0f, 0f))
                } else pairer.onAccel(event.timestamp, v[0], v[1], v[2])
            } catch (problem: Exception) {
                failed = true
                android.util.Log.w(TAG, "Gyroflow log stopped", problem)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private fun add(sample: GyroflowLog.Sample) = stream.add(sample)

    fun updateRollingShutterSkew(ns: Long?) { rollingShutterSkewNs = ns }

    /**
     * t = 0 is the sensor timestamp of the first frame in the file. With a clock that is not the sensor
     * clock the log is anchored to the moment this is called and says so in its note.
     */
    fun onFirstFrame(sensorTimestampNs: Long) {
        try {
            if (target.timestampsSynced) stream.setAnchor(sensorTimestampNs, synced = true)
            else stream.setAnchor(SystemClock.elapsedRealtimeNanos(), synced = false)
        } catch (problem: Exception) {
            failed = true
            android.util.Log.w(TAG, "Gyroflow header failed", problem)
        }
    }

    /** Stops the sensors and closes the file. Returns it when it holds a usable log and [keep] is true. */
    @Synchronized
    fun finish(keep: Boolean): File? {
        if (finished) return null
        finished = true
        runCatching { manager.unregisterListener(listener) }
        thread.quitSafely()
        runCatching { thread.join(1_000) }
        var rows = 0L
        try {
            runCatching { pairer.flush() }
            rows = stream.rows
            stream.close()
        } catch (problem: Exception) {
            failed = true
            android.util.Log.w(TAG, "Gyroflow log close failed", problem)
        }
        val usable = keep && !failed && stream.anchored && rows > 0
        if (!usable) { file.delete(); return null }
        return file
    }

    companion object {
        private const val TAG = "GyroflowLog"
        private const val DIRECTORY = "gyroflow"

        /** Null when the device lacks a gyroscope or the sensors cannot be opened. */
        fun start(context: Context, target: MotionLogTarget): MotionLogRecorder? {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
            val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return null
            val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val directory = File(context.cacheDir, DIRECTORY)
            var thread: HandlerThread? = null
            var file: File? = null
            var recorder: MotionLogRecorder? = null
            return try {
                // Only one take records at a time, so anything left here is a crashed take's leftover.
                directory.listFiles()?.forEach { it.delete() }
                check(directory.isDirectory || directory.mkdirs())
                file = File(directory, UUID.randomUUID().toString() + "." + GyroflowLog.EXTENSION)
                thread = HandlerThread("gyroflow-log").also { it.start() }
                val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                val created = MotionLogRecorder(file, thread, manager, target, "OpenCineCam ${version ?: ""}".trim(), accelerometer != null)
                recorder = created
                val handler = Handler(thread.looper)
                check(manager.registerListener(created.listener, gyroscope, SensorManager.SENSOR_DELAY_FASTEST, handler))
                if (accelerometer != null) {
                    check(manager.registerListener(created.listener, accelerometer, SensorManager.SENSOR_DELAY_FASTEST, handler))
                }
                created
            } catch (problem: Exception) {
                android.util.Log.w(TAG, "Gyroflow log unavailable", problem)
                recorder?.finish(false) ?: run { thread?.quitSafely(); file?.delete() }
                null
            }
        }
    }
}
