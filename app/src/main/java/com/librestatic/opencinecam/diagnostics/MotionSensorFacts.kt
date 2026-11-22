/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The three sensors the motion data (Gyroflow .gcsv and the CAMM track) is recorded from. */
enum class MotionSensorKind(val id: String, val sensorType: Int, val unit: String) {
    GYROSCOPE("gyroscope", Sensor.TYPE_GYROSCOPE, "rad/s"),
    ACCELEROMETER("accelerometer", Sensor.TYPE_ACCELEROMETER, "m/s²"),
    MAGNETOMETER("magnetometer", Sensor.TYPE_MAGNETIC_FIELD, "µT"),
}

/** What the default sensor of one [kind] declares; every field but [kind] and [present] is null when it is absent. */
data class MotionSensorFact(
    val kind: MotionSensorKind,
    val present: Boolean,
    val name: String? = null,
    val vendor: String? = null,
    /** `Sensor.getMinDelay()`, in µs; 0 or less means the sensor does not stream at a declared rate. */
    val minDelayUs: Int? = null,
    val maxRateHz: Double? = null,
    val resolution: Float? = null,
    val maxRange: Float? = null,
    val fifoMaxEventCount: Int? = null,
    /** True when Android 12+ holds this app to 200 Hz; null when the sensor's rate is unknown. */
    val highSamplingRateCapped: Boolean? = null,
) {
    companion object {
        fun absent(kind: MotionSensorKind) = MotionSensorFact(kind, present = false)
    }
}

/** Android 12 limits apps without `HIGH_SAMPLING_RATE_SENSORS` to this rate. */
internal const val HIGH_SAMPLING_RATE_CAP_HZ = 200.0

/** The fastest rate a streaming sensor declares, from its minimum delay in µs; null for on-change or one-shot sensors. */
internal fun maxRateHzFromMinDelay(minDelayUs: Int): Double? = if (minDelayUs <= 0) null else 1_000_000.0 / minDelayUs

/** Whether the platform caps this app's rate: only from Android 12, without the permission, on a sensor faster than the cap. */
internal fun highSamplingRateCapped(sdkInt: Int, permissionGranted: Boolean, maxRateHz: Double?): Boolean? = when {
    maxRateHz == null -> null
    sdkInt < Build.VERSION_CODES.S -> false
    else -> !permissionGranted && maxRateHz > HIGH_SAMPLING_RATE_CAP_HZ
}

/** "500", "2404" or "12.5": whole hertz from 10 Hz up, one decimal below. */
internal fun formatRateHz(rateHz: Double): String =
    if (rateHz >= 10.0) "%.0f".format(Locale.ROOT, rateHz) else "%.1f".format(Locale.ROOT, rateHz)

/**
 * "0.0625 µT": the value as Android reports it, in plain decimals: the shortest text that reads back as the same float,
 * so nothing is rounded and no zero is padded on.
 */
internal fun formatSensorValue(value: Float, unit: String): String {
    val text = if (value.isNaN() || value.isInfinite()) value.toString()
    else java.math.BigDecimal(value.toString()).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
    return "$text $unit"
}

/**
 * The report's `device.motionSensors` block, keyed by sensor kind. The sensor's name and vendor
 * name a part, not the phone, so they stay in a redacted export like the codec names do.
 */
internal fun motionSensorsJson(facts: List<MotionSensorFact>): JsonObject = buildJsonObject {
    facts.forEach { fact ->
        put(fact.kind.id, buildJsonObject {
            put("present", fact.present)
            fact.name?.let { put("name", it) }
            fact.vendor?.let { put("vendor", it) }
            fact.minDelayUs?.let { put("minDelayUs", it) }
            fact.maxRateHz?.let { put("maxRateHz", it) }
            fact.resolution?.let { put("resolution", it) }
            fact.maxRange?.let { put("maxRange", it) }
            fact.fifoMaxEventCount?.let { put("fifoMaxEventCount", it) }
            fact.highSamplingRateCapped?.let { put("highSamplingRateCapped", it) }
            if (fact.present) put("unit", fact.kind.unit)
        })
    }
}

/** One line per sensor for the plain-text copy of the report. */
internal fun motionSensorLine(fact: MotionSensorFact, notAvailable: String): String = buildString {
    append(fact.kind.id).append(": ")
    if (!fact.present) {
        append(notAvailable)
        return@buildString
    }
    append(fact.maxRateHz?.let { formatRateHz(it) + " Hz" } ?: "rate n/a")
    fact.resolution?.let { append(", resolution ").append(formatSensorValue(it, fact.kind.unit)) }
    fact.maxRange?.let { append(", range ").append(formatSensorValue(it, fact.kind.unit)) }
    fact.fifoMaxEventCount?.let { append(", fifo ").append(it) }
    if (fact.highSamplingRateCapped == true) append(", capped at ").append(formatRateHz(HIGH_SAMPLING_RATE_CAP_HZ)).append(" Hz")
    listOfNotNull(fact.name, fact.vendor).takeIf { it.isNotEmpty() }?.let { append(" (").append(it.joinToString(", ")).append(')') }
}

/** Reads the default sensor of each kind; a missing sensor service reads as no sensors. */
internal fun readMotionSensorFacts(context: Context): List<MotionSensorFact> {
    val manager = context.getSystemService(SensorManager::class.java)
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS) == PackageManager.PERMISSION_GRANTED
    return MotionSensorKind.entries.map { kind ->
        val sensor = manager?.getDefaultSensor(kind.sensorType) ?: return@map MotionSensorFact.absent(kind)
        val rate = maxRateHzFromMinDelay(sensor.minDelay)
        MotionSensorFact(
            kind = kind,
            present = true,
            name = sensor.name,
            vendor = sensor.vendor,
            minDelayUs = sensor.minDelay,
            maxRateHz = rate,
            resolution = sensor.resolution,
            maxRange = sensor.maximumRange,
            fifoMaxEventCount = sensor.fifoMaxEventCount,
            highSamplingRateCapped = highSamplingRateCapped(Build.VERSION.SDK_INT, granted, rate),
        )
    }
}
