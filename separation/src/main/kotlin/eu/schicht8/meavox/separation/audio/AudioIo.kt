// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.separation.audio

import java.io.File
import java.io.IOException

/** Receives decoded audio: one [begin], then interleaved float frames in order. */
interface PcmStream : FrameSink {
    /** [estimatedFrames] is derived from the container's duration, 0 if unknown. */
    fun begin(channelCount: Int, sampleRate: Int, estimatedFrames: Long)
}

/** Decodes the audio of a file (any audio or video container the platform reads) to float PCM. */
fun interface PcmDecoder {
    /**
     * Blocking; interrupting the thread cancels it.
     * @throws UndecodableAudio if the file has no audio the platform can decode.
     */
    fun decode(file: File, sink: PcmStream)
}

/** The file has no audio that can be decoded. */
class UndecodableAudio(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Lossless float PCM files: FLAC at 24 bits (ADR 8). Samples beyond ±1 are clipped. */
interface FlacCodec {
    fun writer(file: File, channels: Int, sampleRate: Int): PcmWriter

    /** Blocking; reads [file] into [sink]. */
    fun read(file: File, sink: PcmStream)
}

/** Writes one file. [finish] completes it; [close] without [finish] deletes what was written. */
interface PcmWriter : FrameSink, AutoCloseable {
    fun finish()

    override fun close()
}
