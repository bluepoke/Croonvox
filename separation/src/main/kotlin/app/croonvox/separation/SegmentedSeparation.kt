// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation

/**
 * Runs a [SegmentSeparator] over a whole song that arrives in blocks: overlapping segments,
 * cross-faded with triangular weights (as Demucs' `apply_model` does). Memory is bounded by the
 * segment length, so long songs don't run out of memory.
 *
 * Segment `k` starts at input frame `k · hop`. As soon as it is separated, the frames
 * `[k · hop, k · hop + hop)` are final and go to [out] as segment `k`. A run that resumes with
 * [firstSegment] (the segments before it were stored by an earlier run) skips the model for
 * them, except for the few that overlap the first new one, so its output continues exactly
 * where the stored segments end (ADR 15).
 */
class SegmentedSeparation(
    private val separator: SegmentSeparator,
    private val out: Output,
    private val firstSegment: Int = 0,
    overlap: Float = 0.25f,
) {
    fun interface Output {
        /** The final frames of segment [index]: interleaved Instrumental L, R, Vocal Stem L, R. */
        fun segment(index: Int, interleaved: FloatArray, frames: Int)
    }

    private val size = separator.segmentFrames
    val hop = (size * (1 - overlap)).toInt().coerceIn(1, size)

    init {
        require(overlap >= 0f && overlap < 1f) { "overlap must be in 0 until 1" }
        require(firstSegment >= 0) { "firstSegment must not be negative" }
    }

    /** Segments before [firstSegment] that overlap it: separated again, but not emitted. */
    private val warmUp = (size + hop - 1) / hop - 1
    private var segment = maxOf(0, firstSegment - warmUp)

    /** Input frames received so far (absolute), and the input window of [segment]. */
    private var received = 0L
    private val left = FloatArray(size)
    private val right = FloatArray(size)
    private var filled = 0

    private val weight = FloatArray(size) { minOf(it + 1, size - it).toFloat() }
    private val sums = Array(4) { FloatArray(size) } // instrumental L/R, vocals L/R
    private val weights = FloatArray(size)
    private val emitted = FloatArray(hop * 4)
    private var finished = false

    /** Adds [frames] interleaved stereo frames at the separator's sample rate. */
    fun write(stereo: FloatArray, frames: Int) {
        check(!finished) { "already finished" }
        for (i in 0 until frames) {
            val position = received++
            val offset = position - segment.toLong() * hop
            if (offset < 0) continue // before the first segment this run separates
            left[filled] = stereo[2 * i]
            right[filled] = stereo[2 * i + 1]
            filled++
            if (filled == size) separateCurrent(emit = hop)
        }
    }

    /** Ends the input: separates the remaining segments, padded with silence, up to [totalFrames]. */
    fun finish(totalFrames: Long) {
        check(!finished) { "already finished" }
        finished = true
        while (segment.toLong() * hop < totalFrames) {
            left.fill(0f, filled, size)
            right.fill(0f, filled, size)
            filled = size
            separateCurrent(emit = minOf(hop.toLong(), totalFrames - segment.toLong() * hop).toInt())
        }
    }

    private fun separateCurrent(emit: Int) {
        if (Thread.interrupted()) throw InterruptedException("Separation cancelled")
        val stems = separator.separate(StereoFrames(left.copyOf(), right.copyOf()))
        val parts = arrayOf(stems.instrumental.left, stems.instrumental.right, stems.vocals.left, stems.vocals.right)
        for (c in 0 until 4) {
            val part = parts[c]
            require(part.size == size) { "separator returned ${part.size} frames, expected $size" }
            val sum = sums[c]
            for (i in 0 until size) sum[i] += part[i] * weight[i]
        }
        for (i in 0 until size) weights[i] += weight[i]

        if (segment >= firstSegment) {
            for (i in 0 until emit) for (c in 0 until 4) emitted[4 * i + c] = sums[c][i] / weights[i]
            out.segment(segment, emitted, emit)
        }

        // Slide everything by one hop: nothing after this segment touches its first hop frames.
        for (c in 0 until 4) {
            sums[c].copyInto(sums[c], 0, hop, size)
            sums[c].fill(0f, size - hop, size)
        }
        weights.copyInto(weights, 0, hop, size)
        weights.fill(0f, size - hop, size)
        left.copyInto(left, 0, hop, size)
        right.copyInto(right, 0, hop, size)
        filled = size - hop
        segment++
    }

    companion object {
        /** Segments a song of [frames] frames needs with [hop]. */
        fun segmentCount(frames: Long, hop: Int): Int = ((frames + hop - 1) / hop).toInt()
    }
}
