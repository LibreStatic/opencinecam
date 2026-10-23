/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import kotlin.math.roundToInt

/** One simulated physical lens: its place on the zoom scale and how it differs from the reference lens. */
internal class SimLens(
    val id: String,
    val nominal: Float,
    /** The target point showing the reference's content at p is scale * p + (txPx, tyPx), at equal zoom. */
    val scale: Float = 1f,
    val txPx: Float = 0f,
    val tyPx: Float = 0f,
    /** The colour model that maps this lens' linear colours onto the reference's: 2^ev * M * (gains * target). */
    val matrix: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
    val gains: FloatArray = floatArrayOf(1f, 1f, 1f),
    val ev: Float = 0f,
)

/**
 * A logical camera with textured, coloured content, several lenses and a zoom-driven lens switch with
 * hysteresis: lens k+1 takes over when the zoom reaches up[k], lens k when it falls below down[k].
 * Results lag a zoom command by one sample, and the first samples after a lens switch report AE
 * still searching. Time is virtual: every sample costs 100 ms.
 */
internal class SimulatedRig(
    val lenses: List<SimLens>,
    private val up: FloatArray,
    private val down: FloatArray,
    /** Lenses whose AE never converges. */
    private val unconverged: Set<Int> = emptySet(),
    /** Relative alternating swing of the exposure time between samples. */
    private val exposureWobble: Float = 0f,
    private val onSample: (Int) -> Unit = {},
) : CalibrationRig {
    var zoom = 1f
        private set
    var active = 0
        private set
    var clockMs = 0L
        private set
    var samplesServed = 0
        private set
    private var reportedZoom = 1f
    private var zoomLag = 0
    private var searching = 0
    private val frames = HashMap<Pair<Int, Int>, RgbaFrame>()

    init { settleLens() }

    private fun settleLens() {
        while (active + 1 < lenses.size && zoom >= up[active]) active++
        while (active > 0 && zoom < down[active - 1]) active--
    }

    override fun setZoom(ratio: Float) {
        val before = active
        reportedZoom = zoom
        zoomLag = 1
        zoom = ratio
        settleLens()
        if (active != before) searching = 4
    }

    override fun nowMs(): Long = clockMs

    override fun nextSample(timeoutMs: Long): CalibrationSample {
        clockMs += 100
        samplesServed++
        onSample(samplesServed)
        val z = if (zoomLag > 0) { zoomLag--; reportedZoom } else zoom
        val ae = if (active in unconverged) 1 else if (searching > 0) { searching--; 1 } else 2
        val swing = if (samplesServed % 2 == 0) exposureWobble else -exposureWobble
        return CalibrationSample(
            frame = frameFor(active, zoom),
            timestampNs = clockMs * 1_000_000,
            physicalId = lenses[active].id,
            aeState = ae,
            awbState = 2,
            zoomRatio = z,
            exposureNs = (10_000_000L * (1f + swing)).toLong(),
            iso = 100,
            awbGains = floatArrayOf(2f, 1f, 1f, 1.6f),
        )
    }

    fun frameFor(index: Int, zoom: Float): RgbaFrame = frames.getOrPut(index to (zoom * 1000).roundToInt()) { render(lenses[index], zoom) }

    private fun render(lens: SimLens, zoom: Float): RgbaFrame {
        val w = WIDTH; val h = HEIGHT
        val inv = invert(lens.matrix)
        val px = ByteArray(w * h * 4)
        for (j in 0 until h) for (i in 0 until w) {
            val qx = i + 0.5 - w / 2.0
            val qy = j + 0.5 - h / 2.0
            // The reference shows world point p / zoomR at p; this lens shows the same content at q = scale * p' + t.
            val wx = (qx - lens.txPx) / (lens.scale * zoom)
            val wy = (qy - lens.tyPx) / (lens.scale * zoom)
            val cx = Math.floorMod(Math.floor(wx / CELL_W).toInt(), 12)
            val cy = Math.floorMod(Math.floor(wy / CELL_H).toInt(), 8)
            val base = PALETTE[cy * 12 + cx]
            val modulation = 0.7f + 0.6f * CalibrationTestSupport.texture(wx, wy)
            val ref = FloatArray(3) { base[it] * modulation }
            // Invert 2^ev * M * (gains * target) = ref.
            val scaled = FloatArray(3) { ref[it] / Math.pow(2.0, lens.ev.toDouble()).toFloat() }
            val un = FloatArray(3) { r -> inv[r * 3] * scaled[0] + inv[r * 3 + 1] * scaled[1] + inv[r * 3 + 2] * scaled[2] }
            for (c in 0 until 3) {
                val lin = (un[c] / lens.gains[c]).coerceIn(0f, 1f)
                px[(j * w + i) * 4 + c] = Math.round(CalibrationTransfer.toCode(TransferCurve.SDR, lin) * 255f).toByte()
            }
            px[(j * w + i) * 4 + 3] = 0xFF.toByte()
        }
        return RgbaFrame(w, h, px)
    }

    companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
        private const val CELL_W = 53.0
        private const val CELL_H = 45.0
        private val PALETTE: List<FloatArray> = CalibrationTestSupport.scene(7, 96).map { c -> FloatArray(3) { c[it] * 0.7f } }

        fun invert(m: FloatArray): FloatArray {
            val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
            val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
            val g = m[6].toDouble(); val h = m[7].toDouble(); val i = m[8].toDouble()
            val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
            return floatArrayOf(
                ((e * i - f * h) / det).toFloat(), ((c * h - b * i) / det).toFloat(), ((b * f - c * e) / det).toFloat(),
                ((f * g - d * i) / det).toFloat(), ((a * i - c * g) / det).toFloat(), ((c * d - a * f) / det).toFloat(),
                ((d * h - e * g) / det).toFloat(), ((b * g - a * h) / det).toFloat(), ((a * e - b * d) / det).toFloat(),
            )
        }

        /** Gains normalised to luma 1, as the solver reports them. */
        fun lumaGains(r: Float, g: Float, b: Float): FloatArray {
            val luma = FrameOps.LUMA_R * r + FrameOps.LUMA_G * g + FrameOps.LUMA_B * b
            return floatArrayOf(r / luma, g / luma, b / luma)
        }
    }
}
