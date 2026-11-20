// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.lyrics.MatchLyrics
import eu.schicht8.meavox.core.lyrics.TrackLyrics
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.port.LyricsCandidate
import eu.schicht8.meavox.core.port.LyricsQuery
import eu.schicht8.meavox.core.testing.FakeLyricsProvider
import eu.schicht8.meavox.core.testing.InMemoryLyricsRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MatchLyricsTest {
    private val tracks = InMemoryTrackRepository()
    private val lyrics = InMemoryLyricsRepository()
    private val provider = FakeLyricsProvider()
    private val match = MatchLyrics(tracks, lyrics, provider)
    private val id = TrackId(SourceId("local"), "abc")

    private fun candidate(id: String, title: String, durationMs: Long? = null, synced: Boolean = true) = LyricsCandidate(
        providerItemId = id,
        artist = "Adele",
        title = title,
        durationMs = durationMs,
        plain = "There's a fire starting in my heart",
        synced = if (synced) "[00:01.00] There's a fire starting in my heart" else null,
    )

    @Test
    fun `confirming a suggestion keeps its Lyrics`() = runTest {
        val suggested = candidate("1", "Rolling in the Deep")
        lyrics.set(id, TrackLyrics.Suggested(suggested, LyricsQuery("Adele", "Rolling in the Deep", null)))

        match.confirm(id)

        assertEquals(TrackLyrics.Confirmed(suggested), lyrics.get(id))
    }

    @Test
    fun `a manual search lists candidates best first, and picking one confirms it`() = runTest {
        val wrong = candidate("1", "Rolling in the Deep (Live)", durationMs = 280_000, synced = false)
        val right = candidate("2", "Rolling in the Deep", durationMs = 228_000)
        provider.add(wrong)
        provider.add(right)
        lyrics.set(id, TrackLyrics.Suggested(wrong, LyricsQuery("Adele", "Rolling", null)))

        val results = match.search(LyricsQuery("Adele", "Rolling in the Deep", 228_000))
        match.choose(id, results.first())

        assertEquals(listOf(right, wrong), results)
        assertEquals(TrackLyrics.Confirmed(right), lyrics.get(id))
    }

    @Test
    fun `the search form starts from the query of the last search, or from the cleaned title`() = runTest {
        tracks.addIfAbsent(Track(id, "Adele - Rolling in the Deep (Official Music Video)", null, 228_000, TrackPipelineState.Downloaded))
        val notFound = LyricsQuery("Adele", "Rolling in the Deep", 228_000)

        val fromTitle = match.searchQuery(id)
        lyrics.set(id, TrackLyrics.NotFound(notFound.copy(title = "Rolling in the Deep (Remix)")))
        val fromLastSearch = match.searchQuery(id)

        assertEquals(notFound, fromTitle)
        assertEquals(notFound.copy(title = "Rolling in the Deep (Remix)"), fromLastSearch)
    }
}
