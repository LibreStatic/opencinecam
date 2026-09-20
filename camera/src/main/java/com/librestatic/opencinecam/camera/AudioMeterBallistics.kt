/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Digital amplitude meters, not true-peak, loudness or an electrical instrument certification.
 *
 * VU: full-wave rectification, sine-peak calibration π/2, followed by a second-order movement
 * (ζ=.82, ω=13.758752060525719 rad/s). Its envelope step first reaches 99% at 300 ms and
 * overshoots 1.11%. The exact zero-order-held state transition below runs once per PCM sample.
 * ITU-R BS.645-2 Annex 2: https://www.itu.int/rec/R-REC-BS.645-2-199203-I/en
 *
 * Quasi-PPM: equally weighted 2/12 ms rectified integrators, followed by an envelope with
 * 24 dB/2.8 s release. Digital burst approximation of historical EBU Type IIb normal mode:
 * https://tech.ebu.ch/docs/tech/tech3205.pdf (Table 2 and §3.10). Tests retain the table's
 * individual burst tolerances, not a blanket loose tolerance. This is not a claim of the
 * complete analog frequency/pulse-response specification. Both outputs are sine-peak dBFS
 * amplitudes; RMS remains a separate, uncalibrated mean-square statistic in AudioLevelMeter.
 *
 * There is no wall clock, window reset, UI reference, peak hold, PCM mutation or microphone here.
 */
internal class AudioMeterBallistics(sampleRateHz: Int) {
    init { require(sampleRateHz in 8_000..192_000) }

    private val dt = 1.0 / sampleRateHz
    private val omega = 13.758752060525719
    private val damping = .82
    private val dampedOmega = omega * sqrt(1 - damping * damping)
    private val decay = exp(-damping * omega * dt)
    private val sine = sin(dampedOmega * dt)
    private val cosine = cos(dampedOmega * dt)
    private val yy = decay * (cosine + damping * omega / dampedOmega * sine)
    private val yv = decay * sine / dampedOmega
    private val vy = -decay * omega * omega * sine / dampedOmega
    private val vv = decay * (cosine - damping * omega / dampedOmega * sine)
    private val fast = exp(-dt / .002)
    private val slow = exp(-dt / .012)
    private val release = exp(-24.0 * kotlin.math.ln(10.0) * dt / (20.0 * 2.8))
    private var movement = 0.0
    private var velocity = 0.0
    private var fastEnvelope = 0.0
    private var slowEnvelope = 0.0
    var ppmAmplitude = 0.0
        private set
    val vuAmplitude: Double get() = movement.coerceAtLeast(0.0)

    fun process(sample: Double) {
        val rectified = if (sample.isFinite()) abs(sample) * (PI / 2) else 0.0
        val error = movement - rectified
        movement = rectified + yy * error + yv * velocity
        velocity = vy * error + vv * velocity
        fastEnvelope = fast * fastEnvelope + (1 - fast) * rectified
        slowEnvelope = slow * slowEnvelope + (1 - slow) * rectified
        ppmAmplitude = maxOf((fastEnvelope + slowEnvelope) / 2, ppmAmplitude * release)
    }
}
