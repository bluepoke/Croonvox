// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.lyrics

import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.TrackId
import app.croonvox.core.port.LyricsProvider
import app.croonvox.core.port.LyricsUnavailable
import kotlinx.coroutines.CancellationException

/**
 * Searches a Track's Lyrics by its cleaned title and suggests the best match (ADR 6). Runs beside
 * the Track Pipeline and never changes it.
 */
class FetchLyrics(
    private val tracks: TrackRepository,
    private val lyrics: LyricsRepository,
    private val provider: LyricsProvider,
) {
    enum class Outcome {
        /** The Lyrics are settled: Suggested, NotFound or Failed (or were already). */
        Done,

        /** They stay Searching; run the search again later. */
        RetryLater,
    }

    /**
     * Searches unless the Track's Lyrics are already settled, so running it again is harmless.
     * On the [lastAttempt] a failure worth retrying ends Failed too, so the Lyrics never stay
     * Searching for good and the user can search by hand.
     */
    suspend operator fun invoke(id: TrackId, lastAttempt: Boolean = false): Outcome {
        val track = tracks.get(id) ?: return Outcome.Done
        if (lyrics.get(id).let { it != null && it != TrackLyrics.Searching }) return Outcome.Done
        val query = track.lyricsQuery()
        val found = try {
            // The "artist" may really be a channel name: then the title alone still finds the song.
            provider.search(query).ifEmpty {
                if (query.artist != null) provider.search(query.copy(artist = null)) else emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is LyricsUnavailable && e.retryable && !lastAttempt) return Outcome.RetryLater
            lyrics.set(id, TrackLyrics.Failed(e.message ?: "Lyrics search failed"))
            return Outcome.Done
        }
        val best = LyricsRanking.rank(found, query).firstOrNull()
        lyrics.set(id, if (best != null) TrackLyrics.Suggested(best, query) else TrackLyrics.NotFound(query))
        return Outcome.Done
    }
}
