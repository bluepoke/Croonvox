// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation.model

import app.croonvox.separation.StereoFrames
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SpleeterSeparatorTest {
    // Miniature of Spleeter's layout (n_fft 4096, hop 1024, 512 frames, 1024 bins), same structure.
    private val config = SpleeterConfig(nFft = 16, hop = 4, frames = 17, bins = 6)
    private val segment = config.hop * (config.frames - 1)

    private fun echo() = FakeTensorModel { it }
    private fun silent() = FakeTensorModel { Tensor(it.shape, FloatArray(it.data.size)) }

    @Test
    fun `both models see the magnitude as 2 channels × 1 split × frames × bins`() {
        val k = 2
        val amplitude = 0.5f
        val left = FloatArray(segment) { amplitude * cos(2 * PI * k * it / config.nFft).toFloat() }
        val vocals = echo()
        val accompaniment = echo()

        SpleeterSeparator(vocals, accompaniment, config).use { it.separate(StereoFrames(left, FloatArray(segment))) }

        val x = vocals.lastInputs.values.single()
        assertContentEquals(longArrayOf(2, 1, 17, 6), x.shape)
        assertContentEquals(x.data, accompaniment.lastInputs.values.single().data)
        for (t in 2..14) {
            assertEquals(amplitude * config.nFft / 4, x[0, 0, t, k], 1e-4f, "left, frame $t")
            assertEquals(0f, x[0, 0, t, k + 2], 1e-4f, "left off-bin, frame $t")
        }
        for (t in 0 until 17) for (f in 0 until 6) assertEquals(0f, x[1, 0, t, f], "silent right channel")
    }

    @Test
    fun `the vocal mask takes what the vocals model claims, the Instrumental is the rest`() {
        val left = FloatArray(segment) { cos(2 * PI * 2 * it / config.nFft).toFloat() }
        val right = FloatArray(segment) { 0.5f * cos(2 * PI * 1 * it / config.nFft).toFloat() }
        val input = StereoFrames(left, right)

        val allVocals = SpleeterSeparator(echo(), silent(), config).use { it.separate(input) }
        val noVocals = SpleeterSeparator(silent(), echo(), config).use { it.separate(input) }

        for (i in 16 until 48) {
            assertEquals(left[i], allVocals.vocals.left[i], 2e-3f, "vocals left $i")
            assertEquals(right[i], allVocals.vocals.right[i], 2e-3f, "vocals right $i")
            assertEquals(0f, allVocals.instrumental.left[i], 2e-3f, "instrumental left $i")
        }
        for (i in 0 until segment) {
            assertEquals(0f, noVocals.vocals.left[i], 1e-6f, "vocals $i")
            assertEquals(left[i], noVocals.instrumental.left[i], 1e-6f, "instrumental $i")
            assertEquals(right[i], noVocals.instrumental.right[i], 1e-6f, "instrumental $i")
        }
    }
}
