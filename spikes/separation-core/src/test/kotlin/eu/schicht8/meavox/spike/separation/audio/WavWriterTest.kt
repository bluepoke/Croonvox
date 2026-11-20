// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.separation.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class WavWriterTest {
    @Test
    fun `writes a 16-bit stereo PCM WAV whose header states the frames written`() {
        val file = File(createTempDirectory().toFile(), "stem.wav")

        WavWriter(file, sampleRate = 48_000).use { wav ->
            wav.write(floatArrayOf(0f, 0.5f, 1.5f), floatArrayOf(-1f, -0.5f, 0f), 3)
            wav.write(floatArrayOf(0.25f), floatArrayOf(-2f), 1)
            assertEquals(4L, wav.frames)
        }

        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 4 * 4, b.limit())
        assertEquals("RIFF", ascii(b, 0))
        assertEquals(36 + 16, b.getInt(4))
        assertEquals("WAVE", ascii(b, 8))
        assertEquals("fmt ", ascii(b, 12))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(2, b.getShort(22).toInt())
        assertEquals(48_000, b.getInt(24))
        assertEquals(192_000, b.getInt(28)) // byte rate
        assertEquals(4, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", ascii(b, 36))
        assertEquals(16, b.getInt(40))
        val samples = (0 until 8).map { b.getShort(44 + 2 * it).toInt() }
        assertEquals(listOf(0, -32767, 16384, -16383, 32767, 0, 8192, -32767), samples) // x · 32767, rounded; clipped to ±1
    }

    private fun ascii(b: ByteBuffer, at: Int) = String(ByteArray(4) { b.get(at + it) }, Charsets.US_ASCII)
}
