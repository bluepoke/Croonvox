// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import java.io.File
import java.io.IOException

/**
 * 24-bit FLAC files of float PCM through libFLAC, written and read in blocks (ADR 8). Used for the
 * stems and Separation checkpoints, which must read back exactly as written (the platform's FLAC
 * decoder may hand out 16-bit samples).
 */
object FlacFiles {
    init {
        System.loadLibrary("meavox_engine")
    }

    /** Receives a file's frames: one [begin], then interleaved float frames in order. */
    interface Sink {
        fun begin(channels: Int, sampleRate: Int)

        fun write(interleaved: FloatArray, frames: Int)
    }

    /** One file being written. [finish] completes it; [close] without [finish] deletes it. */
    class Writer internal constructor(private val file: File, private var handle: Long, private val channels: Int) : AutoCloseable {
        fun write(interleaved: FloatArray, frames: Int) {
            check(handle != 0L) { "closed" }
            require(interleaved.size >= frames * channels) { "$frames frames don't fit ${interleaved.size} samples" }
            if (!write(handle, interleaved, frames)) throw IOException("could not write $file")
        }

        fun finish() {
            check(handle != 0L) { "closed" }
            val ok = closeWriter(handle)
            handle = 0
            if (!ok) {
                file.delete()
                throw IOException("could not complete $file")
            }
        }

        override fun close() {
            if (handle == 0L) return
            closeWriter(handle)
            handle = 0
            file.delete()
        }
    }

    /** Starts writing [file] with [channels] (1..8) at [sampleRate]. Samples beyond ±1 are clipped. */
    fun writer(file: File, channels: Int, sampleRate: Int): Writer {
        require(channels in 1..8) { "FLAC holds 1 to 8 channels, not $channels" }
        val handle = openWriter(file.path, channels, sampleRate)
        if (handle == 0L) throw IOException("could not create $file")
        return Writer(file, handle, channels)
    }

    /** Reads [file] into [sink]. Blocking; interrupting the thread cancels it. */
    fun read(file: File, sink: Sink) {
        val info = IntArray(2)
        val handle = openReader(file.path, info)
        if (handle == 0L) throw IOException("could not read $file as FLAC")
        try {
            val channels = info[0]
            sink.begin(channels, info[1])
            val block = FloatArray(BLOCK_FRAMES * channels)
            while (true) {
                if (Thread.interrupted()) throw InterruptedException("reading $file cancelled")
                val frames = read(handle, block, BLOCK_FRAMES)
                if (frames < 0) throw IOException("$file is damaged")
                if (frames == 0) break
                sink.write(block, frames)
            }
        } finally {
            closeReader(handle)
        }
    }

    private const val BLOCK_FRAMES = 4096

    private external fun openWriter(path: String, channels: Int, sampleRate: Int): Long
    private external fun write(handle: Long, interleaved: FloatArray, frames: Int): Boolean
    private external fun closeWriter(handle: Long): Boolean
    private external fun openReader(path: String, info: IntArray): Long
    private external fun read(handle: Long, out: FloatArray, maxFrames: Int): Int
    private external fun closeReader(handle: Long)
}
