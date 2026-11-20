// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import android.media.AudioFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioDecoderTest {
    @Test
    fun `a raw PCM track keeps its own encoding instead of being asked for float`() {
        // On Android 17 (Pixel 9 Pro XL) MediaExtractor decodes FLAC itself and hands out 16-bit
        // "audio/raw". Asking its pass-through decoder for float declared the 16-bit input as float,
        // so stems came out at half their length, full of Inf and NaN.
        assertNull(AudioDecoder.requestedPcmEncoding("audio/raw"))
    }

    @Test
    fun `a compressed track is decoded to float`() {
        assertEquals(AudioFormat.ENCODING_PCM_FLOAT, AudioDecoder.requestedPcmEncoding("audio/flac"))
        assertEquals(AudioFormat.ENCODING_PCM_FLOAT, AudioDecoder.requestedPcmEncoding("audio/opus"))
    }
}
