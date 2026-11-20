// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import android.media.AudioFormat
import java.nio.ByteBuffer

/**
 * The layout of a decoder's output buffers, taken from its latest output format, and their
 * conversion to float samples. Free of MediaCodec, so it runs in JVM tests.
 *
 * Every output format counts, not only the first: a decoder may report more than one before its
 * first buffer, and the encoding it delivers need not be the float asked for (a raw PCM track
 * keeps its own, see [AudioDecoder.requestedPcmEncoding]). Channels and rate may change only
 * until the first buffer was read.
 */
internal class PcmLayout {
    var known = false
        private set
    var channels = 1
        private set
    var sampleRate = 0
        private set
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var reading = false

    /**
     * An output format the decoder reported: its PCM [encoding] (null when the format names none,
     * which means 16-bit), [channels] and [sampleRate].
     */
    fun onFormat(encoding: Int?, channels: Int, sampleRate: Int) {
        val pcm = encoding ?: AudioFormat.ENCODING_PCM_16BIT
        if (pcm !in BYTES_PER_SAMPLE) throw UnsupportedAudioException("unsupported PCM encoding $pcm")
        if (reading && (channels != this.channels || sampleRate != this.sampleRate)) {
            throw UnsupportedAudioException(
                "output changed from ${this.channels} ch ${this.sampleRate} Hz to $channels ch $sampleRate Hz while decoding",
            )
        }
        this.encoding = pcm
        this.channels = channels
        this.sampleRate = sampleRate
        known = true
    }

    /** Samples (all channels) in an output buffer of [bytes]. */
    fun samplesIn(bytes: Int): Int = bytes / BYTES_PER_SAMPLE.getValue(encoding)

    /** Converts [buffer] (native byte order, position to limit) into [into]; returns the sample count. */
    fun read(buffer: ByteBuffer, into: FloatArray): Int {
        reading = true
        val samples = samplesIn(buffer.remaining())
        val start = buffer.position()
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> buffer.asFloatBuffer().get(into, 0, samples)
            AudioFormat.ENCODING_PCM_16BIT -> {
                val shorts = buffer.asShortBuffer()
                for (i in 0 until samples) into[i] = shorts.get(i) / 32_768f
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> for (i in 0 until samples) {
                val at = start + i * 3
                // Little-endian, as Android delivers packed 24-bit PCM; the top byte carries the sign.
                val value = (buffer.get(at).toInt() and 0xFF) or
                    ((buffer.get(at + 1).toInt() and 0xFF) shl 8) or
                    (buffer.get(at + 2).toInt() shl 16)
                into[i] = value / 8_388_608f
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val ints = buffer.asIntBuffer()
                for (i in 0 until samples) into[i] = (ints.get(i) / 2_147_483_648.0).toFloat()
            }
            AudioFormat.ENCODING_PCM_8BIT -> for (i in 0 until samples) {
                into[i] = ((buffer.get(start + i).toInt() and 0xFF) - 128) / 128f
            }
        }
        return samples
    }

    private companion object {
        val BYTES_PER_SAMPLE = mapOf(
            AudioFormat.ENCODING_PCM_8BIT to 1,
            AudioFormat.ENCODING_PCM_16BIT to 2,
            AudioFormat.ENCODING_PCM_24BIT_PACKED to 3,
            AudioFormat.ENCODING_PCM_32BIT to 4,
            AudioFormat.ENCODING_PCM_FLOAT to 4,
        )
    }
}
