// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.lyrics

import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.port.LyricsCandidate
import app.croonvox.core.port.LyricsProvider
import app.croonvox.core.port.LyricsQuery

/** The user's side of matching Lyrics (ADR 6): confirm the suggestion, or search and pick another. */
class MatchLyrics(
    private val tracks: TrackRepository,
    private val lyrics: LyricsRepository,
    private val provider: LyricsProvider,
) {
    /** Confirms the suggested Lyrics; does nothing unless there is a suggestion. */
    suspend fun confirm(track: TrackId) {
        val current = lyrics.get(track)
        if (current is TrackLyrics.Suggested) lyrics.set(track, TrackLyrics.Confirmed(current.lyrics))
    }

    /** What the search form starts with: the last search, or the Track's cleaned title. */
    suspend fun searchQuery(track: TrackId): LyricsQuery? = when (val current = lyrics.get(track)) {
        is TrackLyrics.Suggested -> current.query
        is TrackLyrics.NotFound -> current.query
        else -> tracks.get(track)?.lyricsQuery()
    }

    /**
     * Searches the provider, best match first.
     * @throws app.croonvox.core.port.LyricsUnavailable if the provider can't answer.
     */
    suspend fun search(query: LyricsQuery): List<LyricsCandidate> = LyricsRanking.rank(provider.search(query), query)

    /** Makes [candidate] the Track's confirmed Lyrics. */
    suspend fun choose(track: TrackId, candidate: LyricsCandidate) {
        lyrics.set(track, TrackLyrics.Confirmed(candidate))
    }
}

/** The search for a Track's Lyrics: its cleaned title, and its duration if known. */
internal fun Track.lyricsQuery(): LyricsQuery {
    val clean = TitleCleaner.clean(title, artist)
    return LyricsQuery(clean.artist, clean.title, durationMs)
}
