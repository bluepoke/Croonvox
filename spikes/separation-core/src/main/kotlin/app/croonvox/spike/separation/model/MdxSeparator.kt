// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation.model

import app.croonvox.spike.separation.SegmentSeparator
import app.croonvox.spike.separation.StereoFrames
import app.croonvox.spike.separation.Stems
import app.croonvox.spike.separation.dsp.PadMode
import app.croonvox.spike.separation.dsp.Spectrogram
import app.croonvox.spike.separation.dsp.Stft

/** Which stem an MDX-Net model estimates; the other one is the input minus it. */
enum class PrimaryStem { Vocals, Instrumental }

/**
 * Parameters of one UVR MDX-Net model. UVR keeps them outside the ONNX file (`model_data.json`,
 * keyed by the MD5 of the file's last 10 000 KiB); `n_fft` is often not `2 · dim_f`.
 */
data class MdxConfig(
    val nFft: Int,
    val hop: Int = 1024,
    val dimF: Int,
    val dimT: Int = 256,
    val primary: PrimaryStem,
    /** UVR's `compensate`: gain applied to the model's estimate. */
    val compensate: Float,
)

/**
 * UVR MDX-Net (`UVR-MDX-NET-*.onnx`): one input and one output, both `[1, 4, dim_f, dim_t]`
 * (complex spectrogram as channels: L re, L im, R re, R im) of a centred, reflect-padded STFT.
 * Bins above `dim_f` are dropped on the way in and are zero on the way out.
 */
class MdxSeparator(private val model: TensorModel, private val config: MdxConfig) : SegmentSeparator {
    override val sampleRate = 44_100
    override val segmentFrames = config.hop * (config.dimT - 1)

    private val inputName = model.inputs.keys.single()
    private val outputName = model.outputs.keys.single()
    private val shape = longArrayOf(1, 4, config.dimF.toLong(), config.dimT.toLong())
    private val stft = Stft(config.nFft, config.hop, center = true, padMode = PadMode.Reflect)

    init {
        checkShape("MDX input", model.inputs.getValue(inputName), shape)
        require(config.dimF <= stft.bins) { "dim_f ${config.dimF} exceeds ${stft.bins} bins" }
    }

    override fun separate(input: StereoFrames): Stems {
        val specs = arrayOf(stft.forward(input.left), stft.forward(input.right))
        val x = FloatArray(4 * config.dimF * config.dimT)
        for (ch in 0..1) {
            pack(specs[ch].re, x, 2 * ch)
            pack(specs[ch].im, x, 2 * ch + 1)
        }
        val y = model.run(mapOf(inputName to Tensor(shape, x))).getValue(outputName)
        checkShape("MDX output", y.shape, shape)

        val estimate = Array(2) { ch ->
            val spec = Spectrogram(config.dimT, stft.bins)
            unpack(y.data, 2 * ch, spec.re)
            unpack(y.data, 2 * ch + 1, spec.im)
            stft.inverse(spec, segmentFrames).also { wave -> for (i in wave.indices) wave[i] *= config.compensate }
        }
        val primary = StereoFrames(estimate[0], estimate[1])
        val rest = StereoFrames(
            FloatArray(segmentFrames) { input.left[it] - primary.left[it] },
            FloatArray(segmentFrames) { input.right[it] - primary.right[it] },
        )
        return when (config.primary) {
            PrimaryStem.Instrumental -> Stems(vocals = rest, instrumental = primary)
            PrimaryStem.Vocals -> Stems(vocals = primary, instrumental = rest)
        }
    }

    /** Spectrogram part (frame-major) → tensor channel [c] (`[f][t]`). */
    private fun pack(part: FloatArray, x: FloatArray, c: Int) {
        val bins = stft.bins
        val base = c * config.dimF * config.dimT
        for (f in 0 until config.dimF) for (t in 0 until config.dimT) x[base + f * config.dimT + t] = part[t * bins + f]
    }

    private fun unpack(y: FloatArray, c: Int, part: FloatArray) {
        val bins = stft.bins
        val base = c * config.dimF * config.dimT
        for (f in 0 until config.dimF) for (t in 0 until config.dimT) part[t * bins + f] = y[base + f * config.dimT + t]
    }

    override fun close() = model.close()
}
