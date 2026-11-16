// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation

import app.croonvox.spike.separation.audio.StereoSink
import app.croonvox.spike.separation.audio.StereoSource

/**
 * Runs a [SegmentSeparator] over a whole song: overlapping segments, cross-faded with triangular
 * weights (as Demucs' `apply_model` does), written to the sinks in order. Memory is bounded by the
 * segment length, so long songs don't run out of memory. Audio past the end of the source reads
 * as silence; the sinks receive exactly `source.frames` frames.
 */
object ChunkedSeparation {
    /**
     * [overlap] is the fraction of a segment shared with the next one (0 until 1). [onProgress]
     * receives (segments done, segments total) after each segment.
     */
    fun run(
        source: StereoSource,
        separator: SegmentSeparator,
        vocals: StereoSink,
        instrumental: StereoSink,
        overlap: Float = 0.25f,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        require(source.sampleRate == separator.sampleRate) {
            "source is ${source.sampleRate} Hz, the model needs ${separator.sampleRate} Hz"
        }
        require(overlap >= 0f && overlap < 1f) { "overlap must be in 0 until 1" }
        val size = separator.segmentFrames
        val hop = (size * (1 - overlap)).toInt().coerceIn(1, size)
        val total = ((source.frames + hop - 1) / hop).toInt()
        val weight = FloatArray(size) { minOf(it + 1, size - it).toFloat() }

        val acc = Accumulator(size)
        val input = StereoFrames.silence(size)
        var written = 0L
        for (k in 0 until total) {
            val start = k.toLong() * hop
            source.read(start, input.left, input.right, size)
            val stems = separator.separate(input)
            acc.add(stems, weight)
            // Nothing after segment k touches [start, start + hop): emit it.
            val n = minOf(hop.toLong(), source.frames - written).toInt()
            acc.emit(n, vocals, instrumental)
            acc.shift(hop)
            written += n
            onProgress(k + 1, total)
        }
    }

    /** Weighted sums for the frames `[start, start + size)` of the current segment. */
    private class Accumulator(private val size: Int) {
        private val sums = Array(4) { FloatArray(size) } // vocals L/R, instrumental L/R
        private val weights = FloatArray(size)
        private val out = Array(4) { FloatArray(size) }

        fun add(stems: Stems, weight: FloatArray) {
            val parts = arrayOf(stems.vocals.left, stems.vocals.right, stems.instrumental.left, stems.instrumental.right)
            for (c in 0 until 4) {
                val part = parts[c]
                require(part.size == size) { "separator returned ${part.size} frames, expected $size" }
                val sum = sums[c]
                for (i in 0 until size) sum[i] += part[i] * weight[i]
            }
            for (i in 0 until size) weights[i] += weight[i]
        }

        fun emit(count: Int, vocals: StereoSink, instrumental: StereoSink) {
            for (c in 0 until 4) for (i in 0 until count) out[c][i] = sums[c][i] / weights[i]
            vocals.write(out[0], out[1], count)
            instrumental.write(out[2], out[3], count)
        }

        fun shift(hop: Int) {
            for (c in 0 until 4) {
                sums[c].copyInto(sums[c], 0, hop, size)
                sums[c].fill(0f, size - hop, size)
            }
            weights.copyInto(weights, 0, hop, size)
            weights.fill(0f, size - hop, size)
        }
    }
}
