// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.separation.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class ResamplerTest {
    private fun tone(rate: Int, frames: Int, phase: Double = 0.0) = FloatArray(frames) {
        val t = it.toDouble() / rate
        (0.5 * sin(2 * PI * 1000 * t + phase) + 0.25 * sin(2 * PI * 7000 * t)).toFloat()
    }

    @Test
    fun `resampling a tone gives the same tone sampled at the new rate, with no lag`() {
        val source = StereoBuffer(48_000, tone(48_000, 48_000), tone(48_000, 48_000, phase = 1.0))
        val sink = StereoBuffer(44_100)

        Resampler.resample(source, toRate = 44_100, frames = 44_100, sink)

        assertEquals(44_100L, sink.frames)
        val left = tone(44_100, 44_100)
        val right = tone(44_100, 44_100, phase = 1.0)
        for (i in 2_000 until 42_100) { // edges see the zero padding outside the source
            assertEquals(left[i], sink.left[i], 2e-3f, "left $i")
            assertEquals(right[i], sink.right[i], 2e-3f, "right $i")
        }
    }

    @Test
    fun `resampling back gives the original length and samples`() {
        val original = tone(48_000, 30_001)
        val source = StereoBuffer(48_000, original, original)
        val at44 = StereoBuffer(44_100)
        Resampler.resample(source, toRate = 44_100, frames = Resampler.framesAt(30_001, 48_000, 44_100), at44)
        val back = StereoBuffer(48_000)

        Resampler.resample(at44, toRate = 48_000, frames = 30_001, back)

        assertEquals(30_001L, back.frames)
        for (i in 2_000 until 28_000) assertEquals(original[i], back.left[i], 3e-3f, "sample $i")
    }

    @Test
    fun `the same rate copies the source`() {
        val samples = tone(44_100, 5_000)
        val sink = StereoBuffer(44_100)

        Resampler.resample(StereoBuffer(44_100, samples, samples), toRate = 44_100, frames = 5_000, sink)

        assertEquals(samples.toList(), sink.left.toList())
    }
}
