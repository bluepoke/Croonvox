// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation.audio

/** Measures whether a stem is sample-aligned with the audio it came from. */
object Alignment {
    /**
     * The shift in frames (positive: the stem is late) at which the stem best correlates with the
     * [reference], searched within ±[maxLag] over a [excerptFrames] excerpt from the middle.
     * Null when the excerpt is silent or the source is too short.
     */
    fun lag(reference: StereoSource, stem: StereoSource, maxLag: Int = 1024, excerptFrames: Int = 44_100): Int? {
        val length = minOf(reference.frames, stem.frames)
        val excerpt = minOf(excerptFrames.toLong(), length - 2L * maxLag).toInt()
        if (excerpt <= 0) return null
        val start = (length - excerpt) / 2
        val ref = mono(reference, start, excerpt)
        val s = mono(stem, start - maxLag, excerpt + 2 * maxLag)
        if (ref.all { it == 0f } || s.all { it == 0f }) return null
        var best = 0
        var bestValue = Double.NEGATIVE_INFINITY
        for (lag in -maxLag..maxLag) {
            var sum = 0.0
            val offset = maxLag + lag
            for (i in 0 until excerpt) sum += ref[i] * s[offset + i]
            if (sum > bestValue) {
                bestValue = sum
                best = lag
            }
        }
        return best
    }

    private fun mono(source: StereoSource, start: Long, count: Int): FloatArray {
        val l = FloatArray(count)
        val r = FloatArray(count)
        source.read(start, l, r, count)
        return FloatArray(count) { l[it] + r[it] }
    }
}
