/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.os.PowerManager
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Gamma-encoded sRGB channels in 0..1. */
data class FillLightColor(val red: Float, val green: Float, val blue: Float) {
    init { require(listOf(red, green, blue).all { it.isFinite() && it in 0f..1f }) }
    fun scaled(factor: Float): FillLightColor = factor.coerceIn(0f, 1f).let { FillLightColor(red * it, green * it, blue * it) }
}

const val FILL_LIGHT_MIN_KELVIN = 2700
const val FILL_LIGHT_MAX_KELVIN = 6500
const val FILL_LIGHT_TINT_LIMIT = 50
/** Strongest tint attenuation of the opposite channels at |tint| = 50. */
private const val FILL_LIGHT_TINT_STRENGTH = 0.25f

/**
 * Blackbody white for [kelvin] using the Tanner Helland curve fit of the CIE 1964 10° Planckian locus
 * (values in sRGB 0..255), then a green↔magenta [tint] on the white-balance tint scale: positive values
 * add magenta (less green), negative values add green (less red and blue). The result is normalized so its
 * strongest channel is 1, giving the brightest light the panel can show at that colour. 6500 K is close to
 * neutral (sRGB's D65 white). It is an approximation for a soft light, not a colorimetric calibration.
 */
fun fillLightColor(kelvin: Int, tint: Int = 0): FillLightColor {
    val temp = kelvin.coerceIn(FILL_LIGHT_MIN_KELVIN, FILL_LIGHT_MAX_KELVIN) / 100.0
    val red = if (temp <= 66) 255.0 else 329.698727446 * (temp - 60).pow(-0.1332047592)
    val green = if (temp <= 66) 99.4708025861 * ln(temp) - 161.1195681661 else 288.1221695283 * (temp - 60).pow(-0.0755148492)
    val blue = when {
        temp >= 66 -> 255.0
        temp <= 19 -> 0.0
        else -> 138.5177312231 * ln(temp - 10) - 305.0447927307
    }
    var r = (red / 255).coerceIn(0.0, 1.0).toFloat()
    var g = (green / 255).coerceIn(0.0, 1.0).toFloat()
    var b = (blue / 255).coerceIn(0.0, 1.0).toFloat()
    val shift = tint.coerceIn(-FILL_LIGHT_TINT_LIMIT, FILL_LIGHT_TINT_LIMIT) / FILL_LIGHT_TINT_LIMIT.toFloat() * FILL_LIGHT_TINT_STRENGTH
    if (shift > 0) g *= 1 - shift else if (shift < 0) { r *= 1 - abs(shift); b *= 1 - abs(shift) }
    val peak = maxOf(r, g, b).takeIf { it > 0f } ?: 1f
    return FillLightColor((r / peak).coerceIn(0f, 1f), (g / peak).coerceIn(0f, 1f), (b / peak).coerceIn(0f, 1f))
}

/**
 * One-tap starting points for the fill light. A preset only writes Kelvin and tint, which stay adjustable;
 * fluorescent leans green and beauty leans magenta on the same tint scale as the slider.
 */
enum class FillLightPreset(val kelvin: Int, val tint: Int) {
    CANDLE(2700, 0),
    TUNGSTEN(3200, 0),
    FLUORESCENT(4000, -20),
    BEAUTY(4800, 20),
    DAYLIGHT(5600, 0),
    NEUTRAL(6500, 0);

    companion object {
        /** The preset the current Kelvin/tint exactly equal, or null after a manual adjustment. */
        fun matching(kelvin: Int, tint: Int): FillLightPreset? = entries.firstOrNull { it.kelvin == kelvin && it.tint == tint }
    }
}

enum class FillLightNotice { TIMED_OUT, THERMAL_WARM, THERMAL_HOT }

/**
 * What the fill light should do now. [windowBrightness] is a window-attribute request to the system, never
 * measured luminance; [colorScale] also dims the pixels so an OLED cover draws less power even when the
 * system ignores the brightness request.
 */
data class FillLightOutput(val windowBrightness: Float, val colorScale: Float, val notice: FillLightNotice?)

/** Window brightness ceiling and pixel scale for a timed-out light: dim, but still visibly on. */
internal const val FILL_LIGHT_TIMEOUT_BRIGHTNESS = 0.05f
internal const val FILL_LIGHT_TIMEOUT_COLOR_SCALE = 0.08f

/**
 * Pure fill-light policy. The requested brightness is the subject display's shared brightness preference.
 * Thermal status (PowerManager.THERMAL_STATUS_*) caps it: MODERATE 0.5, SEVERE 0.3, CRITICAL or above 0.1.
 * After [SubjectDisplaySettings.fillLightTimeoutSeconds] of monotonic [elapsedMs] (0 = no timeout) the light
 * drops to a dim, labelled level. The dimmest applicable rule wins; the timeout notice takes precedence.
 */
fun fillLightOutput(settings: SubjectDisplaySettings, elapsedMs: Long, thermalStatus: Int): FillLightOutput {
    val requested = settings.brightness
    val (thermalBrightness, thermalScale, thermalNotice) = when {
        thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> Triple(0.1f, 0.3f, FillLightNotice.THERMAL_HOT)
        thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> Triple(0.3f, 0.6f, FillLightNotice.THERMAL_HOT)
        thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> Triple(0.5f, 0.8f, FillLightNotice.THERMAL_WARM)
        else -> Triple(1f, 1f, null)
    }
    val timedOut = settings.fillLightTimeoutSeconds > 0 && elapsedMs.coerceAtLeast(0) >= settings.fillLightTimeoutSeconds * 1000L
    return if (timedOut) FillLightOutput(minOf(requested, FILL_LIGHT_TIMEOUT_BRIGHTNESS), FILL_LIGHT_TIMEOUT_COLOR_SCALE, FillLightNotice.TIMED_OUT)
    else FillLightOutput(minOf(requested, thermalBrightness), thermalScale, thermalNotice)
}

/** Milliseconds until the timeout fires, or null when there is no pending timeout. */
internal fun fillLightRemainingMs(settings: SubjectDisplaySettings, elapsedMs: Long): Long? =
    if (settings.fillLightTimeoutSeconds <= 0) null
    else (settings.fillLightTimeoutSeconds * 1000L - elapsedMs.coerceAtLeast(0)).takeIf { it > 0 }

/** Thermal status feed; the production one is PowerManager's listener (API 29, the app's minSdk). */
internal interface FillLightThermalSource {
    fun current(): Int
    fun register(listener: (Int) -> Unit)
    fun unregister()
}

/**
 * Tracks the fill light's monotonic start time and thermal status while FILL_LIGHT is shown on an active
 * presentation, and publishes [output] (null when inactive). The thermal listener exists only while active.
 * It reads thermal status only; it never touches capture.
 */
internal class SubjectFillLightMonitor(
    private val thermal: FillLightThermalSource?,
    private val clock: () -> Long,
    /** Runs the block after the delay and returns a cancel action. */
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onOutput: (FillLightOutput?) -> Unit,
) {
    private var startedAt: Long? = null
    private var settings: SubjectDisplaySettings? = null
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var cancelTimer: (() -> Unit)? = null
    var output: FillLightOutput? = null
        private set

    fun update(active: Boolean, next: SubjectDisplaySettings) {
        if (!active) { stop(); return }
        val previous = settings
        settings = next
        if (startedAt == null) {
            startedAt = clock()
            thermalStatus = thermal?.let { source -> runCatching { source.current() }.getOrNull() } ?: PowerManager.THERMAL_STATUS_NONE
            thermal?.runCatching { register { status -> thermalStatus = status; recompute() } }
        } else if (previous?.fillLightTimeoutSeconds != next.fillLightTimeoutSeconds) {
            // Choosing a new timeout starts it again from now.
            startedAt = clock()
        }
        recompute()
    }

    /** Operator action: bring a timed-out light back to full and restart its timeout. */
    fun restart() {
        if (startedAt == null) return
        startedAt = clock()
        recompute()
    }

    fun stop() {
        cancelTimer?.invoke()
        cancelTimer = null
        if (startedAt != null) thermal?.runCatching { unregister() }
        startedAt = null
        settings = null
        thermalStatus = PowerManager.THERMAL_STATUS_NONE
        publish(null)
    }

    private fun recompute() {
        val current = settings ?: return
        val start = startedAt ?: return
        val elapsed = clock() - start
        cancelTimer?.invoke()
        cancelTimer = fillLightRemainingMs(current, elapsed)?.let { remaining -> schedule(remaining) { cancelTimer = null; recompute() } }
        publish(fillLightOutput(current, elapsed, thermalStatus))
    }

    private fun publish(next: FillLightOutput?) {
        if (next == output) return
        output = next
        onOutput(next)
    }
}

/** PowerManager thermal status listener, delivered on [executor]. */
internal class PowerManagerThermalSource(private val power: PowerManager, private val executor: Executor) : FillLightThermalSource {
    private var listener: PowerManager.OnThermalStatusChangedListener? = null
    override fun current(): Int = power.currentThermalStatus
    override fun register(listener: (Int) -> Unit) {
        unregister()
        val platform = PowerManager.OnThermalStatusChangedListener { listener(it) }
        power.addThermalStatusListener(executor, platform)
        this.listener = platform
    }
    override fun unregister() {
        listener?.let { runCatching { power.removeThermalStatusListener(it) } }
        listener = null
    }
}
