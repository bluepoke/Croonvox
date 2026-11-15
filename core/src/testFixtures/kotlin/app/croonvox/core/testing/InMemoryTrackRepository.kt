// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemoryTrackRepository : TrackRepository {
    private val tracks = MutableStateFlow<List<Track>>(emptyList())

    override fun observeAll(): Flow<List<Track>> = tracks

    override suspend fun addIfAbsent(track: Track): Track {
        var stored = track
        tracks.update { current ->
            val existing = current.firstOrNull { it.id == track.id }
            if (existing != null) {
                stored = existing
                current
            } else {
                stored = track
                current + track
            }
        }
        return stored
    }
}
