// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class TrackDao {
    @Query("SELECT * FROM tracks ORDER BY added_at DESC")
    abstract fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE source = :source AND source_track_id = :sourceTrackId")
    abstract suspend fun get(source: String, sourceTrackId: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertOrIgnore(track: TrackEntity): Long

    /** Inserts [track] unless its identity exists; returns the stored row. */
    @Transaction
    open suspend fun insertIfAbsent(track: TrackEntity): TrackEntity {
        insertOrIgnore(track)
        return checkNotNull(get(track.source, track.sourceTrackId))
    }
}
