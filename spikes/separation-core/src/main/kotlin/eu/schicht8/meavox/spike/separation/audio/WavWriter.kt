// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.separation.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Streams stereo audio into a 16-bit PCM WAV file (enough to listen to a stem); [close] writes the
 * final sizes into the header. Samples are clipped to ±1.
 */
class WavWriter(file: File, private val sampleRate: Int) : StereoSink, AutoCloseable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var buffer = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

    var frames = 0L
        private set

    init {
        out.write(header(0))
    }

    override fun write(left: FloatArray, right: FloatArray, count: Int) {
        if (buffer.capacity() < count * 4) buffer = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.clear()
        for (i in 0 until count) {
            buffer.putShort(pcm(left[i]))
            buffer.putShort(pcm(right[i]))
        }
        out.write(buffer.array(), 0, count * 4)
        frames += count
    }

    override fun close() {
        out.seek(0)
        out.write(header(frames * 4))
        out.close()
    }

    private fun pcm(x: Float): Short = (x.coerceIn(-1f, 1f) * 32767).roundToInt().toShort()

    private fun header(dataBytes: Long): ByteArray {
        require(dataBytes <= Int.MAX_VALUE - 36) { "WAV files are limited to 2 GiB" }
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes.toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(2); putInt(sampleRate); putInt(sampleRate * 4); putShort(4); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes.toInt())
        }.array()
    }
}
