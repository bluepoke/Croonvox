// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation

import app.croonvox.separation.audio.FlacCodec
import app.croonvox.separation.audio.PcmDecoder
import app.croonvox.separation.audio.PcmStream
import app.croonvox.separation.audio.PcmWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File

/**
 * Stands in for both the platform decoder and FLAC: files hold channels, rate and raw float
 * samples, so what is written reads back exactly.
 */
class RawPcmFiles : PcmDecoder, FlacCodec {
    class Audio(val channels: Int, val sampleRate: Int, val interleaved: FloatArray) {
        val frames: Int get() = interleaved.size / channels
        fun channel(c: Int) = FloatArray(frames) { interleaved[it * channels + c] }
    }

    fun write(file: File, audio: Audio) {
        writer(file, audio.channels, audio.sampleRate).use {
            it.write(audio.interleaved, audio.frames)
            it.finish()
        }
    }

    fun read(file: File): Audio {
        var channels = 0
        var rate = 0
        val samples = mutableListOf<Float>()
        read(file, object : PcmStream {
            override fun begin(channelCount: Int, sampleRate: Int, estimatedFrames: Long) {
                channels = channelCount
                rate = sampleRate
            }

            override fun write(interleaved: FloatArray, frames: Int) {
                for (i in 0 until frames * channels) samples += interleaved[i]
            }
        })
        return Audio(channels, rate, samples.toFloatArray())
    }

    override fun decode(file: File, sink: PcmStream) = read(file, sink)

    override fun read(file: File, sink: PcmStream) {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val channels = input.readInt()
            val rate = input.readInt()
            val frames = input.readLong()
            sink.begin(channels, rate, frames)
            val block = FloatArray(1000 * channels)
            var n = 0
            try {
                while (true) {
                    block[n++] = input.readFloat()
                    if (n == block.size) {
                        sink.write(block, n / channels)
                        n = 0
                    }
                }
            } catch (_: EOFException) {
                if (n > 0) sink.write(block, n / channels)
            }
        }
    }

    override fun writer(file: File, channels: Int, sampleRate: Int): PcmWriter = object : PcmWriter {
        private val samples = mutableListOf<Float>()
        private var finished = false

        override fun write(interleaved: FloatArray, frames: Int) {
            for (i in 0 until frames * channels) samples += interleaved[i]
        }

        override fun finish() {
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(channels)
                out.writeInt(sampleRate)
                out.writeLong((samples.size / channels).toLong())
                for (s in samples) out.writeFloat(s)
            }
            finished = true
        }

        override fun close() {
            if (!finished) file.delete()
        }
    }
}
