// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [TrackRepository] on Room. Newest Tracks first. */
class RoomTrackRepository(
    database: CroonvoxDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : TrackRepository {
    private val dao = database.tracks()

    override fun observeAll(): Flow<List<Track>> = dao.observeAll().map { rows -> rows.map { it.toTrack() } }

    override suspend fun addIfAbsent(track: Track): Track = dao.insertIfAbsent(track.toEntity(clock())).toTrack()
}
