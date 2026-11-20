// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PcmLayoutTest {
    private fun pcm16(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it) }
            flip()
        }

    @Test
    fun `a later 16-bit output format replaces the float format the decoder first reported`() {
        // Only the latest format describes the buffers. 16-bit read as float would give half the
        // samples, and noise.
        val layout = PcmLayout()
        layout.onFormat(AudioFormat.ENCODING_PCM_FLOAT, channels = 2, sampleRate = 48_000)
        layout.onFormat(AudioFormat.ENCODING_PCM_16BIT, channels = 2, sampleRate = 48_000)

        val into = FloatArray(4)
        val samples = layout.read(pcm16(16_384, -16_384, 0, 32_767), into)

        assertEquals(4, samples)
        assertContentEquals(floatArrayOf(0.5f, -0.5f, 0f, 0.999969482421875f), into)
    }

    @Test
    fun `packed 24-bit and 32-bit output is read with its sign`() {
        val layout = PcmLayout()
        layout.onFormat(AudioFormat.ENCODING_PCM_24BIT_PACKED, channels = 1, sampleRate = 44_100)
        // 0x400000 = +0.5, 0xC00000 = -0.5, 0xFFFFFF = -1/2^23, little-endian.
        val packed = ByteBuffer.wrap(byteArrayOf(0, 0, 0x40, 0, 0, 0xC0.toByte(), -1, -1, -1))
        val into = FloatArray(3)

        assertEquals(3, layout.read(packed, into))
        assertContentEquals(floatArrayOf(0.5f, -0.5f, -1f / 8_388_608), into)

        val wide = PcmLayout().apply { onFormat(AudioFormat.ENCODING_PCM_32BIT, channels = 1, sampleRate = 44_100) }
        val ints = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putInt(1 shl 30).putInt(Int.MIN_VALUE).flip() as ByteBuffer
        assertEquals(2, wide.read(ints, into))
        assertEquals(0.5f, into[0])
        assertEquals(-1f, into[1])
    }

    @Test
    fun `a change of channels or rate after the first buffer stops the decode`() {
        val layout = PcmLayout()
        layout.onFormat(AudioFormat.ENCODING_PCM_16BIT, channels = 2, sampleRate = 48_000)
        layout.read(pcm16(0, 0), FloatArray(2))

        assertFailsWith<UnsupportedAudioException> {
            layout.onFormat(AudioFormat.ENCODING_PCM_16BIT, channels = 1, sampleRate = 48_000)
        }
    }
}
