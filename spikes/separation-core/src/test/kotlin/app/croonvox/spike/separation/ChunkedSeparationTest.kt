// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation

import app.croonvox.spike.separation.audio.StereoBuffer
import app.croonvox.spike.separation.audio.StereoSink
import app.croonvox.spike.separation.audio.StereoSource
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class ChunkedSeparationTest {
    /** Vocals are a quarter of the input, the Instrumental the rest: any shift or gain error shows. */
    private class LinearSeparator(override val segmentFrames: Int) : SegmentSeparator {
        override val sampleRate = 44_100
        var calls = 0
        var maxSeen = 0

        override fun separate(input: StereoFrames): Stems {
            calls++
            maxSeen = maxOf(maxSeen, input.size)
            return Stems(
                vocals = StereoFrames(FloatArray(input.size) { input.left[it] * 0.25f }, FloatArray(input.size) { input.right[it] * 0.25f }),
                instrumental = StereoFrames(FloatArray(input.size) { input.left[it] * 0.75f }, FloatArray(input.size) { input.right[it] * 0.75f }),
            )
        }

        override fun close() {}
    }

    @Test
    fun `stems are sample-aligned with and as long as the input`() {
        val random = java.util.Random(3)
        val frames = 10_007 // not a multiple of the segment hop
        val left = FloatArray(frames) { random.nextGaussian().toFloat() }
        val right = FloatArray(frames) { random.nextGaussian().toFloat() }
        val vocals = StereoBuffer(44_100)
        val instrumental = StereoBuffer(44_100)

        ChunkedSeparation.run(StereoBuffer(44_100, left, right), LinearSeparator(segmentFrames = 1_000), vocals, instrumental, overlap = 0.25f)

        assertEquals(frames.toLong(), vocals.frames)
        assertEquals(frames.toLong(), instrumental.frames)
        for (i in 0 until frames) {
            assertEquals(left[i] * 0.25f, vocals.left[i], 1e-5f, "vocals left $i")
            assertEquals(right[i] * 0.25f, vocals.right[i], 1e-5f, "vocals right $i")
            assertEquals(left[i] * 0.75f, instrumental.left[i], 1e-5f, "instrumental left $i")
        }
    }

    @Test
    fun `a 30-minute song streams through in segments without being held in memory`() {
        val frames = 30L * 60 * 44_100 // 1.27 GB as two stereo float stems; the test heap is 2 GB
        val source = object : StereoSource {
            override val sampleRate = 44_100
            override val frames = frames
            override fun read(start: Long, left: FloatArray, right: FloatArray, count: Int) {
                for (i in 0 until count) {
                    val p = start + i
                    left[i] = if (p in 0 until frames) sin(p * 0.01).toFloat() else 0f
                    right[i] = left[i]
                }
            }
        }
        var written = 0L
        val counting = StereoSink { _, _, count -> written += count }
        val separator = LinearSeparator(segmentFrames = 343_980) // Demucs segment: 7.8 s
        val progress = mutableListOf<Pair<Int, Int>>()

        ChunkedSeparation.run(source, separator, counting, StereoSink { _, _, _ -> }, overlap = 0.25f) { done, total -> progress += done to total }

        assertEquals(frames, written)
        assertEquals(343_980, separator.maxSeen)
        val total = progress.last().second
        assertEquals(total, separator.calls)
        assertEquals(total to total, progress.last())
        assertEquals(308, total) // ⌈79 380 000 frames / hop 257 985 (75 % of the segment)⌉
    }
}
