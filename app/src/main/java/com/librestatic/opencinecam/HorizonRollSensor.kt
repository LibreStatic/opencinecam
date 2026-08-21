/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.os.SystemClock

/**
 * Continuous horizon roll reader backed by TYPE_GRAVITY (or TYPE_ACCELEROMETER as a fallback).
 * Reports a signed roll in degrees in display space; NaN readings are emitted when the device
 * lies flat or the geometry cannot be measured.
 */
class HorizonRollSensor(
    context: Context,
    private val displayRotationProvider: () -> Int,
    private val onSnapshot: (HorizonRollSnapshot) -> Unit,
) : AutoCloseable {
    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val smoothingAlpha = 0.18f
    @Volatile private var smoothed: Float? = null
    @Volatile private var lastEmitMs: Long = 0L
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val rotation = displayRotationProvider()
            val raw = HorizonRollMath.rollDegrees(event.values[0], event.values[1], event.values[2], rotation)
            smoothed = HorizonRollMath.smooth(smoothed, raw, smoothingAlpha)
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmitMs >= 33L) {
                lastEmitMs = now
                onSnapshot(HorizonRollSnapshot(smoothed ?: Float.NaN, now))
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    val available: Boolean get() = sensor != null

    fun start(): Boolean {
        val target = sensor ?: return false
        sensorManager.registerListener(listener, target, SensorManager.SENSOR_DELAY_GAME)
        return true
    }

    override fun close() {
        sensorManager.unregisterListener(listener)
    }
}
