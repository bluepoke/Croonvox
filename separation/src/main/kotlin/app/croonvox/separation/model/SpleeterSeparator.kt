// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation.model

import app.croonvox.separation.SegmentSeparator
import app.croonvox.separation.SegmentStems
import app.croonvox.separation.StereoFrames
import app.croonvox.separation.dsp.PadMode
import app.croonvox.separation.dsp.Spectrogram
import app.croonvox.separation.dsp.Stft
import kotlin.math.sqrt

/** Spleeter's STFT layout. The models only see the lowest [bins] bins (11 kHz at 44.1 kHz). */
data class SpleeterConfig(val nFft: Int = 4096, val hop: Int = 1024, val frames: Int = 512, val bins: Int = 1024)

/**
 * Deezer Spleeter 2-stems as exported by sherpa-onnx (`vocals.onnx`, `accompaniment.onnx`). Each
 * U-Net maps the magnitude `[2, splits, frames, bins]` to its own source's magnitude; as in
 * Spleeter, a ratio mask `v² / (v² + a²)` on the mixture's complex STFT gives the Vocal Stem. The
 * Instrumental is the input minus the Vocal Stem, so it keeps everything above [SpleeterConfig.bins]
 * (Spleeter 2-stems only separates up to 11 kHz).
 */
class SpleeterSeparator(
    private val vocalsModel: TensorModel,
    private val accompanimentModel: TensorModel,
    private val config: SpleeterConfig = SpleeterConfig(),
) : SegmentSeparator {
    override val sampleRate = 44_100
    override val segmentFrames = config.hop * (config.frames - 1)

    private val shape = longArrayOf(2, 1, config.frames.toLong(), config.bins.toLong())
    private val stft = Stft(config.nFft, config.hop, center = true, padMode = PadMode.Zero)

    init {
        for (m in listOf(vocalsModel, accompanimentModel)) checkShape("Spleeter input", m.inputs.values.single(), shape)
        require(config.bins <= stft.bins) { "bins ${config.bins} exceeds ${stft.bins}" }
    }

    override fun separate(input: StereoFrames): SegmentStems {
        val specs = arrayOf(stft.forward(input.left), stft.forward(input.right))
        val bins = config.bins
        val plane = config.frames * bins
        val x = FloatArray(2 * plane)
        for (ch in 0..1) {
            val s = specs[ch]
            for (t in 0 until config.frames) for (f in 0 until bins) {
                val re = s.re(t, f)
                val im = s.im(t, f)
                x[ch * plane + t * bins + f] = sqrt(re * re + im * im)
            }
        }
        val v = run(vocalsModel, x)
        val a = run(accompanimentModel, x)

        val vocals = Array(2) { ch ->
            val s = specs[ch]
            val masked = Spectrogram(config.frames, stft.bins)
            for (t in 0 until config.frames) for (f in 0 until bins) {
                val i = ch * plane + t * bins + f
                val v2 = v[i] * v[i]
                val mask = (v2 + EPS / 2) / (v2 + a[i] * a[i] + EPS)
                masked.re[t * stft.bins + f] = s.re(t, f) * mask
                masked.im[t * stft.bins + f] = s.im(t, f) * mask
            }
            stft.inverse(masked, segmentFrames)
        }
        return SegmentStems(
            vocals = StereoFrames(vocals[0], vocals[1]),
            instrumental = StereoFrames(
                FloatArray(segmentFrames) { input.left[it] - vocals[0][it] },
                FloatArray(segmentFrames) { input.right[it] - vocals[1][it] },
            ),
        )
    }

    private fun run(model: TensorModel, x: FloatArray): FloatArray {
        val y = model.run(mapOf(model.inputs.keys.single() to Tensor(shape, x))).values.single()
        checkShape("Spleeter output", y.shape, shape)
        return y.data
    }

    override fun close() {
        vocalsModel.close()
        accompanimentModel.close()
    }

    private companion object {
        const val EPS = 1e-10f
    }
}
