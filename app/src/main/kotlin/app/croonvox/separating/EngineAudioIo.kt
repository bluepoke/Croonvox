// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separating

import app.croonvox.engine.AudioDecoder
import app.croonvox.engine.FlacFiles
import app.croonvox.engine.UnsupportedAudioException
import app.croonvox.separation.audio.FlacCodec
import app.croonvox.separation.audio.PcmDecoder
import app.croonvox.separation.audio.PcmStream
import app.croonvox.separation.audio.PcmWriter
import app.croonvox.separation.audio.UndecodableAudio
import java.io.File

/** Separation decodes the Original Audio with the same decoder as playback, so stems match what plays. */
object EngineDecoder : PcmDecoder {
    override fun decode(file: File, sink: PcmStream) {
        try {
            AudioDecoder.decode(file.path, object : AudioDecoder.Sink {
                override fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long) =
                    sink.begin(channels, sampleRate, estimatedFrames)

                override fun append(interleaved: FloatArray, frames: Int) = sink.write(interleaved, frames)
            })
        } catch (e: UnsupportedAudioException) {
            throw UndecodableAudio(e.message ?: "unsupported audio", e)
        }
    }
}

/** [FlacCodec] on the engine's libFLAC. */
object EngineFlac : FlacCodec {
    override fun writer(file: File, channels: Int, sampleRate: Int): PcmWriter {
        val writer = FlacFiles.writer(file, channels, sampleRate)
        return object : PcmWriter {
            override fun write(interleaved: FloatArray, frames: Int) = writer.write(interleaved, frames)

            override fun finish() = writer.finish()

            override fun close() = writer.close()
        }
    }

    override fun read(file: File, sink: PcmStream) {
        FlacFiles.read(file, object : FlacFiles.Sink {
            override fun begin(channels: Int, sampleRate: Int) = sink.begin(channels, sampleRate, 0)

            override fun write(interleaved: FloatArray, frames: Int) = sink.write(interleaved, frames)
        })
    }
}
