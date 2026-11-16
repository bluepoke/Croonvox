// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals

class StftTest {
    @Test
    fun `a cosine on bin k has magnitude A·N÷4 there and A·N÷8 on its neighbours`() {
        // N = 24 is not a power of two (UVR models use 5120, 6144, 7680).
        val n = 24
        val k = 5
        val amplitude = 0.5f
        val signal = FloatArray(n * 8) { amplitude * cos(2 * PI * k * it / n).toFloat() }

        val spec = Stft(nFft = n, hop = n / 4).forward(signal)

        val frame = spec.frames / 2 // interior frame, away from the padded edges
        assertEquals(amplitude * n / 4, spec.magnitude(frame, k), 1e-4f)
        assertEquals(amplitude * n / 8, spec.magnitude(frame, k - 1), 1e-4f)
        assertEquals(amplitude * n / 8, spec.magnitude(frame, k + 1), 1e-4f)
        assertEquals(0f, spec.magnitude(frame, k + 3), 1e-4f)
    }

    @Test
    fun `centred frame t is centred on sample t·hop`() {
        val n = 16
        val hop = 4
        val signal = FloatArray(64).also { it[3 * hop] = 1f }

        val spec = Stft(nFft = n, hop = hop).forward(signal)

        assertEquals(1 + 64 / hop, spec.frames)
        // The impulse sits at the window centre (w = 1), offset n/2: X[k] = (−1)^k.
        for (k in 0 until spec.bins) {
            assertEquals(if (k % 2 == 0) 1f else -1f, spec.re(3, k), 1e-5f, "bin $k")
            assertEquals(0f, spec.im(3, k), 1e-5f, "bin $k")
        }
    }

    @Test
    fun `normalized scales every bin by N^-½`() {
        val signal = FloatArray(64).also { it[3 * 4] = 1f }

        val spec = Stft(nFft = 16, hop = 4, normalized = true).forward(signal)

        assertEquals(0.25f, spec.re(3, 0), 1e-6f)
        assertEquals(-0.25f, spec.re(3, 1), 1e-6f)
    }

    @Test
    fun `inverse of forward gives back the signal, at the requested length`() {
        val random = java.util.Random(7)
        val signal = FloatArray(1000) { random.nextGaussian().toFloat() }
        for (mode in PadMode.entries) {
            for (normalized in listOf(false, true)) {
                val stft = Stft(nFft = 24, hop = 6, padMode = mode, normalized = normalized)

                val back = stft.inverse(stft.forward(signal), length = signal.size)

                assertEquals(signal.size, back.size)
                for (i in signal.indices) assertEquals(signal[i], back[i], 1e-4f, "$mode normalized=$normalized sample $i")
            }
        }
    }

    private fun Spectrogram.magnitude(frame: Int, bin: Int) = hypot(re(frame, bin), im(frame, bin))
}
