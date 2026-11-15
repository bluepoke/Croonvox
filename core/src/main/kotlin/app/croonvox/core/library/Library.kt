// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.library

import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.port.SourceCandidate
import kotlinx.coroutines.flow.Flow

/** The user's collection of Tracks. */
class Library(private val tracks: TrackRepository) {
    fun tracks(): Flow<List<Track>> = tracks.observeAll()

    /**
     * Adds a Source search result as a new Track in the [TrackPipelineState.Added] state.
     * If the same Source item was added before, returns that Track unchanged (ADR 10).
     */
    suspend fun add(candidate: SourceCandidate): Track {
        val track = Track(
            id = candidate.id,
            title = candidate.title,
            artist = candidate.artist,
            durationMs = candidate.durationMs,
            pipeline = TrackPipelineState.Added,
        )
        return tracks.addIfAbsent(track)
    }
}
