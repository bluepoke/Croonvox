// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation.model

import app.croonvox.spike.separation.StereoFrames
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DemucsSeparatorTest {
    // Miniature of htdemucs (segment 343 980, hop 1024, n_fft 4096): ⌈50 / 4⌉ = 13 frames, 8 bins.
    private val config = DemucsConfig(segment = 50, hop = 4, vocalsIndex = 3)
    private val sources = 4

    /** Two inputs and two outputs, named like sevagh/demucs.onnx's export; [compute] gets (mix, spec). */
    private inner class FakeDemucs(val compute: (Tensor, Tensor) -> Pair<Tensor, Tensor>) : TensorModel {
        override val inputs = mapOf("input" to longArrayOf(1, 2, 50), "x" to longArrayOf(1, 4, 8, 13))
        override val outputs = mapOf("output" to longArrayOf(1, 4, 4, 8, 13), "add_67" to longArrayOf(1, 4, 2, 50))
        lateinit var mix: Tensor
        lateinit var spec: Tensor

        override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
            mix = inputs.getValue("input")
            spec = inputs.getValue("x")
            val (freq, time) = compute(mix, spec)
            return mapOf("output" to freq, "add_67" to time)
        }

        override fun close() {}
    }

    private fun zeroFreq() = Tensor(longArrayOf(1, 4, 4, 8, 13), FloatArray(4 * 4 * 8 * 13))
    private fun zeroTime() = Tensor(longArrayOf(1, 4, 2, 50), FloatArray(4 * 2 * 50))

    @Test
    fun `the model sees the waveform and the normalized spectrogram, L re, L im, R re, R im`() {
        val n = 16
        val k = 4 // phase 0 at every frame centre (j + ½) · hop
        val amplitude = 0.5f
        val left = FloatArray(50) { amplitude * cos(2 * PI * k * (it - 2) / n).toFloat() }
        val model = FakeDemucs { _, _ -> zeroFreq() to zeroTime() }

        DemucsSeparator(model, config).use { it.separate(StereoFrames(left, FloatArray(50))) }

        assertContentEquals(longArrayOf(1, 2, 50), model.mix.shape)
        for (i in 0 until 50) assertEquals(left[i], model.mix[0, 0, i])
        assertContentEquals(longArrayOf(1, 4, 8, 13), model.spec.shape)
        val expected = amplitude * n / 4 / sqrt(n.toFloat())
        for (t in 2..9) { // frames whose window stays inside the segment
            assertEquals(expected, model.spec[0, 0, k, t], 1e-4f, "L re, frame $t")
            assertEquals(0f, model.spec[0, 1, k, t], 1e-4f, "L im, frame $t")
            assertEquals(0f, model.spec[0, 0, k + 2, t], 1e-4f, "L re off-bin, frame $t")
        }
        for (c in 2..3) for (f in 0 until 8) for (t in 0 until 13) assertEquals(0f, model.spec[0, c, f, t], "silent right")
    }

    @Test
    fun `the vocals source of the time branch is the Vocal Stem, the rest the Instrumental`() {
        val random = java.util.Random(5)
        val left = FloatArray(50) { random.nextGaussian().toFloat() }
        val right = FloatArray(50) { random.nextGaussian().toFloat() }
        val model = FakeDemucs { mix, _ ->
            val time = FloatArray(sources * 2 * 50) { 7f } // other sources: ignored
            mix.data.copyInto(time, destinationOffset = 3 * 2 * 50)
            zeroFreq() to Tensor(longArrayOf(1, 4, 2, 50), time)
        }

        val stems = DemucsSeparator(model, config).use { it.separate(StereoFrames(left, right)) }

        for (i in 0 until 50) {
            assertEquals(left[i], stems.vocals.left[i], 1e-6f, "vocals left $i")
            assertEquals(right[i], stems.vocals.right[i], 1e-6f, "vocals right $i")
            assertEquals(0f, stems.instrumental.left[i], 1e-6f, "instrumental left $i")
        }
    }

    @Test
    fun `the frequency branch is inverted with the same framing and added`() {
        val left = FloatArray(50) { cos(2 * PI * 2 * it / 16).toFloat() }
        val right = FloatArray(50) { 0.5f * cos(2 * PI * 1 * it / 16).toFloat() }
        val model = FakeDemucs { _, spec ->
            val freq = FloatArray(sources * spec.data.size)
            for (s in 0 until sources) spec.data.copyInto(freq, s * spec.data.size)
            Tensor(longArrayOf(1, 4, 4, 8, 13), freq) to zeroTime()
        }

        val stems = DemucsSeparator(model, config).use { it.separate(StereoFrames(left, right)) }

        for (i in 12 until 38) { // Demucs zeroes the 2 outer frames on each side: edges are attenuated
            assertEquals(left[i], stems.vocals.left[i], 2e-3f, "vocals left $i")
            assertEquals(right[i], stems.vocals.right[i], 2e-3f, "vocals right $i")
        }
    }
}
