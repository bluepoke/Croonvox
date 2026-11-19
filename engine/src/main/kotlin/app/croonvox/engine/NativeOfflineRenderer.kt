// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.engine

import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.Transpose
import app.croonvox.core.port.MixRender
import app.croonvox.core.port.OfflineRenderer
import app.croonvox.core.port.PcmSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import kotlin.math.pow

/**
 * The Audio Engine's Offline Render ([OfflineRenderer], #14, #24) on the native `OfflineMixRender`,
 * which shares its DSP with real-time playback. Files are decoded as for playback ([AudioDecoder]):
 * a Mix's Take first, whole, then the Backing Audio from its start, streamed through, so only a
 * short part of it is held at a time. Decoding stops once the span is rendered.
 */
class NativeOfflineRenderer : OfflineRenderer {
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
            AudioDecoder.decode(render.take.path, TakeSink(handle, render.sampleRate))
            val written = renderBacking(handle, render.backing, render.sampleRate, sink)
            if (written != render.frames) throw IOException("rendered $written of ${render.frames} frames")
        } finally {
            NativeMixRender.release(handle)
        }
    }

    override suspend fun renderBacking(backing: AudioFile, transpose: Transpose, sink: PcmSink): Long =
        runInterruptible(Dispatchers.IO) {
            val rate = rateOf(backing)
            // No Take, at unit gain, until the Backing Audio ends: the Backing Audio alone, as a Session plays it.
            val handle = NativeMixRender.create(rate, 0, NativeMixRender.UNTIL_END, 0, 0, transpose.semitones, 1f, 1f)
            try {
                renderBacking(handle, backing, rate, sink)
            } finally {
                NativeMixRender.release(handle)
            }
        }

    override suspend fun sampleRate(audio: AudioFile): Int = runInterruptible(Dispatchers.IO) { rateOf(audio) }

    /** The rate the decoder hands out, which is what playback runs at (it may differ from the container's). */
    private fun rateOf(audio: AudioFile): Int {
        var rate = 0
        try {
            AudioDecoder.decode(
                audio.path,
                object : AudioDecoder.Sink {
                    override fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long) {
                        rate = sampleRate
                        throw EnoughDecoded()
                    }

                    override fun append(interleaved: FloatArray, frames: Int) = Unit
                },
            )
        } catch (_: EnoughDecoded) {
            // The rate is known.
        }
        if (rate <= 0) throw IOException("no audio in ${audio.path}")
        return rate
    }

    /** Streams [backing] through the render [handle] into [sink]; returns the frames written. */
    private fun renderBacking(handle: Long, backing: AudioFile, rate: Int, sink: PcmSink): Long {
        val feed = BackingSink(handle, rate, sink)
        try {
            AudioDecoder.decode(backing.path, feed)
        } catch (_: EnoughDecoded) {
            // Everything needed is decoded.
        }
        NativeMixRender.endBacking(handle)
        feed.drain()
        return feed.written
    }

    private class TakeSink(private val handle: Long, private val rate: Int) : AudioDecoder.Sink {
        private var channels = 1

        override fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long) {
            if (sampleRate != rate) throw IOException("Take at $sampleRate Hz doesn't fit the Mix at $rate Hz")
            this.channels = channels
        }

        override fun append(interleaved: FloatArray, frames: Int) = NativeMixRender.appendTake(handle, interleaved, frames, channels)
    }

    private class BackingSink(private val handle: Long, private val rate: Int, private val sink: PcmSink) : AudioDecoder.Sink {
        private var channels = 1
        private val out = FloatArray(DRAIN_FRAMES * 2)
        var written = 0L
            private set

        override fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long) {
            if (sampleRate != rate) throw IOException("Backing Audio at $sampleRate Hz doesn't fit the render at $rate Hz")
            this.channels = channels
        }

        override fun append(interleaved: FloatArray, frames: Int) {
            NativeMixRender.appendBacking(handle, interleaved, frames, channels)
            drain()
            if (!NativeMixRender.needsBacking(handle)) throw EnoughDecoded()
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

    /** Ends decoding early: what was needed is decoded. */
    private class EnoughDecoded : RuntimeException()

    private companion object {
        const val DRAIN_FRAMES = 8192

        fun linear(db: Float): Float = 10f.pow(db / 20f)
    }
}
