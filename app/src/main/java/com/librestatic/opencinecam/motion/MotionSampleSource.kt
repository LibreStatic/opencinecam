/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/** Receives raw motion samples on the source's own thread. [kind] is a [MotionSpoolFormat] sensor kind. */
internal fun interface MotionSampleConsumer {
    fun onSample(kind: Int, timestampNs: Long, x: Float, y: Float, z: Float)
}

/** What a [MotionSampleSource] opened: the sensors present and the fastest rate each declares (`1 / getMinDelay`). */
internal class MotionSensorSet(
    val gyroMaxHz: Double?,
    val accelerometer: Boolean,
    val accelMaxHz: Double?,
    val magnetometer: Boolean,
    val magMaxHz: Double?,
) {
    companion object {
        fun maxHz(minDelayUs: Int): Double? = if (minDelayUs > 0) 1_000_000.0 / minDelayUs else null
    }
}

/** The running sensor acquisition of a take, as [MotionTakeRecorder] sees it. */
internal interface MotionAcquisition {
    val sensors: MotionSensorSet

    /** Stops delivery and waits for the sensor thread to end, also when [stopThen] already stopped it. Idempotent. */
    fun stop()

    /**
     * Stops delivery without waiting: unregisters the sensors, then runs [after] on the sensor thread once the samples
     * already queued there are handed over, and ends the thread. [after] runs once, on the caller when the acquisition
     * had already stopped. Idempotent apart from that.
     */
    fun stopThen(after: () -> Unit)
}

/**
 * Reads the gyroscope, and the accelerometer and magnetometer when present, at their fastest rate without batching on
 * a thread of its own, handing every event to [consumer]. The gyroscope is required; the others are optional. When the
 * fastest rate is refused (no high-sampling-rate permission) the sensor is read at 200 Hz instead.
 */
internal class MotionSampleSource private constructor(
    private val manager: SensorManager,
    private val thread: HandlerThread,
    private val consumer: MotionSampleConsumer,
) : MotionAcquisition {
    @Volatile private var failed = false
    private var stopped = false
    override lateinit var sensors: MotionSensorSet
        private set

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (failed) return
            val kind = when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> MotionSpoolFormat.KIND_GYRO
                Sensor.TYPE_ACCELEROMETER -> MotionSpoolFormat.KIND_ACCEL
                Sensor.TYPE_MAGNETIC_FIELD -> MotionSpoolFormat.KIND_MAG
                else -> return
            }
            try {
                val v = event.values
                consumer.onSample(kind, event.timestamp, v[0], v[1], v[2])
            } catch (problem: Exception) {
                failed = true
                Log.w(TAG, "Motion sample consumer failed; motion data stops", problem)
                // Nothing reads the sensors any more: stop them now rather than at the take's end.
                runCatching { manager.unregisterListener(this) }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /** Unregisters the sensors and ends the thread; no sample reaches the consumer afterwards. Idempotent. */
    @Synchronized
    override fun stop() {
        if (stopped) {
            // After stopThen: its thread is still handing over the samples queued before the stop.
            if (Thread.currentThread() !== thread) runCatching { thread.join(1_000) }
            return
        }
        stopped = true
        runCatching { manager.unregisterListener(listener) }
        thread.quitSafely()
        runCatching { thread.join(1_000) }
    }

    override fun stopThen(after: () -> Unit) {
        synchronized(this) {
            if (!stopped) {
                stopped = true
                runCatching { manager.unregisterListener(listener) }
                val posted = runCatching {
                    thread.looper?.let { looper ->
                        Handler(looper).post {
                            try {
                                after()
                            } finally {
                                thread.quitSafely()
                            }
                        }
                    } ?: false
                }.getOrDefault(false)
                if (posted) return
                thread.quitSafely()
            }
        }
        after()
    }

    private fun register(sensor: Sensor, handler: Handler): Boolean = try {
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, 0, handler)
    } catch (_: SecurityException) {
        // Android 12+ refuses rates above 200 Hz without HIGH_SAMPLING_RATE_SENSORS.
        manager.registerListener(listener, sensor, FALLBACK_PERIOD_US, 0, handler)
    }

    companion object {
        private const val TAG = "MotionData"
        private const val FALLBACK_PERIOD_US = 5_000

        /** Null when the device has no gyroscope or it cannot be read. Never throws. */
        fun start(context: Context, consumer: MotionSampleConsumer): MotionSampleSource? {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
            val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return null
            val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            var source: MotionSampleSource? = null
            return try {
                val thread = HandlerThread("motion-sensors").also { it.start() }
                val created = MotionSampleSource(manager, thread, consumer).also { source = it }
                val handler = Handler(thread.looper)
                check(created.register(gyroscope, handler)) { "The gyroscope could not be registered" }
                val withAccel = accelerometer != null && runCatching { created.register(accelerometer, handler) }.getOrDefault(false)
                val withMag = magnetometer != null && runCatching { created.register(magnetometer, handler) }.getOrDefault(false)
                created.sensors = MotionSensorSet(
                    gyroMaxHz = MotionSensorSet.maxHz(gyroscope.minDelay),
                    accelerometer = withAccel,
                    accelMaxHz = accelerometer?.takeIf { withAccel }?.let { MotionSensorSet.maxHz(it.minDelay) },
                    magnetometer = withMag,
                    magMaxHz = magnetometer?.takeIf { withMag }?.let { MotionSensorSet.maxHz(it.minDelay) },
                )
                created
            } catch (problem: Exception) {
                Log.w(TAG, "Motion sensors unavailable", problem)
                source?.stop()
                null
            }
        }
    }
}
