// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data.export

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.round

/**
 * Writes PCM WAV (16- or 24-bit, ADR 8) of a length known up front, so the header is written first
 * and the stream never needs to seek. Float samples beyond ±1 are clipped; there is no dither.
 */
class WavEncoder(
    private val out: OutputStream,
    sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int,
    private val frames: Long,
) {
    private val bytesPerSample = bitsPerSample / 8
    private val scale = ((1 shl (bitsPerSample - 1)) - 1).toFloat()
    private var buffer = ByteArray(0)
    private var written = 0L

    init {
        require(bitsPerSample == 16 || bitsPerSample == 24) { "WAV is written at 16 or 24 bits, not $bitsPerSample" }
        require(channels > 0 && sampleRate > 0 && frames >= 0)
        val dataBytes = frames * channels * bytesPerSample
        require(dataBytes <= UINT32_MAX - 36) { "too long for a WAV file" }
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * bytesPerSample)
        header.putShort((channels * bytesPerSample).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes.toInt())
        out.write(header.array())
    }

    /** Appends [frames] frames from [interleaved] (`frames * channels` samples). */
    fun write(interleaved: FloatArray, frames: Int) {
        if (written + frames > this.frames) throw IOException("more than the ${this.frames} frames announced")
        val samples = frames * channels
        val size = samples * bytesPerSample
        if (buffer.size < size) buffer = ByteArray(size)
        var at = 0
        for (i in 0 until samples) {
            // Half to even, so positive and negative samples round alike.
            val v = round(interleaved[i].coerceIn(-1f, 1f) * scale).toInt()
            buffer[at++] = v.toByte()
            buffer[at++] = (v shr 8).toByte()
            if (bytesPerSample == 3) buffer[at++] = (v shr 16).toByte()
        }
        out.write(buffer, 0, size)
        written += frames
    }

    /** Flushes the stream; fails if fewer frames were written than announced. */
    fun finish() {
        if (written != frames) throw IOException("$written of $frames frames written")
        out.flush()
    }

    private companion object {
        const val HEADER_BYTES = 44
        const val UINT32_MAX = 0xFFFF_FFFFL
    }
}
