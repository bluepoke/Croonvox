// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.Transpose
import app.croonvox.core.port.MixRender
import app.croonvox.core.port.OfflineRenderer
import app.croonvox.core.port.PcmSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield

/**
 * Records render requests and writes silence of the rendered length in blocks of [BLOCK_FRAMES]: a
 * Mix's span, or the whole Backing Audio, whose length and rate are given per file in [backings]
 * (10 s at 44.1 kHz by default). With [pauseAfterFirstBlock], it waits after the first block, so a
 * test can cancel a render part-way.
 */
class FakeOfflineRenderer(private val backings: Map<AudioFile, Backing> = emptyMap()) : OfflineRenderer {
    data class Backing(val frames: Long, val sampleRate: Int)

    data class BackingRender(val backing: AudioFile, val transpose: Transpose)

    val renders = mutableListOf<MixRender>()
    val backingRenders = mutableListOf<BackingRender>()

    var pauseAfterFirstBlock = false
    private val gate = CompletableDeferred<Unit>()

    override suspend fun render(render: MixRender, sink: PcmSink) {
        renders += render
        writeSilence(render.frames, sink)
    }

    override suspend fun renderBacking(backing: AudioFile, transpose: Transpose, sink: PcmSink): Long {
        backingRenders += BackingRender(backing, transpose)
        val frames = backingOf(backing).frames
        writeSilence(frames, sink)
        return frames
    }

    override suspend fun sampleRate(audio: AudioFile): Int = backingOf(audio).sampleRate

    private fun backingOf(audio: AudioFile) = backings[audio] ?: Backing(frames = 441_000, sampleRate = 44_100)

    private suspend fun writeSilence(frames: Long, sink: PcmSink) {
        val block = FloatArray(BLOCK_FRAMES * 2)
        var left = frames
        var first = true
        while (left > 0) {
            val n = minOf(left, BLOCK_FRAMES.toLong()).toInt()
            sink.write(block, n)
            left -= n
            if (first && pauseAfterFirstBlock) gate.await()
            first = false
            yield()
        }
    }

    companion object {
        const val BLOCK_FRAMES = 48_000
    }
}
