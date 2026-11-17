// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SegmentedSeparationTest {
    /** Vocals are a quarter of the input, the Instrumental the rest: any shift or gain error shows. */
    private class LinearSeparator(override val segmentFrames: Int, private val failAtCall: Int? = null) : SegmentSeparator {
        override val sampleRate = 44_100
        var calls = 0

        override fun separate(input: StereoFrames): SegmentStems {
            if (calls == failAtCall) throw IllegalStateException("process killed")
            calls++
            return SegmentStems(
                vocals = StereoFrames(FloatArray(input.size) { input.left[it] * 0.25f }, FloatArray(input.size) { input.right[it] * 0.25f }),
                instrumental = StereoFrames(FloatArray(input.size) { input.left[it] * 0.75f }, FloatArray(input.size) { input.right[it] * 0.75f }),
            )
        }

        override fun close() {}
    }

    /** Emitted segments by index, as interleaved Instrumental L, R, Vocal Stem L, R. */
    private class Segments : SegmentedSeparation.Output {
        val emitted = sortedMapOf<Int, FloatArray>()

        override fun segment(index: Int, interleaved: FloatArray, frames: Int) {
            emitted[index] = interleaved.copyOf(frames * 4)
        }

        fun all(): FloatArray = emitted.values.fold(FloatArray(0)) { acc, it -> acc + it }
    }

    private val random = java.util.Random(3)
    private val frames = 10_007 // not a multiple of the segment hop
    private val left = FloatArray(frames) { random.nextGaussian().toFloat() }
    private val right = FloatArray(frames) { random.nextGaussian().toFloat() }

    /** Feeds the input in uneven blocks, as a decoder would. */
    private fun SegmentedSeparation.feed() {
        var done = 0
        var block = 13
        while (done < frames) {
            val n = minOf(block, frames - done)
            write(FloatArray(2 * n) { if (it % 2 == 0) left[done + it / 2] else right[done + it / 2] }, n)
            done += n
            block = block * 2 + 1
        }
        finish(frames.toLong())
    }

    @Test
    fun `stems are sample-aligned with and as long as the input`() {
        val out = Segments()

        SegmentedSeparation(LinearSeparator(segmentFrames = 1_000), out).feed()

        val all = out.all()
        assertEquals(frames * 4, all.size)
        for (i in 0 until frames) {
            assertEquals(left[i] * 0.75f, all[4 * i], 1e-5f, "instrumental left $i")
            assertEquals(right[i] * 0.75f, all[4 * i + 1], 1e-5f, "instrumental right $i")
            assertEquals(left[i] * 0.25f, all[4 * i + 2], 1e-5f, "vocals left $i")
            assertEquals(right[i] * 0.25f, all[4 * i + 3], 1e-5f, "vocals right $i")
        }
    }

    @Test
    fun `a run resumed after the segments stored so far gives the same stems without separating them again`() {
        val uninterrupted = Segments().also { SegmentedSeparation(LinearSeparator(segmentFrames = 1_000), it).feed() }
        val stored = Segments()
        assertFailsWith<IllegalStateException> {
            SegmentedSeparation(LinearSeparator(segmentFrames = 1_000, failAtCall = 6), stored).feed()
        }
        val separator = LinearSeparator(segmentFrames = 1_000)

        SegmentedSeparation(separator, stored, firstSegment = stored.emitted.size).feed()

        assertContentEquals(uninterrupted.all(), stored.all())
        val total = SegmentedSeparation.segmentCount(frames.toLong(), hop = 750)
        assertEquals(total - 6 + 1, separator.calls) // one earlier segment again, for the overlap
    }
}
