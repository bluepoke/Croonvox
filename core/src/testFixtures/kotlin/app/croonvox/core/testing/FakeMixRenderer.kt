// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.port.MixRender
import app.croonvox.core.port.MixRenderer
import app.croonvox.core.port.PcmSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield

/**
 * Records render requests and writes silence of the requested length in blocks of [BLOCK_FRAMES].
 * With [pauseAfterFirstBlock], it waits after the first block, so a test can cancel a render part-way.
 */
class FakeMixRenderer : MixRenderer {
    val renders = mutableListOf<MixRender>()

    var pauseAfterFirstBlock = false
    private val gate = CompletableDeferred<Unit>()

    override suspend fun render(render: MixRender, sink: PcmSink) {
        renders += render
        val block = FloatArray(BLOCK_FRAMES * 2)
        var left = render.frames
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
