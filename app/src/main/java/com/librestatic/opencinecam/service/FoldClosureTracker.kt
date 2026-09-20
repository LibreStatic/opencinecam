/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import com.librestatic.opencinecam.FoldCloseDetector

/** Runs with the capture service, not the Activity. No fold is inferred from a disappearing layout feature. */
internal class FoldClosureTracker(context: Context, private val onClosed: () -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val detector = FoldCloseDetector()
    private val sensor = if (Build.VERSION.SDK_INT >= 30) manager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) else null
    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) {
            event.values.firstOrNull()?.let { if (detector.sample(it)) onClosed() }
        }
    }
    fun start(): Boolean = sensor?.let { manager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) } == true
    override fun close() { manager?.unregisterListener(listener) }
}
