// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WavEncoderTest {
    private fun encode(bits: Int, frames: Long, vararg blocks: FloatArray): ByteArray {
        val out = ByteArrayOutputStream()
        val encoder = WavEncoder(out, sampleRate = 44_100, channels = 2, bitsPerSample = bits, frames = frames)
        blocks.forEach { encoder.write(it, it.size / 2) }
        encoder.finish()
        return out.toByteArray()
    }

    private fun ByteArray.le(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteArray.ascii(at: Int) = String(copyOfRange(at, at + 4), Charsets.US_ASCII)

    @Test
    fun `a 16-bit WAV has a canonical PCM header for its length`() {
        val bytes = encode(16, 3, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f))
        val le = bytes.le()

        assertEquals(44 + 12, bytes.size)
        assertEquals("RIFF", bytes.ascii(0))
        assertEquals(36 + 12, le.getInt(4))
        assertEquals("WAVE", bytes.ascii(8))
        assertEquals("fmt ", bytes.ascii(12))
        assertEquals(16, le.getInt(16)) // fmt chunk size
        assertEquals(1, le.getShort(20).toInt()) // PCM
        assertEquals(2, le.getShort(22).toInt()) // channels
        assertEquals(44_100, le.getInt(24))
        assertEquals(44_100 * 4, le.getInt(28)) // bytes per second
        assertEquals(4, le.getShort(32).toInt()) // block align
        assertEquals(16, le.getShort(34).toInt())
        assertEquals("data", bytes.ascii(36))
        assertEquals(12, le.getInt(40))
    }

    @Test
    fun `16-bit samples are rounded and clipped to full scale`() {
        val bytes = encode(16, 2, floatArrayOf(0.5f, -0.5f), floatArrayOf(1.5f, -1f))
        val le = bytes.le()

        assertEquals(listOf(16_384, -16_384, 32_767, -32_767), (0 until 4).map { le.getShort(44 + 2 * it).toInt() })
    }

    @Test
    fun `24-bit samples are three little-endian bytes each`() {
        val bytes = encode(24, 1, floatArrayOf(1f, -0.25f))
        val le = bytes.le()

        assertEquals(24, le.getShort(34).toInt())
        assertEquals(6, le.getShort(32).toInt())
        assertEquals(44_100 * 6, le.getInt(28))
        assertEquals(6, le.getInt(40))
        // 8 388 607 = 0x7FFFFF; −0.25 × 8 388 607 ≈ −2 097 152 = 0xE00000.
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x00, 0xE0.toByte()), bytes.copyOfRange(44, 50))
    }

    @Test
    fun `finishing with fewer frames than announced fails, so a short file is never kept`() {
        val encoder = WavEncoder(ByteArrayOutputStream(), sampleRate = 44_100, channels = 2, bitsPerSample = 16, frames = 10)
        encoder.write(FloatArray(8), 4)

        assertFailsWith<IOException> { encoder.finish() }
    }
}
