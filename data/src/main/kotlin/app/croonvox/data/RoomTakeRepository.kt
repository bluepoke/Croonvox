// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import app.croonvox.core.model.Take
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.TrackId
import app.croonvox.core.session.TakeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [TakeRepository] on Room. */
class RoomTakeRepository(database: CroonvoxDatabase) : TakeRepository {
    private val dao = database.takes()

    override fun observe(track: TrackId): Flow<List<Take>> =
        dao.observe(track.source.value, track.sourceTrackId).map { rows -> rows.map { it.toTake() } }

    override fun observeCounts(): Flow<Map<TrackId, Int>> =
        dao.observeCounts().map { rows -> rows.associate { TrackId(SourceId(it.source), it.sourceTrackId) to it.count } }

    override suspend fun get(id: TakeId): Take? = dao.get(id.value)?.toTake()

    override suspend fun add(take: Take) = dao.insert(take.toEntity())

    override suspend fun rename(id: TakeId, name: String) = dao.rename(id.value, name)

    override suspend fun delete(id: TakeId) = dao.delete(id.value)

    override suspend fun deleteAll(track: TrackId) = dao.deleteAll(track.source.value, track.sourceTrackId)
}
