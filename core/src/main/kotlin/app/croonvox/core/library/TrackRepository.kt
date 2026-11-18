// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.library

import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import kotlinx.coroutines.flow.Flow

/** Persistence of Tracks. Implemented by the data layer (Room); faked in tests. */
interface TrackRepository {
    fun observeAll(): Flow<List<Track>>

    /** The Track with [id], updated as it changes; `null` while it doesn't exist. */
    fun observe(id: TrackId): Flow<Track?>

    suspend fun get(id: TrackId): Track?

    /**
     * Stores [track] unless a Track with the same identity exists; returns the stored Track
     * (the existing one, or [track]). Must be atomic.
     */
    suspend fun addIfAbsent(track: Track): Track

    /**
     * Atomically replaces the Track with what [change] makes of it as stored now; [change] returns
     * null to leave it as it is. Returns the Track as stored afterwards, or null if there is none.
     * [change] may run more than once, so it must not have side effects.
     */
    suspend fun update(id: TrackId, change: (Track) -> Track?): Track?

    /** Sets the Track Pipeline state of an existing Track and returns the updated Track. */
    suspend fun updatePipeline(id: TrackId, state: TrackPipelineState): Track

    /** Removes the Track, with the Lyrics and Lyrics Offset stored for it. Does nothing if it doesn't exist. */
    suspend fun delete(id: TrackId)

    /** Records that a Take of the Track started recording at [at] (epoch ms): when it was last sung. */
    suspend fun setLastSessionAt(id: TrackId, at: Long)
}
