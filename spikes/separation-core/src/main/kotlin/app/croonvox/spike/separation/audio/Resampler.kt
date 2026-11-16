// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Band-limited sample-rate conversion (polyphase, Kaiser-windowed sinc). The kernel is symmetric
 * around each output instant, so output frame `m` is input time `m / toRate` exactly: no lag.
 * Memory is bounded by the block size, not by the length of the audio.
 */
object Resampler {
    private const val ZERO_CROSSINGS = 24
    private const val KAISER_BETA = 9.0
    private const val BLOCK = 8192

    /** Frames needed at [toRate] to cover [frames] at [fromRate]. */
    fun framesAt(frames: Long, fromRate: Int, toRate: Int): Long = (frames * toRate + fromRate - 1) / fromRate

    /** Writes exactly [frames] frames of [source], converted to [toRate], to [sink]. */
    fun resample(source: StereoSource, toRate: Int, frames: Long, sink: StereoSink) {
        if (source.sampleRate == toRate) return copy(source, frames, sink)
        Kernel(source.sampleRate, toRate).run(source, frames, sink)
    }

    private fun copy(source: StereoSource, frames: Long, sink: StereoSink) {
        val l = FloatArray(BLOCK)
        val r = FloatArray(BLOCK)
        var done = 0L
        while (done < frames) {
            val n = minOf(BLOCK.toLong(), frames - done).toInt()
            source.read(done, l, r, n)
            sink.write(l, r, n)
            done += n
        }
    }

    private class Kernel(private val from: Int, private val to: Int) {
        private val gcd = gcd(from, to)
        private val phases = to / gcd
        private val cutoff = minOf(1.0, to.toDouble() / from) * 0.97 // fraction of the input Nyquist
        private val half = ceil(ZERO_CROSSINGS / cutoff).toInt() // taps on each side
        private val taps = 2 * half

        /** table[phase · taps + j] weights input `i0 − half + 1 + j` for fractional position phase/phases. */
        private val table = FloatArray(phases * taps).also { t ->
            for (p in 0 until phases) {
                val frac = p.toDouble() / phases
                for (j in 0 until taps) {
                    val x = (j - half + 1) - frac // distance from the output instant, in input samples
                    t[p * taps + j] = (cutoff * sinc(cutoff * x) * kaiser(x / half)).toFloat()
                }
            }
        }

        fun run(source: StereoSource, frames: Long, sink: StereoSink) {
            val outL = FloatArray(BLOCK)
            val outR = FloatArray(BLOCK)
            var inL = FloatArray(0)
            var inR = FloatArray(0)
            var m0 = 0L
            while (m0 < frames) {
                val n = minOf(BLOCK.toLong(), frames - m0).toInt()
                val inStart = (m0 * from) / to - half + 1
                val inCount = (((m0 + n - 1) * from) / to + half - inStart + 1).toInt()
                if (inL.size < inCount) {
                    inL = FloatArray(inCount)
                    inR = FloatArray(inCount)
                }
                source.read(inStart, inL, inR, inCount)
                for (k in 0 until n) {
                    val num = (m0 + k) * from
                    val base = (num / to - half + 1 - inStart).toInt()
                    val phase = ((num % to) / gcd).toInt()
                    var sl = 0f
                    var sr = 0f
                    val row = phase * taps
                    for (j in 0 until taps) {
                        val w = table[row + j]
                        sl += inL[base + j] * w
                        sr += inR[base + j] * w
                    }
                    outL[k] = sl
                    outR[k] = sr
                }
                sink.write(outL, outR, n)
                m0 += n
            }
        }

        private fun sinc(x: Double) = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        private fun kaiser(x: Double): Double {
            if (x <= -1.0 || x >= 1.0) return 0.0
            return besselI0(KAISER_BETA * sqrt(1 - x * x)) / besselI0(KAISER_BETA)
        }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > 1e-12 * sum) {
                term *= (x / (2 * k)) * (x / (2 * k))
                sum += term
                k++
            }
            return sum
        }

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
