// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.lyrics.Karaoke
import app.croonvox.core.lyrics.KaraokePosition
import app.croonvox.core.lyrics.LrcLine
import app.croonvox.core.lyrics.WordStart
import kotlin.test.Test
import kotlin.test.assertEquals

class KaraokeTest {
    // 20 characters each; the second line starts 4 s after the first.
    private val lines = listOf(
        LrcLine(10_000, "There's a fire start"),
        LrcLine(14_000, "Reaching a fever pit"),
        LrcLine(17_000, "See how I'll leave it"),
    )

    @Test
    fun `the current line is the last one started, its words are sung across its time span`() {
        assertEquals(KaraokePosition(line = -1, sungChars = 0f), Karaoke.at(lines, positionMs = 9_999, offsetMs = 0))
        assertEquals(KaraokePosition(line = 0, sungChars = 0f), Karaoke.at(lines, positionMs = 10_000, offsetMs = 0))
        assertEquals(KaraokePosition(line = 0, sungChars = 10f), Karaoke.at(lines, positionMs = 12_000, offsetMs = 0))
        assertEquals(KaraokePosition(line = 1, sungChars = 0f), Karaoke.at(lines, positionMs = 14_000, offsetMs = 0))
    }

    @Test
    fun `a positive Lyrics Offset shows the Lyrics later`() {
        assertEquals(KaraokePosition(line = -1, sungChars = 0f), Karaoke.at(lines, positionMs = 10_500, offsetMs = 1_000))
        assertEquals(KaraokePosition(line = 0, sungChars = 10f), Karaoke.at(lines, positionMs = 13_000, offsetMs = 1_000))
        assertEquals(KaraokePosition(line = 1, sungChars = 0f), Karaoke.at(lines, positionMs = 13_500, offsetMs = -500))
    }

    @Test
    fun `before a long break or at the end a line is sung at a singing pace, not across the whole gap`() {
        // 20 characters: sung in at most 5 s (250 ms each), even with 30 s to the next line.
        val withBreak = listOf(LrcLine(10_000, "There's a fire start"), LrcLine(40_000, "Reaching a fever pit"))

        assertEquals(KaraokePosition(line = 0, sungChars = 10f), Karaoke.at(withBreak, positionMs = 12_500, offsetMs = 0))
        assertEquals(KaraokePosition(line = 0, sungChars = 20f), Karaoke.at(withBreak, positionMs = 20_000, offsetMs = 0))
        assertEquals(KaraokePosition(line = 1, sungChars = 10f), Karaoke.at(withBreak, positionMs = 42_500, offsetMs = 0))
    }

    @Test
    fun `with word timing from enhanced LRC, progress follows the words`() {
        val enhanced = listOf(
            LrcLine(
                5_500,
                "Reaching a fever pitch",
                words = listOf(WordStart(0, 5_500), WordStart(9, 6_000), WordStart(11, 6_250), WordStart(22, 7_800)),
            ),
            LrcLine(10_000, "Next line"),
        )
        fun sungAt(ms: Long) = Karaoke.at(enhanced, positionMs = ms, offsetMs = 0).sungChars

        assertEquals(0f, sungAt(5_500))
        assertEquals(4.5f, sungAt(5_750)) // halfway through "Reaching "
        assertEquals(9f, sungAt(6_000))
        assertEquals(16.5f, sungAt(7_025)) // halfway through "fever pitch"
        assertEquals(22f, sungAt(8_000)) // the line ended at 7.8 s, before the next one starts
    }
}
