// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingResamplerTest {
    private fun tone(rate: Int, frames: Int, phase: Double = 0.0) = FloatArray(frames) {
        val t = it.toDouble() / rate
        (0.5 * sin(2 * PI * 1000 * t + phase) + 0.25 * sin(2 * PI * 7000 * t)).toFloat()
    }

    /** Pushes [channels] (planar) through a resampler in uneven blocks and collects the output. */
    private fun resample(from: Int, to: Int, channels: List<FloatArray>, outFrames: Long): List<FloatArray> {
        val out = InterleavedBuffer(channels.size)
        val resampler = StreamingResampler(from, to, channels.size, out)
        val frames = channels[0].size
        var done = 0
        var block = 1
        while (done < frames) {
            val n = minOf(block, frames - done)
            val interleaved = FloatArray(n * channels.size) { channels[it % channels.size][done + it / channels.size] }
            resampler.write(interleaved, n)
            done += n
            block = block * 3 + 7 // uneven sizes, from 1 frame to large blocks
        }
        resampler.finish(outFrames)
        return List(channels.size) { out.channel(it) }
    }

    @Test
    fun `resampling a tone gives the same tone sampled at the new rate, with no lag`() {
        val (left, right) = resample(48_000, 44_100, listOf(tone(48_000, 48_000), tone(48_000, 48_000, phase = 1.0)), 44_100)

        assertEquals(44_100, left.size)
        val expectedLeft = tone(44_100, 44_100)
        val expectedRight = tone(44_100, 44_100, phase = 1.0)
        for (i in 2_000 until 42_100) { // edges see the zero padding outside the input
            assertEquals(expectedLeft[i], left[i], 2e-3f, "left $i")
            assertEquals(expectedRight[i], right[i], 2e-3f, "right $i")
        }
    }

    @Test
    fun `resampling there and back gives the original length and samples`() {
        val original = tone(48_000, 30_001)
        val at44 = resample(48_000, 44_100, listOf(original), StreamingResampler.framesAt(30_001, 48_000, 44_100))

        val (back) = resample(44_100, 48_000, at44, 30_001)

        assertEquals(30_001, back.size)
        for (i in 2_000 until 28_000) assertEquals(original[i], back[i], 3e-3f, "sample $i")
    }

    @Test
    fun `the same rate copies the input, padded to the requested length`() {
        val samples = tone(44_100, 5_000)

        val (same) = resample(44_100, 44_100, listOf(samples), 5_000)
        val (padded) = resample(44_100, 44_100, listOf(samples), 5_010)

        assertEquals(samples.toList(), same.toList())
        assertEquals(samples.toList() + List(10) { 0f }, padded.toList())
    }
}
