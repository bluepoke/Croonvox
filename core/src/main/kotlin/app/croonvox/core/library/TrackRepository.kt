// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.library

import app.croonvox.core.model.Track
import kotlinx.coroutines.flow.Flow

/** Persistence of Tracks. Implemented by the data layer (Room); faked in tests. */
interface TrackRepository {
    fun observeAll(): Flow<List<Track>>

    /**
     * Stores [track] unless a Track with the same identity exists; returns the stored Track
     * (the existing one, or [track]). Must be atomic.
     */
    suspend fun addIfAbsent(track: Track): Track
}
