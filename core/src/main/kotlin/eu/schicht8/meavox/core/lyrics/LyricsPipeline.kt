// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.lyrics

import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.pipeline.PipelineJobs
import kotlinx.coroutines.flow.combine

/**
 * Searches the Lyrics of every Track as soon as it is in the Library, in parallel with the
 * Track Pipeline and without touching it (ADR 6, ADR 10).
 */
class LyricsPipeline(
    private val tracks: TrackRepository,
    private val lyrics: LyricsRepository,
    private val jobs: PipelineJobs,
) {
    /** Follows the Library until cancelled; runs for the lifetime of the app process. */
    suspend fun run() {
        // Tracks this run has queued a search for: each once, as their state changes again and again.
        val queued = mutableSetOf<TrackId>()
        combine(tracks.observeAll(), lyrics.observeAll()) { all, known -> all to known }.collect { (all, known) ->
            for (track in all) {
                when (known[track.id]) {
                    // Only if still absent: the map may be older than a search that just finished.
                    null -> if (lyrics.addIfAbsent(track.id, TrackLyrics.Searching)) {
                        queued += track.id
                        jobs.fetchLyrics(track.id)
                    }
                    // Left Searching by an earlier process (or queued above): make sure its job exists.
                    TrackLyrics.Searching -> if (queued.add(track.id)) jobs.fetchLyrics(track.id)
                    else -> queued -= track.id
                }
            }
        }
    }
}
