/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Translation of image `b` relative to image `a`, in pixels: [dx] > 0 means the content of `a`
 * appears [dx] pixels further right (toward +x) in `b`, [dy] > 0 further down; that is
 * `b(x, y) = a(x - dx, y - dy)`. [peakRatio] is the correlation peak over the strongest value
 * outside its 5x5 neighbourhood, so ~1 means no reliable peak.
 */
data class PhaseShift(val dx: Float, val dy: Float, val peakRatio: Float)

object PhaseCorrelation {
    /**
     * Phase correlation of two `size` x `size` row-major images (`size` a power of two): Hann window,
     * 2D FFT, normalised cross-power spectrum `Fb * conj(Fa) / |.|`, inverse FFT, peak with parabolic
     * sub-pixel refinement. Means are removed before windowing.
     */
    fun estimate(a: FloatArray, b: FloatArray, size: Int): PhaseShift {
        require(size >= 4 && size and (size - 1) == 0) { "Size must be a power of two." }
        require(a.size == size * size && b.size == size * size) { "Images must be size x size." }
        val n = size * size
        val ar = windowed(a, size); val ai = DoubleArray(n)
        val br = windowed(b, size); val bi = DoubleArray(n)
        fft2d(ar, ai, size, false)
        fft2d(br, bi, size, false)
        val mag = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            // Fb * conj(Fa)
            val re = br[i] * ar[i] + bi[i] * ai[i]
            val im = bi[i] * ar[i] - br[i] * ai[i]
            ar[i] = re; ai[i] = im
            mag[i] = hypot(re, im)
            sum += mag[i]
        }
        val eps = 1e-3 * sum / n + 1e-30
        for (i in 0 until n) { ar[i] /= mag[i] + eps; ai[i] /= mag[i] + eps }
        val spectrumRe = ar.copyOf(); val spectrumIm = ai.copyOf()
        fft2d(ar, ai, size, true)
        // The surface is real up to rounding; ar holds it.
        var peak = Double.NEGATIVE_INFINITY
        var px = 0; var py = 0
        for (y in 0 until size) for (x in 0 until size) {
            val v = ar[y * size + x]
            if (v > peak) { peak = v; px = x; py = y }
        }
        var second = 1e-9
        for (y in 0 until size) for (x in 0 until size) {
            val ddx = wrapDiff(x - px, size); val ddy = wrapDiff(y - py, size)
            if (kotlin.math.abs(ddx) <= 2 && kotlin.math.abs(ddy) <= 2) continue
            if (ar[y * size + x] > second) second = ar[y * size + x]
        }
        var sx = wrapDiff(px, size).toDouble()
        var sy = wrapDiff(py, size).toDouble()
        // Sub-pixel: evaluate the correlation surface between samples (matrix-multiply DFT) in two
        // coarse-to-fine passes (0.1 then 0.01 px).
        var half = 1.0
        repeat(2) {
            val r = refine(spectrumRe, spectrumIm, size, sx, sy, half)
            sx = r[0]; sy = r[1]
            half /= 10
        }
        return PhaseShift(sx.toFloat(), sy.toFloat(), (peak / second).coerceIn(0.0, 1e6).toFloat())
    }

    private fun wrapDiff(d: Int, size: Int): Int {
        val m = ((d % size) + size) % size
        return if (m > size / 2) m - size else m
    }

    /** Maximum of the band-limited correlation surface on a 21 x 21 grid spanning +-[half] around ([cx], [cy]). */
    private fun refine(sr: DoubleArray, si: DoubleArray, size: Int, cx: Double, cy: Double, half: Double): DoubleArray {
        val pts = 21
        fun freq(i: Int) = if (i < size / 2) i else i - size
        val xs = DoubleArray(pts) { cx - half + it * half / 10 }
        val ys = DoubleArray(pts) { cy - half + it * half / 10 }
        // t[ky][xi] = sum_kx C[ky, kx] e^{+j 2 pi kx x / N}
        val tr = Array(size) { DoubleArray(pts) }; val ti = Array(size) { DoubleArray(pts) }
        for (xi in 0 until pts) {
            val er = DoubleArray(size) { cos(2 * PI * freq(it) * xs[xi] / size) }
            val ei = DoubleArray(size) { sin(2 * PI * freq(it) * xs[xi] / size) }
            for (ky in 0 until size) {
                var a = 0.0; var b = 0.0
                for (kx in 0 until size) {
                    val re = sr[ky * size + kx]; val im = si[ky * size + kx]
                    a += re * er[kx] - im * ei[kx]; b += re * ei[kx] + im * er[kx]
                }
                tr[ky][xi] = a; ti[ky][xi] = b
            }
        }
        val v = Array(pts) { DoubleArray(pts) }
        for (yi in 0 until pts) {
            val er = DoubleArray(size) { cos(2 * PI * freq(it) * ys[yi] / size) }
            val ei = DoubleArray(size) { sin(2 * PI * freq(it) * ys[yi] / size) }
            for (xi in 0 until pts) {
                var a = 0.0
                for (ky in 0 until size) a += tr[ky][xi] * er[ky] - ti[ky][xi] * ei[ky]
                v[yi][xi] = a
            }
        }
        var bx = 0; var by = 0
        for (yi in 0 until pts) for (xi in 0 until pts) if (v[yi][xi] > v[by][bx]) { bx = xi; by = yi }
        return doubleArrayOf(xs[bx], ys[by])
    }

    private fun windowed(src: FloatArray, size: Int): DoubleArray {
        var mean = 0.0
        for (v in src) mean += v
        mean /= src.size
        val hann = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }
        val out = DoubleArray(src.size)
        for (y in 0 until size) for (x in 0 until size) out[y * size + x] = (src[y * size + x] - mean) * hann[x] * hann[y]
        return out
    }

    /** In-place 2D radix-2 FFT; [inverse] also divides by size^2. */
    internal fun fft2d(re: DoubleArray, im: DoubleArray, size: Int, inverse: Boolean) {
        val rr = DoubleArray(size); val ri = DoubleArray(size)
        for (y in 0 until size) {
            System.arraycopy(re, y * size, rr, 0, size); System.arraycopy(im, y * size, ri, 0, size)
            fft1d(rr, ri, inverse)
            System.arraycopy(rr, 0, re, y * size, size); System.arraycopy(ri, 0, im, y * size, size)
        }
        for (x in 0 until size) {
            for (y in 0 until size) { rr[y] = re[y * size + x]; ri[y] = im[y * size + x] }
            fft1d(rr, ri, inverse)
            for (y in 0 until size) { re[y * size + x] = rr[y]; im[y * size + x] = ri[y] }
        }
    }

    /** In-place iterative radix-2 FFT; the inverse uses the +j kernel and divides by n. */
    internal fun fft1d(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = i + k + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}
