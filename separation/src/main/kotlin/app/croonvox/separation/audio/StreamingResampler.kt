// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Band-limited sample-rate conversion (polyphase, Kaiser-windowed sinc) of interleaved audio that
 * arrives in blocks. The kernel is symmetric around each output instant, so output frame `m` is
 * input time `m / to` exactly: no lag. Input before the start and after the end reads as silence.
 * Memory is bounded by the kernel and the block size, not by the length of the audio.
 */
class StreamingResampler(
    private val from: Int,
    private val to: Int,
    private val channels: Int,
    private val out: FrameSink,
) {
    private val gcd = gcd(from, to)
    private val phases = to / gcd
    private val cutoff = minOf(1.0, to.toDouble() / from) * 0.97 // fraction of the input Nyquist
    private val half = if (from == to) 0 else ceil(ZERO_CROSSINGS / cutoff).toInt() // taps on each side
    private val taps = 2 * half

    /** table[phase · taps + j] weights input `i0 − half + 1 + j` for fractional position phase/phases. */
    private val table = FloatArray(phases * taps).also { t ->
        if (from == to) return@also
        for (p in 0 until phases) {
            val frac = p.toDouble() / phases
            for (j in 0 until taps) {
                val x = (j - half + 1) - frac // distance from the output instant, in input samples
                t[p * taps + j] = (cutoff * sinc(cutoff * x) * kaiser(x / half)).toFloat()
            }
        }
    }

    /** Input frames held: absolute input frame [bufferStart] is at index 0. Starts with the padding before 0. */
    private var buffer = FloatArray(maxOf(half - 1, 0) * channels + BLOCK * channels)
    private var bufferStart = -(maxOf(half - 1, 0)).toLong()
    private var bufferFrames = maxOf(half - 1, 0)
    private var nextOut = 0L
    private val outBlock = FloatArray(BLOCK * channels)
    private var finished = false

    fun write(interleaved: FloatArray, frames: Int) {
        check(!finished) { "already finished" }
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            append(interleaved, done, n)
            done += n
            emitAvailable(limit = Long.MAX_VALUE)
        }
    }

    /** Ends the input and writes the remaining output, so that exactly [outputFrames] frames were written. */
    fun finish(outputFrames: Long) {
        check(!finished) { "already finished" }
        finished = true
        require(outputFrames >= nextOut) { "already wrote $nextOut frames, more than $outputFrames" }
        val silence = FloatArray(BLOCK * channels)
        while (nextOut < outputFrames) {
            append(silence, 0, BLOCK)
            emitAvailable(limit = outputFrames)
        }
    }

    private fun append(interleaved: FloatArray, offset: Int, frames: Int) {
        compact()
        val needed = (bufferFrames + frames) * channels
        if (needed > buffer.size) buffer = buffer.copyOf(maxOf(needed, buffer.size * 2))
        interleaved.copyInto(buffer, bufferFrames * channels, offset * channels, (offset + frames) * channels)
        bufferFrames += frames
    }

    /** Drops input that no later output frame needs. */
    private fun compact() {
        val firstNeeded = firstInput(nextOut)
        val drop = (firstNeeded - bufferStart).coerceIn(0, bufferFrames.toLong()).toInt()
        if (drop == 0) return
        buffer.copyInto(buffer, 0, drop * channels, bufferFrames * channels)
        bufferFrames -= drop
        bufferStart += drop
    }

    /** Writes every output frame whose input is complete, up to (excluding) [limit]. */
    private fun emitAvailable(limit: Long) {
        val inputEnd = bufferStart + bufferFrames
        var n = 0
        while (nextOut < limit && lastInput(nextOut) < inputEnd) {
            val base = ((firstInput(nextOut) - bufferStart) * channels).toInt()
            val o = n * channels
            if (from == to) {
                for (c in 0 until channels) outBlock[o + c] = buffer[base + c]
            } else {
                val num = nextOut * from
                val row = ((num % to) / gcd).toInt() * taps
                for (c in 0 until channels) {
                    var sum = 0f
                    var i = base + c
                    for (j in 0 until taps) {
                        sum += buffer[i] * table[row + j]
                        i += channels
                    }
                    outBlock[o + c] = sum
                }
            }
            nextOut++
            if (++n == BLOCK) {
                out.write(outBlock, n)
                n = 0
            }
        }
        if (n > 0) out.write(outBlock, n)
    }

    /** First input frame that output frame [m] reads (the same frame when the rates match). */
    private fun firstInput(m: Long): Long = if (from == to) m else (m * from) / to - half + 1

    private fun lastInput(m: Long): Long = if (from == to) m else firstInput(m) + taps - 1

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

    companion object {
        private const val ZERO_CROSSINGS = 24
        private const val KAISER_BETA = 9.0
        private const val BLOCK = 8192

        /** Frames needed at [toRate] to cover [frames] at [fromRate]. */
        fun framesAt(frames: Long, fromRate: Int, toRate: Int): Long = (frames * toRate + fromRate - 1) / fromRate

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
