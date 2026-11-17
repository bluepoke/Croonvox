// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.lyrics.Lrc
import app.croonvox.core.lyrics.LrcLine
import app.croonvox.core.lyrics.WordStart
import kotlin.test.Test
import kotlin.test.assertEquals

class LrcTest {
    @Test
    fun `standard LRC gives timed lines in time order, without metadata`() {
        val lrc = """
            [ar:Adele]
            [length: 03:48]
            [00:01.00]There's a fire
            [00:12.5][01:30.250] See how I'll leave
            [00:05.42]Reaching a fever pitch
            [00:20.00]
            not a lyric line
        """.trimIndent()

        assertEquals(
            listOf(
                LrcLine(1_000, "There's a fire"),
                LrcLine(5_420, "Reaching a fever pitch"),
                LrcLine(12_500, "See how I'll leave"),
                LrcLine(20_000, ""),
                LrcLine(90_250, "See how I'll leave"),
            ),
            Lrc.parse(lrc),
        )
    }

    @Test
    fun `enhanced LRC keeps when each word starts, as positions in the line's text`() {
        val lrc = "[00:05.50]<00:05.50>Reaching <00:06.00>a <00:06.25>fever  pitch<00:07.80>"

        assertEquals(
            listOf(
                LrcLine(
                    5_500,
                    "Reaching a fever pitch",
                    words = listOf(WordStart(0, 5_500), WordStart(9, 6_000), WordStart(11, 6_250), WordStart(22, 7_800)),
                ),
            ),
            Lrc.parse(lrc),
        )
    }

    @Test
    fun `a repeated line keeps its word timing relative to each of its timestamps`() {
        val lrc = "[00:12.00][01:30.00]<00:12.00>See <00:12.50>how"

        assertEquals(
            listOf(
                LrcLine(12_000, "See how", words = listOf(WordStart(0, 12_000), WordStart(4, 12_500))),
                LrcLine(90_000, "See how", words = listOf(WordStart(0, 90_000), WordStart(4, 90_500))),
            ),
            Lrc.parse(lrc),
        )
    }

    @Test
    fun `the offset tag shifts every timestamp, positive meaning earlier`() {
        val lrc = "[offset:+500]\n[00:10.00]<00:10.00>There's <00:11.00>a fire\n[00:00.20]Intro"

        assertEquals(
            listOf(
                LrcLine(0, "Intro"),
                LrcLine(9_500, "There's a fire", words = listOf(WordStart(0, 9_500), WordStart(8, 10_500))),
            ),
            Lrc.parse(lrc),
        )
    }

    @Test
    fun `broken word tags are dropped from the text`() {
        assertEquals(listOf(LrcLine(5_000, "Reaching a fever")), Lrc.parse("[00:05.00]<00:05.5x>Reaching <>a <1:2:3:4>fever"))
    }
}
