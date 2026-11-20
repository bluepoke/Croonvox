// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.separation.model

import eu.schicht8.meavox.spike.separation.StereoFrames
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MdxSeparatorTest {
    // Miniature of UVR's layout (n_fft 6144, hop 1024, dim_f 3072, dim_t 256), same structure.
    private val config = MdxConfig(nFft = 24, hop = 6, dimF = 12, dimT = 9, primary = PrimaryStem.Instrumental, compensate = 1f)
    private val segment = config.hop * (config.dimT - 1)

    @Test
    fun `segments are hop · (dim_t − 1) frames, as in UVR`() {
        MdxSeparator(FakeTensorModel { it }, config).use { assertEquals(48, it.segmentFrames) }
    }

    @Test
    fun `the model sees the spectrogram as 1 × 4 × dim_f × dim_t = L re, L im, R re, R im`() {
        val k = 4 // a multiple of 4 with hop = n_fft / 4: the cosine has phase 0 at every frame centre
        val amplitude = 0.5f
        val left = FloatArray(segment) { amplitude * cos(2 * PI * k * it / config.nFft).toFloat() }
        val model = FakeTensorModel { it }

        MdxSeparator(model, config).use { it.separate(StereoFrames(left, FloatArray(segment))) }

        val x = model.lastInputs.values.single()
        assertContentEquals(longArrayOf(1, 4, 12, 9), x.shape)
        val expected = amplitude * config.nFft / 4
        for (t in 2..6) { // interior frames
            assertEquals(expected, x[0, 0, k, t], 1e-4f, "L re, frame $t")
            assertEquals(0f, x[0, 1, k, t], 1e-4f, "L im, frame $t")
            assertEquals(0f, x[0, 0, k + 2, t], 1e-4f, "L re off-bin, frame $t")
        }
        for (c in 2..3) for (f in 0 until 12) for (t in 0 until 9) assertEquals(0f, x[0, c, f, t], "silent right channel")
    }

    @Test
    fun `an Instrumental model's output times compensate is the Instrumental, the rest the Vocal Stem`() {
        val left = FloatArray(segment) { cos(2 * PI * 2 * it / config.nFft).toFloat() }
        val right = FloatArray(segment) { 0.5f * cos(2 * PI * 3 * it / config.nFft).toFloat() }
        val input = StereoFrames(left, right)

        val stems = MdxSeparator(FakeTensorModel { it }, config.copy(compensate = 1.25f)).use { it.separate(input) }

        for (i in 12 until 36) { // away from the reflect-padded edges
            assertEquals(1.25f * left[i], stems.instrumental.left[i], 2e-3f, "instrumental left $i")
            assertEquals(1.25f * right[i], stems.instrumental.right[i], 2e-3f, "instrumental right $i")
        }
        for (i in 0 until segment) {
            assertEquals(left[i], stems.vocals.left[i] + stems.instrumental.left[i], 1e-5f, "stems sum to the input at $i")
            assertEquals(right[i], stems.vocals.right[i] + stems.instrumental.right[i], 1e-5f, "stems sum to the input at $i")
        }
    }

    @Test
    fun `a Vocals model's output is the Vocal Stem`() {
        val left = FloatArray(segment) { cos(2 * PI * 2 * it / config.nFft).toFloat() }
        val input = StereoFrames(left, left.copyOf())

        val stems = MdxSeparator(FakeTensorModel { it }, config.copy(primary = PrimaryStem.Vocals)).use { it.separate(input) }

        for (i in 12 until 36) {
            assertEquals(left[i], stems.vocals.left[i], 2e-3f, "vocals $i")
            assertEquals(0f, stems.instrumental.left[i], 2e-3f, "instrumental $i")
        }
    }
}
