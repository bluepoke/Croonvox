// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.lyrics.LyricsOffset
import eu.schicht8.meavox.core.lyrics.MatchLyrics
import eu.schicht8.meavox.core.lyrics.TrackLyrics
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.LyricsCandidate
import eu.schicht8.meavox.core.testing.FakeLyricsProvider
import eu.schicht8.meavox.core.testing.InMemoryLyricsRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsOffsetTest {
    private val lyrics = InMemoryLyricsRepository()
    private val offset = LyricsOffset(lyrics)
    private val track = TrackId(SourceId("local"), "abc")
    private val other = TrackId(SourceId("local"), "def")

    @Test
    fun `the offset starts at zero, adds up per Track and resets`() = runTest {
        assertEquals(0L, offset.observe(track).first())

        offset.nudge(track, +1_000)
        offset.nudge(track, -100)
        offset.nudge(other, +100)
        val nudged = offset.observe(track).first()
        offset.reset(track)

        assertEquals(900L, nudged)
        assertEquals(0L, offset.observe(track).first())
        assertEquals(100L, offset.observe(other).first())
    }

    @Test
    fun `the offset stays when other Lyrics are chosen`() = runTest {
        val match = MatchLyrics(InMemoryTrackRepository(), lyrics, FakeLyricsProvider())
        lyrics.set(track, TrackLyrics.Confirmed(LyricsCandidate("1", "A", "Song", null, null, "[00:01.00]la")))
        offset.nudge(track, +2_300)

        match.choose(track, LyricsCandidate("2", "A", "Song", null, null, "[00:01.50]la"))

        assertEquals(2_300L, offset.observe(track).first())
    }

    @Test
    fun `quick taps all count, even when they overlap`() = runTest {
        repeat(10) { launch { offset.nudge(track, +100) } } // each tap starts its own coroutine

        advanceUntilIdle()

        assertEquals(1_000L, offset.observe(track).first())
    }
}
