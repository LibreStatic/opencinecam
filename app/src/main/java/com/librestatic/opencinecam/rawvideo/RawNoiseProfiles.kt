/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import android.content.Context
import android.os.Build
import android.util.Log
import com.librestatic.opencinecam.media.raw.RawQuantisation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One measured point of a [RawNoiseProfile]: the noise model `a + b * (DN - black)` (DN²) at [iso]. */
data class RawNoisePoint(val iso: Int, val a: Double, val b: Double) {
    init {
        require(iso > 0) { "iso must be positive" }
        require(a.isFinite() && a >= 0.0) { "a must be finite and non-negative" }
        require(b.isFinite() && b > 0.0) { "b must be finite and positive" }
    }
}

/**
 * The sensor noise of one camera of one device, measured on device (`RawNoiseProbeDeviceTest`) at
 * its RAW size [rawWidth] x [rawHeight]; [points] are sorted by ISO, without duplicates. It drives the
 * near-lossless RAW video encoding ([RawQuantisation]): without a profile a camera records lossless.
 */
data class RawNoiseProfile(
    val device: String,
    val cameraId: String,
    val rawWidth: Int,
    val rawHeight: Int,
    val blackLevel: Int,
    val points: List<RawNoisePoint>,
) {
    init {
        require(device.isNotBlank() && cameraId.isNotBlank()) { "device and camera must not be blank" }
        require(rawWidth > 0 && rawHeight > 0) { "RAW size must be positive" }
        require(blackLevel in 0..1023) { "black level must be a RAW10 sample" }
        require(points.isNotEmpty()) { "a profile needs at least one point" }
        require(points.zipWithNext().all { (low, high) -> low.iso < high.iso }) { "points must rise strictly in ISO" }
    }

    /** `device/camera`, the informative id a take records. */
    val id: String get() = "$device/$cameraId"

    /**
     * The noise model at [iso]: linear in ISO between the measured points for both `a` and `b`; below
     * the lowest point the lowest one; above the highest, `a` stays and `b` grows in proportion to ISO
     * (shot noise scales with the gain).
     */
    fun modelAt(iso: Int): RawQuantisation.NoiseModel {
        val first = points.first()
        val last = points.last()
        if (iso <= first.iso) return RawQuantisation.NoiseModel(first.a, first.b)
        if (iso >= last.iso) return RawQuantisation.NoiseModel(last.a, last.b * iso / last.iso)
        val upper = points.indexOfFirst { it.iso >= iso }
        val high = points[upper]
        if (high.iso == iso) return RawQuantisation.NoiseModel(high.a, high.b)
        val low = points[upper - 1]
        val t = (iso - low.iso).toDouble() / (high.iso - low.iso)
        return RawQuantisation.NoiseModel(low.a + (high.a - low.a) * t, low.b + (high.b - low.b) * t)
    }

    /** True when this profile was measured on [device]'s camera [cameraId] at the RAW size [width] x [height]. */
    fun matches(device: String, cameraId: String, width: Int, height: Int): Boolean =
        this.device == device && this.cameraId == cameraId && rawWidth == width && rawHeight == height
}

/**
 * The bundled RAW noise profiles (`app/src/main/profiles/raw-noise-profiles.json`, shipped as the
 * asset [ASSET]). They are measurements, not device quirks, so they live apart from the signed
 * device profiles. A profile applies only to the device codename (`Build.DEVICE`), camera and RAW
 * size it was measured at; any mismatch means no profile.
 */
object RawNoiseProfiles {
    const val ASSET = "raw-noise-profiles.json"
    const val SCHEMA_VERSION = 1
    private const val TAG = "RawNoiseProfiles"

    @Volatile private var loaded: List<RawNoiseProfile>? = null

    /**
     * Parses the profile document. An entry that does not validate is skipped (and reported through
     * [log]); a document of another schema version yields none.
     */
    fun parse(text: String, log: (String) -> Unit = {}): List<RawNoiseProfile> {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse {
            log("noise profiles are not a JSON object: ${it.message}")
            return emptyList()
        }
        val version = (root["schemaVersion"] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() }
        if (version != SCHEMA_VERSION) {
            log("unsupported noise profile schema $version")
            return emptyList()
        }
        val entries = runCatching { root.getValue("profiles").jsonArray }.getOrElse { return emptyList() }
        return entries.mapIndexedNotNull { index, element ->
            runCatching { decode(element.jsonObject) }.onFailure { log("noise profile $index skipped: ${it.message}") }.getOrNull()
        }
    }

    private fun decode(json: JsonObject): RawNoiseProfile {
        fun string(key: String) = json.getValue(key).jsonPrimitive.also { require(it.isString) { "$key must be a string" } }.content
        fun int(key: String) = json.getValue(key).jsonPrimitive.int
        return RawNoiseProfile(
            device = string("device"),
            cameraId = string("cameraId"),
            rawWidth = int("rawWidth"),
            rawHeight = int("rawHeight"),
            blackLevel = int("blackLevel"),
            points = json.getValue("points").jsonArray.map { point ->
                val values = point.jsonObject
                RawNoisePoint(values.getValue("iso").jsonPrimitive.int, values.getValue("a").jsonPrimitive.double, values.getValue("b").jsonPrimitive.double)
            },
        )
    }

    /** The profile of [device]'s camera [cameraId] measured at [width] x [height], or null. */
    fun find(profiles: List<RawNoiseProfile>, device: String, cameraId: String, width: Int, height: Int): RawNoiseProfile? =
        profiles.firstOrNull { it.matches(device, cameraId, width, height) }

    /** The bundled profiles, read once per process. */
    fun bundled(context: Context): List<RawNoiseProfile> = loaded ?: synchronized(this) {
        loaded ?: load(context.applicationContext).also { loaded = it }
    }

    /** The bundled profile of this phone's camera [cameraId] at the RAW size [width] x [height], or null. */
    fun forCamera(context: Context, cameraId: String, width: Int, height: Int): RawNoiseProfile? =
        find(bundled(context), Build.DEVICE, cameraId, width, height)

    private fun load(context: Context): List<RawNoiseProfile> = runCatching {
        context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
    }.fold(
        onSuccess = { parse(it) { message -> Log.w(TAG, message) } },
        onFailure = {
            Log.w(TAG, "bundled noise profiles could not be read", it)
            emptyList()
        },
    )
}
