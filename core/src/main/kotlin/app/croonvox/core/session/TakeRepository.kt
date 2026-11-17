// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.session

import app.croonvox.core.model.Take
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.TrackId
import kotlinx.coroutines.flow.Flow

/** Persistence of kept Takes. Implemented by the data layer (Room); faked in tests. */
interface TakeRepository {
    /** The Track's Takes, oldest first, updated as they change. */
    fun observe(track: TrackId): Flow<List<Take>>

    /** How many Takes each Track has, updated as they change; Tracks without Takes are left out. */
    fun observeCounts(): Flow<Map<TrackId, Int>>

    suspend fun get(id: TakeId): Take?

    suspend fun add(take: Take)

    suspend fun rename(id: TakeId, name: String)

    suspend fun delete(id: TakeId)

    /** Deletes all of the Track's Takes. */
    suspend fun deleteAll(track: TrackId)
}
