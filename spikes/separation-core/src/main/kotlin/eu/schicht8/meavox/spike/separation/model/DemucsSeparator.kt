// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.separation.model

import eu.schicht8.meavox.spike.separation.SegmentSeparator
import eu.schicht8.meavox.spike.separation.StereoFrames
import eu.schicht8.meavox.spike.separation.Stems
import eu.schicht8.meavox.spike.separation.dsp.PadMode
import eu.schicht8.meavox.spike.separation.dsp.Spectrogram
import eu.schicht8.meavox.spike.separation.dsp.Stft

/** htdemucs: 7.8 s segments at 44.1 kHz, n_fft = 4 · hop; vocals are source 3 in the 4- and 6-source models. */
data class DemucsConfig(val segment: Int = 343_980, val hop: Int = 1024, val vocalsIndex: Int = 3)

/**
 * Demucs v4 (hybrid transformer) as exported by sevagh/demucs.onnx: the STFT and iSTFT are moved
 * out of the network, so this adapter does them (`standalone_spec` / `standalone_ispec`).
 * Inputs: the waveform `[1, 2, segment]` (rank 3) and the complex-as-channels spectrogram
 * `[1, 4, n_fft/2, ⌈segment/hop⌉]` (rank 4). Outputs: the frequency branch `[1, S, 4, F, T]`
 * (rank 5) and the time branch `[1, S, 2, segment]` (rank 4), summed after the iSTFT. Tensors are
 * told apart by rank, because the export names only the first input and output.
 */
class DemucsSeparator(private val model: TensorModel, private val config: DemucsConfig = DemucsConfig()) : SegmentSeparator {
    override val sampleRate = 44_100
    override val segmentFrames = config.segment

    private val hop = config.hop
    private val bins = 2 * hop // n_fft / 2: the Nyquist bin is dropped
    private val frames = (config.segment + hop - 1) / hop
    private val pad = hop / 2 * 3
    private val stft = Stft(nFft = 4 * hop, hop = hop, center = true, padMode = PadMode.Reflect, normalized = true)

    private val waveName = nameOfRank(model.inputs, 3)
    private val specName = nameOfRank(model.inputs, 4)
    private val freqName = nameOfRank(model.outputs, 5)
    private val timeName = nameOfRank(model.outputs, 4)

    init {
        checkShape("Demucs waveform input", model.inputs.getValue(waveName), longArrayOf(1, 2, config.segment.toLong()))
        checkShape("Demucs spectrogram input", model.inputs.getValue(specName), longArrayOf(1, 4, bins.toLong(), frames.toLong()))
    }

    override fun separate(input: StereoFrames): Stems {
        val n = config.segment
        val wave = FloatArray(2 * n).also {
            input.left.copyInto(it, 0)
            input.right.copyInto(it, n)
        }
        val plane = bins * frames
        val spec = FloatArray(4 * plane)
        for ((ch, signal) in listOf(input.left, input.right).withIndex()) {
            val z = stft.forward(reflectPadded(signal))
            for (f in 0 until bins) for (t in 0 until frames) {
                spec[(2 * ch) * plane + f * frames + t] = z.re(t + 2, f)
                spec[(2 * ch + 1) * plane + f * frames + t] = z.im(t + 2, f)
            }
        }
        val out = model.run(
            mapOf(
                waveName to Tensor(longArrayOf(1, 2, n.toLong()), wave),
                specName to Tensor(longArrayOf(1, 4, bins.toLong(), frames.toLong()), spec),
            ),
        )
        val freq = out.getValue(freqName)
        val time = out.getValue(timeName)
        val sources = freq.shape[1].toInt()
        require(config.vocalsIndex < sources) { "vocals index ${config.vocalsIndex}, model has $sources sources" }
        checkShape("Demucs frequency output", freq.shape, longArrayOf(1, sources.toLong(), 4, bins.toLong(), frames.toLong()))
        checkShape("Demucs time output", time.shape, longArrayOf(1, sources.toLong(), 2, n.toLong()))

        val v = config.vocalsIndex
        val vocals = Array(2) { ch ->
            val z = Spectrogram(frames + 4, stft.bins) // frames 0, 1 and the last 2 stay 0, as in Demucs
            val base = (v * 4 + 2 * ch) * plane
            for (f in 0 until bins) for (t in 0 until frames) {
                z.re[(t + 2) * stft.bins + f] = freq.data[base + f * frames + t]
                z.im[(t + 2) * stft.bins + f] = freq.data[base + plane + f * frames + t]
            }
            val x = stft.inverse(z, hop * frames + 2 * pad)
            val timeBase = (v * 2 + ch) * n
            FloatArray(n) { x[pad + it] + time.data[timeBase + it] }
        }
        return Stems(
            vocals = StereoFrames(vocals[0], vocals[1]),
            instrumental = StereoFrames(
                FloatArray(n) { input.left[it] - vocals[0][it] },
                FloatArray(n) { input.right[it] - vocals[1][it] },
            ),
        )
    }

    /** `pad1d(x, (pad, pad + frames · hop − length), mode="reflect")`. */
    private fun reflectPadded(x: FloatArray): FloatArray {
        val last = x.size - 1
        return FloatArray(pad + frames * hop + pad) {
            val i = it - pad
            x[if (i < 0) -i else if (i > last) 2 * last - i else i]
        }
    }

    override fun close() = model.close()

    private companion object {
        fun nameOfRank(tensors: Map<String, LongArray>, rank: Int): String =
            tensors.entries.singleOrNull { it.value.size == rank }?.key
                ?: error("expected one rank-$rank tensor, model has ${tensors.mapValues { it.value.contentToString() }}")
    }
}
