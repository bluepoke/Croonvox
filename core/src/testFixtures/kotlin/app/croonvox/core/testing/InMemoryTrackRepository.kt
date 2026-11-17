// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

class InMemoryTrackRepository : TrackRepository {
    private val tracks = MutableStateFlow<List<Track>>(emptyList())

    override fun observeAll(): Flow<List<Track>> = tracks

    override fun observe(id: TrackId): Flow<Track?> = tracks.map { all -> all.firstOrNull { it.id == id } }

    override suspend fun get(id: TrackId): Track? = tracks.value.firstOrNull { it.id == id }

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

    override suspend fun updatePipeline(id: TrackId, state: TrackPipelineState): Track {
        var updated: Track? = null
        tracks.update { current ->
            current.map { if (it.id == id) it.copy(pipeline = state).also { t -> updated = t } else it }
        }
        return checkNotNull(updated) { "no Track $id" }
    }

    override suspend fun update(id: TrackId, change: (Track) -> Track?): Track? {
        val updated = tracks.updateAndGet { current ->
            current.map { if (it.id == id) change(it) ?: it else it }
        }
        return updated.firstOrNull { it.id == id }
    }

    override suspend fun delete(id: TrackId) {
        tracks.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun setLastSessionAt(id: TrackId, at: Long) {
        tracks.update { current -> current.map { if (it.id == id) it.copy(lastSessionAt = at) else it } }
    }
}
