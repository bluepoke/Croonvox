// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [TrackRepository] on Room. Newest Tracks first. */
class RoomTrackRepository(
    database: CroonvoxDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : TrackRepository {
    private val dao = database.tracks()

    override fun observeAll(): Flow<List<Track>> = dao.observeAll().map { rows -> rows.map { it.toTrack() } }

    override fun observe(id: TrackId): Flow<Track?> =
        dao.observe(id.source.value, id.sourceTrackId).map { it?.toTrack() }

    override suspend fun get(id: TrackId): Track? = dao.get(id.source.value, id.sourceTrackId)?.toTrack()

    override suspend fun addIfAbsent(track: Track): Track = dao.insertIfAbsent(track.toEntity(clock())).toTrack()

    override suspend fun updatePipeline(id: TrackId, state: TrackPipelineState): Track =
        dao.updatePipeline(id.source.value, id.sourceTrackId, state.toColumns()).toTrack()

    override suspend fun delete(id: TrackId) = dao.delete(id.source.value, id.sourceTrackId)

    override suspend fun setLastSessionAt(id: TrackId, at: Long) = dao.setLastSessionAt(id.source.value, id.sourceTrackId, at)
}
