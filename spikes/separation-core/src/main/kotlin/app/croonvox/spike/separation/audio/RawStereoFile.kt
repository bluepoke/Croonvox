// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Appends stereo float frames to a headerless file (interleaved little-endian float32): the spike's temp format. */
class RawStereoWriter(private val file: File, private val sampleRate: Int) : StereoSink, AutoCloseable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var bytes = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    var frames = 0L
        private set

    override fun write(left: FloatArray, right: FloatArray, count: Int) {
        if (bytes.capacity() < count * 8) bytes = ByteBuffer.allocate(count * 8).order(ByteOrder.LITTLE_ENDIAN)
        bytes.clear()
        for (i in 0 until count) {
            bytes.putFloat(left[i])
            bytes.putFloat(right[i])
        }
        out.write(bytes.array(), 0, count * 8)
        frames += count
    }

    override fun close() = out.close()

    /** Closes the writer and opens what it wrote for reading. */
    fun finish(): RawStereoFile {
        close()
        return RawStereoFile(file, sampleRate)
    }
}

/** Random-access reader for a [RawStereoWriter] file. */
class RawStereoFile(val file: File, override val sampleRate: Int) : StereoSource, AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    private val channel = raf.channel
    override val frames: Long = raf.length() / 8
    private var bytes = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

    override fun read(start: Long, left: FloatArray, right: FloatArray, count: Int) {
        left.fill(0f, 0, count)
        right.fill(0f, 0, count)
        val from = maxOf(start, 0L)
        val to = minOf(start + count, frames)
        if (from >= to) return
        val n = (to - from).toInt()
        if (bytes.capacity() < n * 8) bytes = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN)
        bytes.clear().limit(n * 8)
        var position = from * 8
        while (bytes.hasRemaining()) {
            val read = channel.read(bytes, position)
            check(read >= 0) { "unexpected end of ${file.name}" }
            position += read
        }
        bytes.flip()
        val offset = (from - start).toInt()
        for (i in 0 until n) {
            left[offset + i] = bytes.float
            right[offset + i] = bytes.float
        }
    }

    override fun close() = raf.close()
}
