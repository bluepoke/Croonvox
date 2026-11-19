// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.engine

import app.croonvox.core.port.MixRender
import app.croonvox.core.port.MixRenderer
import app.croonvox.core.port.PcmSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import kotlin.math.pow

/**
 * The Audio Engine's Offline Render of a Mix ([MixRenderer], #14) on the native `OfflineMixRender`,
 * which shares its DSP with Take playback. Files are decoded as for playback ([AudioDecoder]):
 * the Take first, whole, then the Backing Audio from its start, streamed through, so only a short
 * part of it is held at a time. Decoding stops once the span is rendered.
 */
class OfflineMixRenderer : MixRenderer {
    override suspend fun render(render: MixRender, sink: PcmSink) = runInterruptible(Dispatchers.IO) {
        val handle = NativeMixRender.create(
            render.sampleRate,
            render.fromFrame,
            render.toFrame,
            render.takeStartFrame,
            render.alignmentFrames,
            render.transpose.semitones,
            linear(render.backingGainDb),
            linear(render.takeGainDb),
        )
        try {
            AudioDecoder.decode(render.take.path, TakeSink(handle, render))
            val backing = BackingSink(handle, render, sink)
            try {
                AudioDecoder.decode(render.backing.path, backing)
            } catch (_: SpanRendered) {
                // Everything needed is decoded.
            }
            NativeMixRender.endBacking(handle)
            backing.drain()
            if (backing.written != render.frames) throw IOException("rendered ${backing.written} of ${render.frames} frames")
        } finally {
            NativeMixRender.release(handle)
        }
    }

    private class TakeSink(private val handle: Long, private val render: MixRender) : AudioDecoder.Sink {
        private var channels = 1

        override fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long) {
            if (sampleRate != render.sampleRate) throw IOException("Take at $sampleRate Hz doesn't fit the Mix at ${render.sampleRate} Hz")
            this.channels = channels
        }

        override fun append(interleaved: FloatArray, frames: Int) = NativeMixRender.appendTake(handle, interleaved, frames, channels)
    }

    private class BackingSink(private val handle: Long, private val render: MixRender, private val sink: PcmSink) : AudioDecoder.Sink {
        private var channels = 1
        private val out = FloatArray(DRAIN_FRAMES * 2)
        var written = 0L
            private set

        override fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long) {
            if (sampleRate != render.sampleRate) {
                throw IOException("Backing Audio at $sampleRate Hz doesn't fit the Take at ${render.sampleRate} Hz")
            }
            this.channels = channels
        }

        override fun append(interleaved: FloatArray, frames: Int) {
            NativeMixRender.appendBacking(handle, interleaved, frames, channels)
            drain()
            if (!NativeMixRender.needsBacking(handle)) throw SpanRendered()
        }

        fun drain() {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("render cancelled")
                val n = NativeMixRender.drain(handle, out, DRAIN_FRAMES)
                if (n == 0) return
                sink.write(out, n)
                written += n
            }
        }
    }

    /** Ends decoding early: the rest of the Backing Audio isn't needed. */
    private class SpanRendered : RuntimeException()

    private companion object {
        const val DRAIN_FRAMES = 8192

        fun linear(db: Float): Float = 10f.pow(db / 20f)
    }
}
