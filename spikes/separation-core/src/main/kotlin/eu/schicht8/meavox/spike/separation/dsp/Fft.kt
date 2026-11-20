// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.separation.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place complex DFT of a fixed [size] = r · 2^a (r odd). One Cooley–Tukey step splits it into r
 * radix-2 FFTs of length 2^a, joined by a direct DFT of length r. That is fast for the sizes the
 * models use (4096, 5120 = 5·2^10, 6144 = 3·2^11, 7680 = 15·2^9), and correct (but slow) for a
 * large odd r. Not thread-safe: it reuses work buffers.
 */
internal class Fft(val size: Int) {
    private val odd: Int
    private val pow2: Int

    init {
        require(size > 0) { "size must be positive" }
        var m = size
        while (m % 2 == 0) m /= 2
        odd = m
        pow2 = size / odd
    }

    private val bitReverse = Integer.numberOfTrailingZeros(pow2).let { bits ->
        IntArray(pow2) { if (bits == 0) 0 else Integer.reverse(it) ushr (32 - bits) }
    }
    private val pow2Cos = DoubleArray(pow2 / 2) { cos(-2 * PI * it / pow2) }
    private val pow2Sin = DoubleArray(pow2 / 2) { sin(-2 * PI * it / pow2) }
    private val oddCos = DoubleArray(odd) { cos(-2 * PI * it / odd) }
    private val oddSin = DoubleArray(odd) { sin(-2 * PI * it / odd) }
    private val twCos = DoubleArray(size) { cos(-2 * PI * it / size) }
    private val twSin = DoubleArray(size) { sin(-2 * PI * it / size) }

    private val workRe = DoubleArray(size)
    private val workIm = DoubleArray(size)

    /** X[k] = Σ x[n]·e^(−2πi·kn/N), or with [inverse] the conjugate sum (unscaled). */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val sign = if (inverse) -1.0 else 1.0
        val m = pow2
        // 1. Direct DFT of length r over n1, for every n2: Y[k1][n2] = Σ x[m·n1 + n2]·W_r^(n1·k1),
        //    then the twiddle W_N^(n2·k1). Stored at work[k1·m + n2].
        for (n2 in 0 until m) {
            for (k1 in 0 until odd) {
                var sr = 0.0
                var si = 0.0
                for (n1 in 0 until odd) {
                    val idx = (n1 * k1) % odd
                    val c = oddCos[idx]
                    val s = sign * oddSin[idx]
                    val xr = re[m * n1 + n2]
                    val xi = im[m * n1 + n2]
                    sr += xr * c - xi * s
                    si += xr * s + xi * c
                }
                val t = (n2 * k1) % size
                val c = twCos[t]
                val s = sign * twSin[t]
                workRe[k1 * m + n2] = sr * c - si * s
                workIm[k1 * m + n2] = sr * s + si * c
            }
        }
        // 2. Radix-2 FFT of length m for every k1: X[k1 + r·k2].
        for (k1 in 0 until odd) {
            radix2(k1 * m, sign)
            for (k2 in 0 until m) {
                re[k1 + odd * k2] = workRe[k1 * m + k2]
                im[k1 + odd * k2] = workIm[k1 * m + k2]
            }
        }
    }

    private fun radix2(offset: Int, sign: Double) {
        val m = pow2
        if (m == 1) return
        for (i in 0 until m) {
            val j = bitReverse[i]
            if (j > i) {
                val a = offset + i
                val b = offset + j
                val tr = workRe[a]; workRe[a] = workRe[b]; workRe[b] = tr
                val ti = workIm[a]; workIm[a] = workIm[b]; workIm[b] = ti
            }
        }
        var len = 2
        while (len <= m) {
            val half = len / 2
            val step = m / len
            var start = 0
            while (start < m) {
                for (j in 0 until half) {
                    val c = pow2Cos[j * step]
                    val s = sign * pow2Sin[j * step]
                    val a = offset + start + j
                    val b = a + half
                    val br = workRe[b] * c - workIm[b] * s
                    val bi = workRe[b] * s + workIm[b] * c
                    workRe[b] = workRe[a] - br
                    workIm[b] = workIm[a] - bi
                    workRe[a] += br
                    workIm[a] += bi
                }
                start += len
            }
            len *= 2
        }
    }
}
