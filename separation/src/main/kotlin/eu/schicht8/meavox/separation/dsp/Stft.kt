// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.separation.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** One channel's complex spectrogram, frame-major: bin `k` of frame `t` is at `t · bins + k`. */
class Spectrogram(val frames: Int, val bins: Int) {
    val re = FloatArray(frames * bins)
    val im = FloatArray(frames * bins)

    fun re(frame: Int, bin: Int) = re[frame * bins + bin]
    fun im(frame: Int, bin: Int) = im[frame * bins + bin]
}

/** How [Stft] pads the signal by `nFft / 2` on each side when [Stft.center] is set. */
enum class PadMode { Reflect, Zero }

/**
 * Short-time Fourier transform with the semantics of `torch.stft` / `torch.istft` (periodic Hann
 * window of length [nFft], `onesided`), which the models were trained with. Not thread-safe.
 */
class Stft(
    val nFft: Int,
    val hop: Int,
    val center: Boolean = true,
    val padMode: PadMode = PadMode.Reflect,
    /** Scales the forward transform by `nFft^-½` (and the inverse back), like `normalized=True`. */
    val normalized: Boolean = false,
) {
    val bins = nFft / 2 + 1
    private val window = FloatArray(nFft) { (0.5 - 0.5 * cos(2 * PI * it / nFft)).toFloat() }
    private val scale = if (normalized) (1 / sqrt(nFft.toDouble())) else 1.0
    private val fft = Fft(nFft)
    private val re = DoubleArray(nFft)
    private val im = DoubleArray(nFft)

    fun frameCount(length: Int): Int = if (center) 1 + length / hop else 1 + (length - nFft) / hop

    fun forward(signal: FloatArray): Spectrogram {
        val pad = if (center) nFft / 2 else 0
        val spec = Spectrogram(frameCount(signal.size), bins)
        for (t in 0 until spec.frames) {
            val start = t * hop - pad
            for (n in 0 until nFft) {
                re[n] = sample(signal, start + n) * window[n].toDouble()
                im[n] = 0.0
            }
            fft.transform(re, im)
            val base = t * bins
            for (k in 0 until bins) {
                spec.re[base + k] = (re[k] * scale).toFloat()
                spec.im[base + k] = (im[k] * scale).toFloat()
            }
        }
        return spec
    }

    /**
     * Windowed overlap-add of the inverse frames, divided by the summed squared window, like
     * `torch.istft(..., length = length)`. Samples the frames don't cover are 0.
     */
    fun inverse(spec: Spectrogram, length: Int): FloatArray {
        require(spec.bins == bins) { "expected $bins bins, got ${spec.bins}" }
        val pad = if (center) nFft / 2 else 0
        val out = FloatArray(length)
        val envelope = FloatArray(length)
        val unscale = 1 / scale / nFft
        for (t in 0 until spec.frames) {
            val base = t * bins
            for (k in 0 until bins) {
                re[k] = spec.re[base + k].toDouble()
                im[k] = spec.im[base + k].toDouble()
            }
            for (k in 1 until nFft - bins + 1) { // Hermitian mirror; imag of DC and Nyquist is ignored
                re[nFft - k] = re[k]
                im[nFft - k] = -im[k]
            }
            fft.transform(re, im, inverse = true)
            val start = t * hop - pad
            for (n in 0 until nFft) {
                val i = start + n
                if (i < 0 || i >= length) continue
                val w = window[n]
                out[i] += (re[n] * unscale).toFloat() * w
                envelope[i] += w * w
            }
        }
        for (i in 0 until length) out[i] = if (envelope[i] > 1e-11f) out[i] / envelope[i] else 0f
        return out
    }

    private fun sample(signal: FloatArray, index: Int): Float {
        if (index in signal.indices) return signal[index]
        return when (padMode) {
            PadMode.Zero -> 0f
            PadMode.Reflect -> {
                val last = signal.size - 1
                val reflected = if (index < 0) -index else 2 * last - index
                signal[reflected]
            }
        }
    }
}
